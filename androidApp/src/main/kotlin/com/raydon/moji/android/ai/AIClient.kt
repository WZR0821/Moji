package com.raydon.moji.android.ai

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One HTTP exchange, so tests and the QA fixture can stand in for the network. */
class AIHttpRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
    /** Idle timeout, like `URLRequest.timeoutInterval`. */
    val timeoutSeconds: Double,
)

class AIHttpResponse(val status: Int, val body: ByteArray)

interface AITransport {
    suspend fun send(request: AIHttpRequest): AIHttpResponse
}

/**
 * `HttpURLConnection` on the IO pool. No cache, no cookies; cancelling the
 * coroutine disconnects, as cancelling a URLSession task does.
 */
object AIHttpTransport : AITransport {
    /** Swift's `timeoutIntervalForResource`. */
    private const val RESOURCE_TIMEOUT_MS = 90_000L
    /** A body larger than any answer a task can need is not read further. */
    private const val READ_LIMIT = 2_000_001

    override suspend fun send(request: AIHttpRequest): AIHttpResponse =
        withTimeoutOrNull(RESOURCE_TIMEOUT_MS) { exchange(request) } ?: throw AIRequestError.timeout

    private suspend fun exchange(request: AIHttpRequest): AIHttpResponse = suspendCancellableCoroutine { continuation ->
        val connection = try {
            URL(request.url).openConnection() as HttpURLConnection
        } catch (error: Exception) {
            continuation.resumeWithException(AIRequestError.invalidURL)
            return@suspendCancellableCoroutine
        }
        continuation.invokeOnCancellation { runCatching { connection.disconnect() } }
        Dispatchers.IO.asExecutor().execute {
            val result = runCatching {
                val millis = (request.timeoutSeconds * 1000).toInt().coerceAtLeast(1)
                connection.connectTimeout = millis
                connection.readTimeout = millis
                connection.useCaches = false
                connection.requestMethod = request.method
                request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                val body = request.body
                if (body != null) {
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                AIHttpResponse(status, stream?.use { readLimited(it) } ?: ByteArray(0))
            }
            runCatching { connection.disconnect() }
            result.fold(
                onSuccess = { continuation.resume(it) },
                onFailure = { if (continuation.isActive) continuation.resumeWithException(it) },
            )
        }
    }

    private fun readLimited(stream: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (out.size() < READ_LIMIT) {
            val count = stream.read(buffer, 0, minOf(buffer.size, READ_LIMIT - out.size()))
            if (count < 0) break
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
}

/**
 * Sends one instruction and one piece of text to the provider in use and
 * returns the model's text. The request formats, limits and reasoning fields
 * follow the provider layer of 读屏, as on iOS.
 */
class AIClient(private val transport: AITransport = liveTransport) {

    /** One request, one answer. `entryID` tells which provider the fields are for. */
    suspend fun complete(
        instructions: String,
        input: String,
        entryID: String,
        configuration: ProviderConfiguration,
        apiKey: String,
        timeout: Double = 40.0,
    ): String {
        val config = configuration.requestFields
        val model = config.model.trim()
        if (model.isEmpty()) throw AIRequestError.missingModel
        val headers = authorizedHeaders(config, apiKey) + ("Content-Type" to "application/json")
        val body = body(instructions, input, AIProvider.kind(entryID), config, model)
        val request = AIHttpRequest(endpoint(config), "POST", headers, body.toString().toByteArray(Charsets.UTF_8), timeout)
        return parseText(perform(request), config.style)
    }

    /** The model ids the account can use, as the provider lists them. */
    suspend fun models(configuration: ProviderConfiguration, apiKey: String): List<String> {
        val headers = authorizedHeaders(configuration, apiKey)
        val data = perform(AIHttpRequest(endpoint(configuration, models = true), "GET", headers, null, 15.0))
        val root = parse(data) as? JsonObject ?: throw AIRequestError.malformedResponse
        val items = objects(root["data"]) ?: throw AIRequestError.malformedResponse
        val models = items.mapNotNull { it["id"].aiString() }.toSet().sorted()
        if (models.isEmpty()) throw AIRequestError.emptyResponse
        return models
    }

    private fun authorizedHeaders(configuration: ProviderConfiguration, apiKey: String): Map<String, String> {
        val key = apiKey.trim()
        if (key.isEmpty()) throw AIRequestError.missingKey
        if (key.contains('\n') || key.contains('\r')) throw AIRequestError.credentialUnavailable
        // Validate the address before anything else is built.
        endpoint(configuration)
        val headers = linkedMapOf<String, String>()
        if (configuration.style == APIStyle.anthropicMessages) {
            headers["x-api-key"] = key
            headers["anthropic-version"] = "2023-06-01"
        } else {
            headers["Authorization"] = "Bearer $key"
        }
        headers["Accept"] = "application/json"
        headers["Cache-Control"] = "no-cache"
        return headers
    }

    private suspend fun perform(request: AIHttpRequest): ByteArray {
        val response = try {
            transport.send(request)
        } catch (error: CancellationException) {
            throw error
        } catch (error: AIRequestError) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw AIRequestError.timeout
        } catch (error: IOException) {
            throw AIRequestError.network
        } catch (error: Exception) {
            throw AIRequestError.network
        }
        if (response.status !in 200..299) {
            throw AIProviderErrorMessage.error(response.status, response.body, secrets(request))
        }
        if (response.body.size > 2_000_000) throw AIRequestError.malformedResponse
        return response.body
    }

    companion object {
        /** The network, or the debug QA stand-in while the fixture is on. */
        val liveTransport: AITransport
            get() = if (AIQAFixture.isActive) AIQAFixture.transport else AIHttpTransport

        /** The full request URL. Throws [AIRequestError.invalidURL]. */
        fun endpoint(configuration: ProviderConfiguration, models: Boolean = false): String {
            val input = configuration.baseURL.trim()
            val uri = try { URI(input) } catch (error: Exception) { throw AIRequestError.invalidURL }
            if (uri.scheme?.lowercase() != "https" || uri.isOpaque) throw AIRequestError.invalidURL
            val authority = uri.rawAuthority ?: throw AIRequestError.invalidURL
            val host = uri.host ?: authority.substringBefore(':')
            if (host.isEmpty() || uri.rawUserInfo != null || authority.contains('@') ||
                uri.rawQuery != null || uri.rawFragment != null) throw AIRequestError.invalidURL
            var path = uri.rawPath ?: ""
            while (path.endsWith("/")) path = path.dropLast(1)
            // Accept either an API base URL or a full endpoint without appending it twice.
            for (suffix in listOf("/chat/completions", "/responses", "/messages", "/models")) {
                if (path.endsWith(suffix)) { path = path.dropLast(suffix.length); break }
            }
            return "${uri.scheme}://$authority$path/" + (if (models) "models" else configuration.style.route)
        }

        fun body(
            instructions: String,
            input: String,
            provider: AIProvider,
            configuration: ProviderConfiguration,
            model: String,
        ): JsonObject {
            val body = linkedMapOf<String, JsonElement>("model" to JsonPrimitive(model))
            when (configuration.style) {
                APIStyle.anthropicMessages -> {
                    body["system"] = JsonPrimitive(instructions)
                    body["max_tokens"] = JsonPrimitive(4_096)
                    body["messages"] = buildJsonArray { add(message("user", input)) }
                }
                APIStyle.responses -> {
                    body["instructions"] = JsonPrimitive(instructions)
                    body["input"] = JsonPrimitive(input)
                    body["store"] = JsonPrimitive(false)
                }
                APIStyle.chatCompletions -> {
                    body["messages"] = buildJsonArray { add(message("system", instructions)); add(message("user", input)) }
                }
            }
            applyOutputLimits(body, provider, configuration, model)
            applyReasoning(body, provider, configuration, model)
            return JsonObject(body)
        }

        private fun message(role: String, content: String) = buildJsonObject { put("role", role); put("content", content) }

        private fun typeField(value: String) = buildJsonObject { put("type", value) }

        private fun effortField(value: String) = buildJsonObject { put("effort", value) }

        /**
         * Fields a known model needs to return a whole answer, whatever the
         * reasoning setting. Unknown models and other interface formats get nothing.
         */
        fun applyOutputLimits(
            body: MutableMap<String, JsonElement>,
            provider: AIProvider,
            configuration: ProviderConfiguration,
            model: String,
        ) {
            if (configuration.style != APIStyle.chatCompletions) return
            val id = model.lowercase()
            when {
                // Without a limit the answer stops at 4K tokens.
                provider == AIProvider.doubao && id.startsWith("doubao-seed-2-1") -> body["max_tokens"] = JsonPrimitive(16_000)
                // Reasoning counts toward the limit; Kimi's documentation asks for 16000 or more.
                provider == AIProvider.kimi && id.startsWith("kimi-k3") -> body["max_tokens"] = JsonPrimitive(16_000)
                provider == AIProvider.minimax && id.startsWith("minimax-m") -> {
                    // Keeps the model's thinking out of the answer.
                    body["reasoning_split"] = JsonPrimitive(true)
                    if (id.startsWith("minimax-m3")) body["max_completion_tokens"] = JsonPrimitive(32_000)
                }
            }
        }

        /**
         * Reasoning fields. `null` (自动) keeps reasoning low for known model
         * families, since every task here is short, and otherwise sends nothing;
         * an explicit choice is sent in the provider's own format, and an
         * unsupported choice surfaces as the provider's error message.
         */
        fun applyReasoning(
            body: MutableMap<String, JsonElement>,
            provider: AIProvider,
            configuration: ProviderConfiguration,
            model: String,
        ) {
            val chosen = configuration.reasoning
            if (chosen == null || chosen !in provider.reasoningOptions) {
                applyAutomaticReasoning(body, provider, configuration, model)
                return
            }
            val off = chosen == ReasoningEffort.off
            val level = chosen.rawValue
            if (provider == AIProvider.qwen) {
                if (off && configuration.style != APIStyle.anthropicMessages) body["enable_thinking"] = JsonPrimitive(false)
                return
            }
            when (configuration.style) {
                APIStyle.responses -> body["reasoning"] = effortField(if (off) "none" else level)
                APIStyle.chatCompletions -> when (provider) {
                    AIProvider.deepSeek, AIProvider.doubao -> {
                        body["thinking"] = typeField(if (off) "disabled" else "enabled")
                        if (!off) body["reasoning_effort"] = JsonPrimitive(level)
                    }
                    // Gemini 2.x Flash can turn thinking off; later models only go down to minimal.
                    AIProvider.gemini -> body["reasoning_effort"] =
                        JsonPrimitive(if (off) (if (model.startsWith("gemini-2")) "none" else "minimal") else level)
                    // K3 always thinks and takes low, high or max.
                    AIProvider.kimi -> body["reasoning_effort"] = JsonPrimitive(threeStepEffort(chosen))
                    AIProvider.zhipu -> {
                        val id = model.lowercase()
                        if (id.startsWith("glm-5.3")) {
                            // Thinking cannot be turned off; these models take low, high or max.
                            body["reasoning_effort"] = JsonPrimitive(threeStepEffort(chosen))
                        } else {
                            body["thinking"] = typeField(if (off) "disabled" else "enabled")
                            // Earlier models switch thinking on or off and have no effort level.
                            if (!off && id.startsWith("glm-5.2")) body["reasoning_effort"] = JsonPrimitive(level)
                        }
                    }
                    AIProvider.minimax -> {
                        if (model.lowercase().startsWith("minimax-m3.1")) {
                            // Thinking is always on for this model; only the effort goes down.
                            body["reasoning_effort"] = JsonPrimitive(if (off) "low" else level)
                        } else {
                            // Other models think or do not, and ignore an effort level.
                            body["thinking"] = typeField(if (off) "disabled" else "adaptive")
                        }
                    }
                    else -> body["reasoning_effort"] = JsonPrimitive(if (off) "none" else level)
                }
                APIStyle.anthropicMessages -> {
                    if (provider == AIProvider.deepSeek) {
                        body["thinking"] = typeField(if (off) "disabled" else "enabled")
                        if (!off) body["output_config"] = effortField(level)
                    } else {
                        applyClaudeThinking(body, chosen, model)
                    }
                }
            }
        }

        /** 关闭 and 低 share the lowest step on models that take only low, high and max. */
        private fun threeStepEffort(effort: ReasoningEffort): String = when (effort) {
            ReasoningEffort.off, ReasoningEffort.low -> "low"
            ReasoningEffort.medium -> "high"
            ReasoningEffort.high -> "max"
        }

        private fun applyClaudeThinking(body: MutableMap<String, JsonElement>, effort: ReasoningEffort, model: String) {
            val id = model.lowercase()
            // Older models only understand a fixed thinking budget and do not think unless asked.
            val budgetPrefixes = listOf("claude-3", "claude-haiku-4", "claude-sonnet-4-5", "claude-sonnet-4-0", "claude-sonnet-4-2",
                "claude-opus-4-0", "claude-opus-4-1", "claude-opus-4-5", "claude-opus-4-2")
            if (budgetPrefixes.any { id.startsWith(it) } || id == "claude-sonnet-4" || id == "claude-opus-4") {
                val budget = when (effort) {
                    ReasoningEffort.off -> return
                    ReasoningEffort.low -> 1_024
                    ReasoningEffort.medium -> 4_096
                    ReasoningEffort.high -> 8_192
                }
                // Thinking counts toward max_tokens; keep the answer's own room.
                body["thinking"] = buildJsonObject { put("type", "enabled"); put("budget_tokens", budget) }
                body["max_tokens"] = JsonPrimitive(4_096 + budget)
                return
            }
            if (effort == ReasoningEffort.off) {
                if (id.startsWith("claude-sonnet-5-5")) {
                    body["thinking"] = typeField("between_tools")
                } else if (listOf("claude-opus-5-5", "claude-fable", "claude-mythos").any { id.startsWith(it) }) {
                    // Thinking cannot be turned off on these models; use the lowest effort.
                    body["output_config"] = effortField("low")
                } else {
                    body["thinking"] = typeField("disabled")
                }
                return
            }
            body["thinking"] = typeField("adaptive")
            body["output_config"] = effortField(effort.rawValue)
            body["max_tokens"] = JsonPrimitive(16_000)
        }

        /** 自动: only documented fields for known model families; custom endpoints get nothing. */
        private fun applyAutomaticReasoning(
            body: MutableMap<String, JsonElement>,
            provider: AIProvider,
            configuration: ProviderConfiguration,
            model: String,
        ) {
            val id = model.lowercase()
            if (provider == AIProvider.deepSeek && model in listOf("deepseek-flash", "deepseek-v4-pro")) {
                // DeepSeek defaults to high reasoning, which is slow for a short task.
                if (configuration.style == APIStyle.responses) body["reasoning"] = effortField("none")
                else body["thinking"] = typeField("disabled")
            }
            if (provider == AIProvider.openAI) {
                val efforts = mapOf("gpt-6-luna" to "none", "gpt-6-sol" to "none", "gpt-6.1-sol" to "low", "gpt-6-astra" to "low")
                efforts[model]?.let { value ->
                    if (configuration.style == APIStyle.responses) body["reasoning"] = effortField(value)
                    else if (configuration.style == APIStyle.chatCompletions) body["reasoning_effort"] = JsonPrimitive(value)
                }
            }
            if (provider == AIProvider.claude && configuration.style == APIStyle.anthropicMessages &&
                listOf("claude-opus-5", "claude-sonnet-5", "claude-haiku-5", "claude-fable", "claude-mythos").any { id.startsWith(it) }) {
                // The 5 series thinks adaptively by default. Low effort keeps a short
                // task short; thinking stays available.
                body["output_config"] = effortField("low")
            }
            if (configuration.style != APIStyle.chatCompletions) return
            when {
                // 2.5 Flash and Flash-Lite can turn thinking off.
                provider == AIProvider.gemini && id.startsWith("gemini-2.5-flash") -> body["reasoning_effort"] = JsonPrimitive("none")
                // Thinking cannot be turned off on later models; minimal is their lowest level.
                provider == AIProvider.gemini && id.contains("flash") && (id.startsWith("gemini-3") || id.startsWith("gemini-4")) ->
                    body["reasoning_effort"] = JsonPrimitive("minimal")
                (provider == AIProvider.doubao && id.startsWith("doubao-seed-2-1")) || (provider == AIProvider.zhipu && id.startsWith("glm-5.2")) ->
                    body["thinking"] = typeField("disabled")
                // Thinking cannot be turned off here.
                (provider == AIProvider.kimi && id.startsWith("kimi-k3")) || (provider == AIProvider.zhipu && id.startsWith("glm-5.3")) ||
                    (provider == AIProvider.minimax && id.startsWith("minimax-m3.1")) -> body["reasoning_effort"] = JsonPrimitive("low")
                provider == AIProvider.minimax && id == "minimax-m3" -> body["thinking"] = typeField("disabled")
            }
        }

        /** The credential sent with a request, so an echoed key is never shown. */
        private fun secrets(request: AIHttpRequest): List<String> {
            val values = mutableListOf<String>()
            request.headers["x-api-key"]?.let { values.add(it) }
            request.headers["Authorization"]?.let { values.add(if (it.startsWith("Bearer ")) it.drop(7) else it) }
            return values
        }

        internal fun parse(data: ByteArray): JsonElement? =
            runCatching { Json.parseToJsonElement(data.toString(Charsets.UTF_8)) }.getOrNull()

        fun parseText(data: ByteArray, style: APIStyle): String {
            val root = parse(data) as? JsonObject ?: throw AIRequestError.malformedResponse
            val text = when (style) {
                APIStyle.anthropicMessages -> {
                    val content = objects(root["content"]) ?: throw AIRequestError.malformedResponse
                    val joined = content.filter { it["type"].aiString() == "text" }
                        .mapNotNull { it["text"].aiString() }.joinToString("\n")
                    if (root["stop_reason"].aiString() == "max_tokens") throw AIRequestError.incompleteResponse
                    if (root["stop_reason"].aiString() == "refusal") throw AIRequestError.emptyResponse
                    joined
                }
                APIStyle.chatCompletions -> {
                    val choices = objects(root["choices"]) ?: throw AIRequestError.malformedResponse
                    val first = choices.firstOrNull() ?: throw AIRequestError.malformedResponse
                    val message = first["message"] as? JsonObject ?: throw AIRequestError.malformedResponse
                    val content = message["content"]
                    val joined = content.aiString()
                        ?: objects(content)?.mapNotNull { it["text"].aiString() }?.joinToString("\n")
                        ?: ""
                    if (first["finish_reason"].aiString() == "length") throw AIRequestError.incompleteResponse
                    joined
                }
                APIStyle.responses -> {
                    val output = objects(root["output"]) ?: throw AIRequestError.malformedResponse
                    // A reasoning item may precede the output message; never assume output[0] is text.
                    val joined = output.filter { it["type"].aiString() == "message" }
                        .flatMap { objects(it["content"]) ?: emptyList() }
                        .filter { it["type"].aiString() == "output_text" }
                        .mapNotNull { it["text"].aiString() }
                        .joinToString("\n")
                    if (root["status"].aiString() == "incomplete") throw AIRequestError.incompleteResponse
                    if (root["status"].aiString() == "failed") throw AIRequestError.emptyResponse
                    joined
                }
            }.trim()
            if (text.isEmpty()) throw AIRequestError.emptyResponse
            return text
        }

        /** An array whose items are all objects, like Swift's `as? [[String: Any]]`. */
        private fun objects(element: JsonElement?): List<JsonObject>? {
            val array = element as? JsonArray ?: return null
            return if (array.all { it is JsonObject }) array.map { it as JsonObject } else null
        }
    }
}

/**
 * Reads the human-readable message from a provider's JSON error body.
 * Only structured message fields are used: a raw or non-JSON body is never
 * shown, and anything that looks like a credential is masked.
 */
object AIProviderErrorMessage {
    const val maximumLength = 200

    private val patterns = listOf(
        Regex("(?i)bearer\\s+\\S+") to "••••",
        Regex("sk-[A-Za-z0-9*_\\-]{6,}") to "••••",
        Regex("AIza[0-9A-Za-z_\\-]{10,}") to "••••",
        Regex("(?i)(api[_-]?key|token|secret)([\"'\\s:=]+)[^\\s,\"'}]{6,}") to "$1$2••••",
    )

    fun extract(data: ByteArray, secrets: List<String> = emptyList()): String? {
        if (data.isEmpty() || data.size > 65_536) return null
        val json = AIClient.parse(data) ?: return null
        // Gemini's OpenAI-compatible endpoint may wrap the error in an array.
        val root = (json as? JsonArray)?.firstOrNull() ?: json
        val values = root as? JsonObject ?: return null
        var message: String? = null
        val error = values["error"]
        if (error is JsonObject) {
            message = error["message"].aiString() ?: error["msg"].aiString()
        } else if (error.aiString() != null) {
            message = error.aiString()
        }
        if (message == null) {
            message = values["message"].aiString() ?: values["detail"].aiString() ?: values["msg"].aiString()
        }
        return sanitized(message ?: return null, secrets)
    }

    fun sanitized(message: String, secrets: List<String> = emptyList()): String? {
        var text = message
        for (secret in secrets) if (secret.length >= 8) text = text.replace(secret, "••••")
        // Common key and token shapes, including partially masked echoes.
        for ((pattern, template) in patterns) text = pattern.replace(text, template)
        text = text.map { if (Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt()) ' ' else it }.joinToString("")
        text = text.replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return null
        if (text.codePointCount(0, text.length) > maximumLength) {
            text = text.substring(0, text.offsetByCodePoints(0, maximumLength)) + "…"
        }
        return text
    }

    fun error(status: Int, body: ByteArray, secrets: List<String>): AIRequestError {
        val detail = extract(body, secrets)
        return if (detail != null) AIRequestError.httpDetail(status, detail) else AIRequestError.http(status)
    }
}
