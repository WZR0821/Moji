package com.raydon.moji.android.ai

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Ported from Moji/AI/AIProviderModels.swift. Enum entry names are the Swift
// raw values, so `name` is what the settings JSON stores.

@Suppress("EnumEntryName")
enum class APIStyle {
    chatCompletions, responses, anthropicMessages;

    val rawValue: String get() = name
    val id: String get() = name

    val title: String
        get() = when (this) {
            responses -> "Responses"; chatCompletions -> "Chat Completions"; anthropicMessages -> "Claude Messages"
        }

    /** Name on the model page, in the words provider documents use. */
    val displayName: String
        get() = when (this) {
            chatCompletions -> "OpenAI 兼容"; responses -> "OpenAI Responses"; anthropicMessages -> "Claude 兼容"
        }

    val route: String
        get() = when (this) {
            responses -> "responses"; chatCompletions -> "chat/completions"; anthropicMessages -> "messages"
        }

    companion object {
        fun from(rawValue: String?): APIStyle? = entries.firstOrNull { it.name == rawValue }
    }
}

@Suppress("EnumEntryName")
enum class AIProvider {
    openAI, deepSeek, gemini, claude, qwen, custom,
    doubao, kimi, zhipu, minimax;

    val rawValue: String get() = name
    val id: String get() = name

    val title: String
        get() = when (this) {
            openAI -> "ChatGPT"; deepSeek -> "DeepSeek"
            gemini -> "Gemini"; claude -> "Claude"
            qwen -> "通义千问"; custom -> "自定义 API"
            doubao -> "豆包"; kimi -> "Kimi"
            zhipu -> "智谱 GLM"; minimax -> "MiniMax"
        }

    /** Who runs the service; the second line of a provider row. */
    val subtitle: String
        get() = when (this) {
            openAI -> "OpenAI"; deepSeek -> "深度求索"
            gemini -> "Google"; claude -> "Anthropic"
            qwen -> "阿里云百炼"; custom -> "OpenAI 或 Claude 兼容接口"
            doubao -> "火山方舟"; kimi -> "月之暗面"
            zhipu -> "智谱 AI"; minimax -> "稀宇科技"
        }

    val isRecommended: Boolean get() = this == deepSeek

    val defaultURL: String
        get() = when (this) {
            openAI -> "https://api.openai.com/v1"
            deepSeek -> "https://api.deepseek.com"
            gemini -> "https://generativelanguage.googleapis.com/v1beta/openai"
            claude -> "https://api.anthropic.com/v1"
            qwen -> "https://dashscope-us.aliyuncs.com/compatible-mode/v1"
            custom -> ""
            doubao -> "https://ark.cn-beijing.volces.com/api/v3"
            kimi -> "https://api.moonshot.cn/v1"
            zhipu -> "https://open.bigmodel.cn/api/paas/v4"
            minimax -> "https://api.minimax.cn/v1"
        }

    val defaultStyle: APIStyle
        get() = if (this == openAI) APIStyle.responses else if (this == claude) APIStyle.anthropicMessages else APIStyle.chatCompletions

