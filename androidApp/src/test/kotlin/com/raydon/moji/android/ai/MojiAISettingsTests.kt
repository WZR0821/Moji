package com.raydon.moji.android.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// Ported from MojiAISettingsTests in MojiTests/MojiCoreTests.swift. JUnit makes
// a new instance per test, so each test has its own empty preferences.
class MojiAISettingsTests {
    private val defaults = AIMemoryPreferences()

    private fun messages(vararg pairs: Pair<String, String>): JsonArray = buildJsonArray {
        for ((role, content) in pairs) add(buildJsonObject { put("role", role); put("content", content) })
    }

    private fun data(json: String): ByteArray = json.toByteArray(Charsets.UTF_8)

    @Test
    fun testNothingIsReadyUntilEnabledWithAKey() {
        val keys = AIMemoryKeyStore()
        val store = AISettingsStore(defaults, keys)
        assertFalse(store.isReady)
        assertEquals(AIRequestError.disabled, assertThrows(AIRequestError::class.java) { store.credentials() })

        store.change { it.copy(isEnabled = true) }
        assertFalse("on, but no key yet", store.isReady)
        assertEquals(AIRequestError.missingKey, assertThrows(AIRequestError::class.java) { store.credentials() })

        val added = store.addProvider(AIProvider.deepSeek, ProviderConfiguration(AIProvider.deepSeek), "  sk-test-123  ")
        assertEquals("deepSeek", added.id); assertTrue(added.activated)
        assertTrue(store.isReady)
        assertTrue(store.readyFlow.value)
        val credentials = store.credentials()
        assertEquals("sk-test-123", credentials.apiKey)
        assertEquals(AIProvider.deepSeek.models.first().id, credentials.configuration.model)

        store.change { it.copy(isEnabled = false) }
        assertFalse(store.isReady)
        assertFalse(store.readyFlow.value)
    }

    @Test
    fun testKeysAreNotWrittenIntoSettingsAndSurviveAReload() {
        val keys = AIMemoryKeyStore()
        val store = AISettingsStore(defaults, keys)
        store.addProvider(AIProvider.deepSeek, ProviderConfiguration(AIProvider.deepSeek), "sk-secret-value")
        store.change { it.copy(isEnabled = true) }
        val saved = defaults.getString(AISettingsStore.defaultsKey)
        assertNotNull(saved)
        assertFalse(saved!!.contains("sk-secret-value"))

        val reloaded = AISettingsStore(defaults, keys)
        assertTrue(reloaded.isReady)
        assertEquals(store.settings.value, reloaded.settings.value)
        assertFalse("the same settings on a device without the key", AISettingsStore(defaults, AIMemoryKeyStore()).isReady)
    }

    @Test
    fun testAddingASecondProviderDoesNotSwitchAndTheOneInUseCannotBeRemoved() {
        val store = AISettingsStore(defaults, AIMemoryKeyStore())
        store.addProvider(AIProvider.deepSeek, ProviderConfiguration(AIProvider.deepSeek), "k1")
        val second = store.addProvider(AIProvider.claude, ProviderConfiguration(AIProvider.claude), "k2")
        assertFalse(second.activated)
        assertEquals("deepSeek", store.settings.value.activeID)
        assertEquals(listOf("deepSeek", "claude"), store.listedProviderIDs)

        store.removeProvider("deepSeek")
        assertEquals(listOf("deepSeek", "claude"), store.listedProviderIDs)
        store.removeProvider("claude")
        assertEquals(listOf("deepSeek"), store.listedProviderIDs)
        assertEquals("", store.apiKey("claude"))
    }

    @Test
    fun testAnAddressThatIsNotHTTPSIsRefusedAndNothingIsSaved() {
        val keys = AIMemoryKeyStore()
        val store = AISettingsStore(defaults, keys)
        val configuration = ProviderConfiguration(AIProvider.custom).copy(baseURL = "http://example.com/v1", model = "some-model")
        val error = assertThrows(AIRequestError::class.java) { store.addProvider(AIProvider.custom, configuration, "k") }
        assertEquals(AIRequestError.invalidURL, error)
        assertEquals("", store.apiKey("custom"))
        assertNull(defaults.getString(AISettingsStore.defaultsKey))
    }

