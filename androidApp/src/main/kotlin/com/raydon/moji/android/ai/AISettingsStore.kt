package com.raydon.moji.android.ai

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The one string the settings store keeps; lets JVM tests run without Android. */
interface AIPreferences {
    fun getString(key: String): String?
    /** False when the value could not be written. */
    fun putString(key: String, value: String): Boolean
    /** Forgets the value; an empty string reads as nothing saved. */
    fun remove(key: String): Boolean = putString(key, "")
}

/**
 * A private preferences file of the AI layer's own, apart from `moji.settings`,
 * so these settings are never part of the app's backup archive.
 */
class AISharedPreferences(context: Context, name: String = "moji.ai") : AIPreferences {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String): Boolean = prefs.edit().putString(key, value).commit()
    override fun remove(key: String): Boolean = prefs.edit().remove(key).commit()
}

class AIMemoryPreferences : AIPreferences {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String): String? = synchronized(values) { values[key] }
    override fun putString(key: String, value: String): Boolean {
        synchronized(values) { values[key] = value }
        return true
    }
    override fun remove(key: String): Boolean {
        synchronized(values) { values.remove(key) }
        return true
    }
}

/** Swift's `(id:activated:)` result of adding a provider. */
data class AddedProvider(val id: String, val activated: Boolean)

/** What a request needs: the entry in use, its configuration and its key. */
data class AICredentials(val entryID: String, val configuration: ProviderConfiguration, val apiKey: String)

/**
 * The saved AI settings and which provider entries have a key. Screens collect
 * the flows of the shared instance; requests read their credentials from it.
 */
class AISettingsStore(private val defaults: AIPreferences, private val keys: AIKeyStoring) {
    private val settingsState = MutableStateFlow(
        defaults.getString(defaultsKey)?.let { AISettings.decode(it) } ?: AISettings()
    )
    private val keyedState = MutableStateFlow<Set<String>>(emptySet())
    private val readyState = MutableStateFlow(false)
    private val showsEntriesState = MutableStateFlow(false)

    val settings: StateFlow<AISettings> = settingsState.asStateFlow()

    /** Entries that have an API key saved. */
    val keyedIDs: StateFlow<Set<String>> = keyedState.asStateFlow()

    /** `isReady` as a flow, for screens that show or hide AI entry points. */
    val readyFlow: StateFlow<Boolean> = readyState.asStateFlow()

    /** `showsEntries` as a flow: ready, or not ready with the entries kept on. */
    val showsEntriesFlow: StateFlow<Boolean> = showsEntriesState.asStateFlow()

    init {
        refreshKeys()
    }

    /** On, with a key and a model: the only state in which anything is sent. */
    val isReady: Boolean get() = ready(settingsState.value, keyedState.value)

    /**
     * Whether entry points are shown: always when ready, and while not ready
     * unless the user hid them. Tapping one while not ready opens the intro.
     */
    val showsEntries: Boolean get() = isReady || settingsState.value.showsEntriesWhenOff

    val listedProviderIDs: List<String> get() = settingsState.value.listedProviderIDs(keyedState.value)

    fun apiKey(entryID: String): String = try { keys.read(entryID) } catch (error: Exception) { "" }

    fun refreshKeys() {
        val value = settingsState.value
        val found = mutableSetOf<String>()
        for (id in value.listedProviderIDs(emptySet()) + AIProvider.presets.map { it.rawValue } + value.configurations.keys) {
            if (apiKey(id).isNotEmpty()) found.add(id)
        }
        keyedState.value = found
        readyState.value = ready(value, found)
        showsEntriesState.value = readyState.value || value.showsEntriesWhenOff
    }

    /** Saves settings, and the keys given for some entries (an empty key deletes it). Throws [AIRequestError]. */
    fun save(value: AISettings, keys: Map<String, String> = emptyMap()) {
        for (id in keys.keys) AIClient.endpoint(value.configuration(id))
        for ((id, key) in keys) this.keys.write(key, id)
        val text = try { value.encode() } catch (error: Exception) { throw AIRequestError.persistence }
        if (!defaults.putString(defaultsKey, text)) throw AIRequestError.persistence
        settingsState.value = value
        refreshKeys()
        // 本周来信's Sunday notification follows the switches and the key.
        AIWeeklyLetterState.reschedule()
    }

    /** Swift's `change(keys:_:)`: the block returns the changed copy. */
    fun change(keys: Map<String, String> = emptyMap(), change: (AISettings) -> AISettings) {
        save(change(settingsState.value), keys)
    }

    /**
     * Adds a provider with its key. It becomes the one in use only when the
     * current one has no key, so adding a second provider changes nothing by itself.
     */
    fun addProvider(kind: AIProvider, configuration: ProviderConfiguration, key: String): AddedProvider {
        val keyed = keyedState.value
        var (value, id) = settingsState.value.addProvider(kind, configuration, keyed)
        val activated = value.activeID !in keyed || value.activeID == id
        if (activated) value = value.copy(activeID = id)
        save(value, mapOf(id to key))
        return AddedProvider(id, activated)
    }

    fun removeProvider(entryID: String) {
        val value = settingsState.value.removeProvider(entryID, keyedState.value) ?: return
        keys.write("", entryID)
        save(value)
    }

    /** What a request needs. Throws the reason nothing can be sent. */
    fun credentials(): AICredentials {
        val value = settingsState.value
        if (!value.isEnabled) throw AIRequestError.disabled
        val configuration = value.configuration
        if (configuration.model.isBlank()) throw AIRequestError.missingModel
        val key = apiKey(value.activeID)
        if (key.isEmpty()) throw AIRequestError.missingKey
        return AICredentials(value.activeID, configuration, key)
    }

    private fun ready(value: AISettings, keyed: Set<String>): Boolean =
        value.isEnabled && value.activeID in keyed && value.configuration.model.isNotBlank()

    companion object {
        const val defaultsKey = "moji.ai.settings.v1"

        @Volatile private var installed: AISettingsStore? = null
        @Volatile private var fallback: AISettingsStore? = null

        /**
         * The store screens and requests use. While the debug QA fixture is on
         * it is the fixture's in-memory store, as `AISettingsStore.shared` is on iOS.
         */
        val shared: AISettingsStore
            get() {
                AIQAFixture.store()?.let { return it }
                installed?.let { return it }
                // Not installed yet (or in a JVM test): settings that save nowhere.
                return synchronized(this) {
                    fallback ?: AISettingsStore(AIMemoryPreferences(), AIMemoryKeyStore()).also { fallback = it }
                }
            }

        /** Call once from `MojiApplication.onCreate`. */
        fun install(context: Context) {
            if (installed != null) return
            synchronized(this) {
                if (installed != null) return
                val app = context.applicationContext
                installed = AISettingsStore(AISharedPreferences(app), AIKeystoreStore(app))
                AIProactiveStore.install(AISharedPreferences(app))
                AIWriteLog.install(AISharedPreferences(app))
                AIEveningWrapState.install(AISharedPreferences(app))
                AIWeeklyLetterState.install(AISharedPreferences(app))
                AIWeeklyLetterState.rescheduler = { com.raydon.moji.android.platform.AIWeeklyLetterNotifier.reschedule(app) }
                AIAssistant.installDefaultCategory(app)
            }
        }
    }
}