    val models: List<ModelPreset>
        get() = when (this) {
            openAI -> listOf(ModelPreset("gpt-6-luna", "GPT-6 Luna", "日常够用"), ModelPreset("gpt-6.1-sol", "GPT-6.1 Sol", "更细致"), ModelPreset("gpt-4.1-mini", "GPT-4.1 Mini", "轻量"))
            deepSeek -> listOf(ModelPreset("deepseek-flash", "DeepSeek Flash", "日常够用"), ModelPreset("deepseek-v4-pro", "DeepSeek V4 Pro", "更细致"))
            gemini -> listOf(ModelPreset("gemini-3.8-flash", "Gemini 3.8 Flash", "日常够用"), ModelPreset("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite", "轻量"))
            claude -> listOf(ModelPreset("claude-haiku-5-5", "Claude Haiku 5.5", "日常够用"), ModelPreset("claude-sonnet-5-5", "Claude Sonnet 5.5", "更细致"), ModelPreset("claude-opus-5-5", "Claude Opus 5.5", "最细致"))
            qwen -> listOf(ModelPreset("qwen-plus", "Qwen Plus", "日常够用"), ModelPreset("qwen-turbo", "Qwen Turbo", "轻量"), ModelPreset("qwen-max", "Qwen Max", "更细致"))
            custom -> emptyList()
            doubao -> listOf(ModelPreset("doubao-seed-2-1-lite-260915", "豆包 Seed 2.1 Lite", "日常够用"), ModelPreset("doubao-seed-2-1-pro-260915", "豆包 Seed 2.1 Pro", "更细致"))
            kimi -> listOf(ModelPreset("kimi-k3.1-pro", "Kimi K3.1 Pro", "日常够用"), ModelPreset("kimi-k3.1-pro-highspeed", "Kimi K3.1 Pro 高速版", "更快"))
            zhipu -> listOf(ModelPreset("glm-5.3-flash", "GLM-5.3 Flash", "日常够用"), ModelPreset("glm-5.3", "GLM-5.3", "更细致"))
            minimax -> listOf(ModelPreset("MiniMax-M3.1-Flash", "MiniMax M3.1 Flash", "日常够用"), ModelPreset("MiniMax-M3", "MiniMax M3", "更细致"))
        }

    /** What to know about the address; shown under the key and address fields. */
    val setupNote: String
        get() = when (this) {
            qwen -> "默认是美国区域的地址，密钥要和区域一致。其他区域请把 API 地址改成控制台给出的地址。"
            doubao -> "默认是火山方舟（中国站）的地址。国际版 BytePlus 的地址和模型 ID 都不同，要用它请改 API 地址并手动添加模型。"
            kimi -> "默认是国内站的地址。国际站的密钥不通用，要用它请把 API 地址改成 https://api.moonshot.ai/v1。"
            zhipu -> "默认是国内站的地址。国际站 Z.AI 的密钥不通用，要用它请把 API 地址改成 https://api.z.ai/api/paas/v4。"
            minimax -> "默认是国内站的地址。国际站请把 API 地址改成 https://api.minimax.io/v1。"
            openAI, deepSeek, gemini, claude -> "已填入官方地址，一般不用改。"
            custom -> "填服务商给的 API 地址，一般以 /v1 结尾。接口格式不确定就保持默认。"
        }

    val documentationURL: String
        get() = when (this) {
            openAI -> "https://developers.openai.com/api/docs/models"
            deepSeek -> "https://api-docs.deepseek.com/"
            gemini -> "https://ai.google.dev/gemini-api/docs/openai"
            claude -> "https://platform.claude.com/docs/en/models/overview"
            qwen -> "https://www.alibabacloud.com/help/en/model-studio/compatibility-of-openai-with-dashscope"
            custom -> "https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create"
            doubao -> "https://docs.volcengine.com/docs/82379"
            kimi -> "https://platform.kimi.com/docs"
            zhipu -> "https://docs.bigmodel.cn"
            minimax -> "https://platform.minimax.cn/docs"
        }

    /** The console page where a key is created, when the provider has a stable one. */
    val keyURL: String?
        get() = when (this) {
            openAI -> "https://platform.openai.com/api-keys"
            deepSeek -> "https://platform.deepseek.com/api_keys"
            gemini -> "https://aistudio.google.com/apikey"
            claude -> "https://platform.claude.com/settings/keys"
            doubao -> "https://ark.volcengine.com/region:cn-beijing/apiKey"
            kimi -> "https://platform.kimi.com/console/api-keys"
            zhipu -> "https://bigmodel.cn/usercenter/proj-mgmt/apikeys"
            minimax -> "https://platform.minimax.cn/user-center/basic-information/interface-key"
            // 通义千问's console differs by region; the documentation link covers it.
            qwen, custom -> null
        }