    @Test
    fun testEndpointAcceptsABaseAddressOrAFullOne() {
        var configuration = ProviderConfiguration(AIProvider.custom).copy(style = APIStyle.chatCompletions, baseURL = "https://api.example.com/v1/")
        assertEquals("https://api.example.com/v1/chat/completions", AIClient.endpoint(configuration))
        configuration = configuration.copy(baseURL = "https://api.example.com/v1/chat/completions")
        assertEquals("https://api.example.com/v1/chat/completions", AIClient.endpoint(configuration))
        assertEquals("https://api.example.com/v1/models", AIClient.endpoint(configuration, models = true))
        configuration = configuration.copy(style = APIStyle.anthropicMessages)
        assertEquals("https://api.example.com/v1/messages", AIClient.endpoint(configuration))
        for (bad in listOf("http://api.example.com/v1", "https://user:pass@api.example.com/v1", "https://api.example.com/v1?key=1", "api.example.com", "")) {
            val error = assertThrows(bad, AIRequestError::class.java) { AIClient.endpoint(configuration.copy(baseURL = bad)) }
            assertEquals(bad, AIRequestError.invalidURL, error)
        }
    }

    @Test
    fun testRequestBodyCarriesOnlyTheInstructionsAndTheText() {
        var configuration = ProviderConfiguration(AIProvider.custom).copy(model = "m", style = APIStyle.chatCompletions)
        val chat = AIClient.body("指令", "原文", AIProvider.custom, configuration, "m")
        assertEquals(setOf("model", "messages"), chat.keys)
        assertEquals(messages("system" to "指令", "user" to "原文"), chat["messages"])

        configuration = configuration.copy(style = APIStyle.anthropicMessages)
        val anthropic = AIClient.body("指令", "原文", AIProvider.custom, configuration, "m")
        assertEquals(JsonPrimitive("指令"), anthropic["system"])
        assertEquals(messages("user" to "原文"), anthropic["messages"])
        assertNotNull(anthropic["max_tokens"])

        configuration = configuration.copy(style = APIStyle.responses)
        val responses = AIClient.body("指令", "原文", AIProvider.custom, configuration, "m")
        assertEquals(JsonPrimitive("指令"), responses["instructions"]); assertEquals(JsonPrimitive("原文"), responses["input"])
        assertEquals(JsonPrimitive(false), responses["store"])
    }

    @Test
    fun testAnswersAreReadFromAllThreeInterfaceFormats() {
        assertEquals("好", AIClient.parseText(data("""{"choices":[{"message":{"content":" 好 "},"finish_reason":"stop"}]}"""), APIStyle.chatCompletions))
        assertEquals("好", AIClient.parseText(
            data("""{"content":[{"type":"thinking","thinking":"…"},{"type":"text","text":"好"}],"stop_reason":"end_turn"}"""),
            APIStyle.anthropicMessages,
        ))
        assertEquals("好", AIClient.parseText(
            data("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"好"}]}]}"""),
            APIStyle.responses,
        ))
        val cut = assertThrows(AIRequestError::class.java) {
            AIClient.parseText(data("""{"choices":[{"message":{"content":"半句"},"finish_reason":"length"}]}"""), APIStyle.chatCompletions)
        }
        assertEquals(AIRequestError.incompleteResponse, cut)
        assertThrows(AIRequestError::class.java) {
            AIClient.parseText(data("""{"choices":[{"message":{"content":""},"finish_reason":"stop"}]}"""), APIStyle.chatCompletions)
        }
        assertThrows(AIRequestError::class.java) { AIClient.parseText(data("not json"), APIStyle.responses) }
    }

    @Test
    fun testStubbedProviderAnswersEachInterfaceFormat() {
        val body = buildJsonObject {
            put("model", "m")
            put("messages", messages("system" to AIDrafting.checklistInstructions, "user" to "备忘"))
        }.toString().toByteArray(Charsets.UTF_8)
        for ((path, style) in listOf("/v1/chat/completions" to APIStyle.chatCompletions, "/v1/messages" to APIStyle.anthropicMessages, "/v1/responses" to APIStyle.responses)) {
            val (status, answer) = AIQAStubProtocol.response(path, body)
            assertEquals(200, status)
            assertEquals(path, 4, AIDrafting.checklist(AIClient.parseText(answer, style)).size)
        }
    }
}