    /** 通义千问 only enables thinking in streaming mode, so it offers 关闭 only. */
    val reasoningOptions: List<ReasoningEffort>
        get() = if (this == qwen) listOf(ReasoningEffort.off) else ReasoningEffort.entries.toList()

    val reasoningNote: String
        get() = when (this) {
            qwen -> "通义千问的思考模式需要流式输出，这里只提供关闭。"
            kimi -> "K3 系列始终思考，无法关闭：自动和“关闭”都用最低档。"
            zhipu -> "GLM-5.3 系列无法关闭思考，自动和“关闭”都用最低档；GLM-5.2 可以关闭。"
            minimax -> "M3.1 Flash 无法关闭思考，自动和“关闭”都用最低档；M3 只能开或关，不分强度。"
            custom -> "自动不发送任何推理参数；其他档位按所选接口格式发送标准字段。"
            openAI, deepSeek, gemini, claude, doubao -> "自动：墨记的整理都是短任务，对已知模型关闭思考或用最低档。"
        }

    companion object {
        /**
         * Order on the 添加供应商 page: Claude, ChatGPT and DeepSeek first, then
         * the rest, with no domestic/overseas split.
         */
        val presets: List<AIProvider> = listOf(claude, openAI, deepSeek, qwen, doubao, kimi, zhipu, minimax, gemini)

        /** A built-in provider's entry uses its raw value as id; any other id is a custom service. */
        fun kind(of: String): AIProvider = from(of) ?: custom

        fun from(rawValue: String?): AIProvider? = entries.firstOrNull { it.name == rawValue }
    }
}

data class ModelPreset(val id: String, val title: String, val detail: String)

/**
 * User-selected reasoning depth. `null` in a configuration means 自动: keep
 * the built-in, provider-specific behaviour and send nothing new.
 */
@Suppress("EnumEntryName")
enum class ReasoningEffort {
    off, low, medium, high;

    val rawValue: String get() = name
    val id: String get() = name

    val title: String
        get() = when (this) { off -> "关闭"; low -> "低"; medium -> "中"; high -> "高" }

    companion object {
        fun from(rawValue: String?): ReasoningEffort? = entries.firstOrNull { it.name == rawValue }
    }
}

/** One model kept under a provider, with the settings that belong to it. */
data class SavedModel(val id: String, val style: APIStyle, val reasoning: ReasoningEffort? = null) {
    // Same keys as the Swift Codable synthesis; nil optionals are left out.
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("style", style.rawValue)
        reasoning?.let { put("reasoning", it.rawValue) }
    }

    companion object {
        fun fromJson(element: JsonElement?): SavedModel? {
            val values = element as? JsonObject ?: return null
            val id = values["id"].aiString() ?: return null
            val style = APIStyle.from(values["style"].aiString()) ?: return null
            // Written by a newer build: fall back to 自动.
            return SavedModel(id, style, ReasoningEffort.from(values["reasoning"].aiString()))
        }
    }
}

/**
 * One provider entry. `model`, `style` and `reasoning` describe the model the
 * provider currently uses, which is what a request reads; `models` keeps the
 * other models and their own settings. Immutable: every change returns a copy,
 * so a StateFlow holding the settings always sees a new value.
 */
data class ProviderConfiguration(
    val baseURL: String,
    val model: String,
    val style: APIStyle,
    val reasoning: ReasoningEffort? = null,
    /** The user's own name for a custom service. */
    val name: String? = null,
    /**
     * `null` until the list is first edited; the provider's presets stand in,
     * so settings saved by earlier versions load without a migration.
     */
    val models: List<SavedModel>? = null,
) {
    constructor(provider: AIProvider) : this(
        baseURL = provider.defaultURL,
        model = provider.models.firstOrNull()?.id ?: "",
        style = provider.defaultStyle,
    )

    private val selectedID: String get() = model.trim()

    /** The fields a request depends on, without the name and the other models. */
    val requestFields: ProviderConfiguration get() = copy(name = null, models = null)

    /**
     * Every model kept under this provider. The one in use carries the
     * top-level settings, so code that edits `model` or `style` directly stays valid.
     */
    fun savedModels(provider: AIProvider): List<SavedModel> {
        val list = mutableListOf<SavedModel>()
        for (item in models ?: provider.models.map { SavedModel(it.id, style) }) {
            val id = item.id.trim()
            if (id.isEmpty() || list.any { it.id == id }) continue
            list.add(SavedModel(id, item.style, item.reasoning))
        }
        val selected = selectedID
        if (selected.isEmpty()) return list
        val current = SavedModel(selected, style, reasoning)
        val index = list.indexOfFirst { it.id == selected }
        if (index >= 0) list[index] = current else list.add(0, current)
        return list
    }

    /** Switches the provider to one of its kept models. */
    fun select(id: String, provider: AIProvider): ProviderConfiguration {
        val list = savedModels(provider)
        val item = list.firstOrNull { it.id == id } ?: return this
        return copy(models = list, model = item.id, style = item.style, reasoning = item.reasoning)
    }

    /** Adds a model, or replaces the settings of one that is already kept. */
    fun upsert(item: SavedModel, provider: AIProvider): ProviderConfiguration {
        val id = item.id.trim()
        if (id.isEmpty()) return this
        val list = savedModels(provider).toMutableList()
        val clean = SavedModel(id, item.style, item.reasoning)
        val index = list.indexOfFirst { it.id == id }
        if (index >= 0) list[index] = clean else list.add(clean)
        var result = copy(models = list)
        if (selectedID.isEmpty() || selectedID == id) {
            result = result.copy(model = id, style = clean.style, reasoning = clean.reasoning)
        }
        return result
    }

    /** Removes a model. When it was the one in use, the next kept model takes over. */
    fun removeModel(id: String, provider: AIProvider): ProviderConfiguration {
        val list = savedModels(provider).filter { it.id != id }
        val result = copy(models = list)
        if (selectedID != id) return result
        val next = list.firstOrNull()
        return if (next != null) result.copy(model = next.id, style = next.style, reasoning = next.reasoning)
        else result.copy(model = "", reasoning = null)
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("baseURL", baseURL)
        put("model", model)
        put("style", style.rawValue)
        reasoning?.let { put("reasoning", it.rawValue) }
        name?.let { put("name", it) }
        models?.let { list -> put("models", buildJsonArray { list.forEach { add(it.toJson()) } }) }
    }

    companion object {
        fun fromJson(element: JsonElement?): ProviderConfiguration? {
            val values = element as? JsonObject ?: return null
            val baseURL = values["baseURL"].aiString() ?: return null
            val model = values["model"].aiString() ?: return null
            val style = APIStyle.from(values["style"].aiString()) ?: return null
            val models = (values["models"] as? JsonArray)?.let { array ->
                // Like Swift's `try?` on the whole array: one bad item drops the list.
                val parsed = array.map { SavedModel.fromJson(it) }
                if (parsed.any { it == null }) null else parsed.filterNotNull()
            }
            return ProviderConfiguration(
                baseURL = baseURL, model = model, style = style,
                reasoning = ReasoningEffort.from(values["reasoning"].aiString()),
                name = values["name"].aiString(),
                models = models,
            )
        }
    }
}

/**
 * What 设置 › AI 模型 saves. It lives in the app's own preferences file, outside
 * `PlanSnapshot`, so it never travels with a backup file or reaches the widget.
 */
data class AISettings(
    /** Off until the user turns it on; while off the app looks and behaves as before. */
    val isEnabled: Boolean = false,
    /**
     * Whether a plan typed into the home page's quick field is read for a
     * time, a length or a type. Only applies while `isEnabled` is on.
     */
    val readsQuickEntry: Boolean = true,
    val activeID: String = AIProvider.deepSeek.rawValue,
    /** Entries under 供应商, in order. `null` until the list is first edited. */
    val providerList: List<String>? = null,
    val configurations: Map<String, ProviderConfiguration> = emptyMap(),
) {
    val provider: AIProvider get() = AIProvider.kind(activeID)

    /** The configuration of the entry in use. */
    val configuration: ProviderConfiguration
        get() = configurations[activeID] ?: ProviderConfiguration(provider)

    /** Swift's `configuration` setter. */
    fun withConfiguration(value: ProviderConfiguration): AISettings =
        copy(configurations = configurations + (activeID to value))

    fun configuration(entryID: String): ProviderConfiguration =
        configurations[entryID] ?: ProviderConfiguration(AIProvider.kind(entryID))

    /** The user's own name for a custom service, otherwise the provider's name. */
    fun displayName(entryID: String): String {
        val name = configurations[entryID]?.name?.trim() ?: ""
        return name.ifEmpty { AIProvider.kind(entryID).title }
    }

    /** Shown on the settings row, e.g. "DeepSeek · deepseek-flash". */
    val engineLabel: String get() = "${displayName(activeID)} · ${configuration.model}"

    fun savedModels(entryID: String): List<SavedModel> =
        configuration(entryID).savedModels(AIProvider.kind(entryID))

    /**
     * Entries under 供应商: the saved list, or before it exists the entry in
     * use and those in `keyed`, the entries that have an API key.
     */
    fun listedProviderIDs(keyed: Set<String>): List<String> {
        val list = mutableListOf<String>()
        fun add(id: String) { if (id.isNotEmpty() && id !in list) list.add(id) }
        val saved = providerList
        if (saved != null) {
            saved.forEach(::add)
        } else {
            for (provider in AIProvider.presets + AIProvider.custom) {
                if (provider.rawValue == activeID || provider.rawValue in keyed) add(provider.rawValue)
            }
        }
        add(activeID)
        return list
    }

    /**
     * Adds an entry and returns the new settings and its id. A built-in
     * provider has one entry; custom services can have several.
     */
    fun addProvider(
        kind: AIProvider,
        configuration: ProviderConfiguration,
        keyed: Set<String>,
        newID: () -> String = { "custom-" + UUID.randomUUID().toString().lowercase() },
    ): Pair<AISettings, String> {
        val list = listedProviderIDs(keyed).toMutableList()
        val id = if (kind == AIProvider.custom && kind.rawValue in list) newID() else kind.rawValue
        if (id !in list) list.add(id)
        return copy(providerList = list, configurations = configurations + (id to configuration)) to id
    }

    /** Removes an entry and its settings; null when refused, since the entry in use cannot be removed. */
    fun removeProvider(entryID: String, keyed: Set<String>): AISettings? {
        if (entryID == activeID) return null
        return copy(
            providerList = listedProviderIDs(keyed).filter { it != entryID },
            configurations = configurations - entryID,
        )
    }

    /** Makes an entry the one in use, optionally switching it to one of its models. */
    fun activate(entryID: String, model: String? = null): AISettings {
        val value = copy(activeID = entryID)
        if (model == null) return value
        return value.updateConfiguration(entryID) { it.select(model, AIProvider.kind(entryID)) }
    }

    fun updateConfiguration(entryID: String, change: (ProviderConfiguration) -> ProviderConfiguration): AISettings =
        copy(configurations = configurations + (entryID to change(configuration(entryID))))

    /** True for the one model requests currently use. */
    fun isInUse(model: String, entryID: String): Boolean =
        entryID == activeID && configuration.model.trim() == model

    /** The JSON Swift's JSONEncoder writes for the same value. */
    fun encode(): String = buildJsonObject {
        put("isEnabled", isEnabled)
        put("readsQuickEntry", readsQuickEntry)
        put("activeID", activeID)
        providerList?.let { list -> put("providerList", buildJsonArray { list.forEach { add(JsonPrimitive(it)) } }) }
        put("configurations", buildJsonObject { configurations.forEach { (id, value) -> put(id, value.toJson()) } })
    }.toString()

    companion object {
        /** Lenient like the Swift decoder: a missing or unreadable field takes its default. */
        fun decode(text: String): AISettings? {
            val values = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
            val providerList = (values["providerList"] as? JsonArray)?.let { array ->
                val parsed = array.map { it.aiString() }
                if (parsed.any { it == null }) null else parsed.filterNotNull()
            }
            val configurations = (values["configurations"] as? JsonObject)?.let { map ->
                val parsed = map.mapValues { ProviderConfiguration.fromJson(it.value) }
                if (parsed.values.any { it == null }) null else parsed.mapValues { it.value!! }
            } ?: emptyMap()
            var activeID = values["activeID"].aiString() ?: AIProvider.deepSeek.rawValue
            if (activeID.isBlank()) activeID = AIProvider.deepSeek.rawValue
            return AISettings(
                isEnabled = values["isEnabled"].aiBool() ?: false,
                readsQuickEntry = values["readsQuickEntry"].aiBool() ?: true,
                activeID = activeID,
                providerList = providerList,
                configurations = configurations,
            )
        }
    }
}

/**
 * Swift's `AIRequestError` enum. Case names are kept, so call sites read
 * `AIRequestError.emptyText`; `message` is the Swift `errorDescription`.
 */
@Suppress("ClassName")
sealed class AIRequestError(override val message: String) : Exception(message) {
    object disabled : AIRequestError("还没有开启。请到“设置 › AI 模型”打开开关。")
    object missingKey : AIRequestError("还没有填 API Key。请到“设置 › AI 模型”给正在用的供应商填写。")
    object missingModel : AIRequestError("还没有选模型。请到“设置 › AI 模型”选一个。")
    object invalidURL : AIRequestError("请使用完整的 HTTPS 地址，不要附加账号、查询参数或片段。")
    object tooMuchText : AIRequestError("内容太长了，请缩短到 6,000 字以内再试。")
    object emptyText : AIRequestError("没有可整理的内容。")
    object malformedResponse : AIRequestError("接口返回格式不兼容。请检查接口格式、API 地址和模型名称。")
    object emptyResponse : AIRequestError("模型没有返回内容。请换一个模型，或稍后重试。")
    object incompleteResponse : AIRequestError("模型的回答被提前截断了。请重试或换一个模型。")
    /** The model answered, but not in the shape the task asked for. */
    object unreadableAnswer : AIRequestError("模型没有按要求的格式回答，这次没有生成草稿。请重试或换一个模型。")
    data class http(val status: Int) : AIRequestError(httpMessage(status))
    object network : AIRequestError("无法连接 AI 服务，请检查网络和 API 地址。")
    object timeout : AIRequestError("等待超时。请稍后重试，或选择响应更快的模型。")
    object credentialUnavailable : AIRequestError("无法访问安全存储。请解锁手机后重试，或重新保存 API Key。")
    object persistence : AIRequestError("无法保存设置，请检查设备的可用空间。")
    /** An HTTP failure with the provider's own (sanitised, shortened) message. */
    data class httpDetail(val status: Int, val detail: String) : AIRequestError(httpMessage(status) + "\n服务商原话：$detail")

    val errorDescription: String get() = message

    companion object {
        fun httpMessage(status: Int): String = when (status) {
            401 -> "API Key 无效或已过期，请检查设置。（HTTP 401）"
            402 -> "API 账户余额不足，请检查服务商账户。（HTTP 402）"
            403 -> "此账户无权使用该服务或模型。（HTTP 403）"
            404 -> "接口或模型不存在，请检查 API 地址和模型名称。（HTTP 404）"
            429 -> "请求过于频繁或额度不足，请稍后重试。（HTTP 429）"
            in 500..599 -> "AI 服务暂时不可用，请稍后重试。（HTTP $status）"
            else -> "请求被接口拒绝，请检查模型、接口格式及账户权限。（HTTP $status）"
        }
    }
}

// Small readers for decoded JSON; only real JSON strings and booleans count.
internal fun JsonElement?.aiString(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonElement?.aiBool(): Boolean? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
