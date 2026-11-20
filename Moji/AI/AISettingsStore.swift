import Foundation

/// The saved AI settings and which provider entries have a key. Views observe
/// the shared instance; requests read their credentials from it.
@MainActor
final class AISettingsStore: ObservableObject {
    static let shared: AISettingsStore = {
#if DEBUG
        if let fixture = AIQAFixture.store() { return fixture }
#endif
        return AISettingsStore()
    }()

    static let defaultsKey = "moji.ai.settings.v1"

    @Published private(set) var settings: AISettings
    /// Entries that have an API key saved.
    @Published private(set) var keyedIDs: Set<String> = []

    private let defaults: UserDefaults
    private let keys: AIKeyStoring

    init(defaults: UserDefaults = .standard, keys: AIKeyStoring = AIKeychainStore()) {
        self.defaults = defaults
        self.keys = keys
        if let data = defaults.data(forKey: Self.defaultsKey),
           let saved = try? JSONDecoder().decode(AISettings.self, from: data) {
            settings = saved
        } else {
            settings = AISettings()
        }
        refreshKeys()
    }

    /// On, with a key and a model: the only state in which anything is sent.
    var isReady: Bool {
        settings.isEnabled && keyedIDs.contains(settings.activeID)
            && !settings.configuration.model.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var listedProviderIDs: [String] { settings.listedProviderIDs(keyed: keyedIDs) }

    func apiKey(for entryID: String) -> String {
        (try? keys.read(account: entryID)) ?? ""
    }

    func refreshKeys() {
        var found = Set<String>()
        for id in settings.listedProviderIDs(keyed: []) + AIProvider.presets.map(\.rawValue) + Array(settings.configurations.keys) {
            if !apiKey(for: id).isEmpty { found.insert(id) }
        }
        if found != keyedIDs { keyedIDs = found }
    }

    /// Saves settings, and the keys given for some entries (an empty key deletes it).
    func save(_ value: AISettings, keys newKeys: [String: String] = [:]) throws {
        for (id, _) in newKeys {
            _ = try AIClient.endpoint(configuration: value.configuration(for: id))
        }
        for (id, key) in newKeys { try keys.write(key, account: id) }
        guard let data = try? JSONEncoder().encode(value) else { throw AIRequestError.persistence }
        defaults.set(data, forKey: Self.defaultsKey)
        settings = value
        refreshKeys()
    }

    func change(keys newKeys: [String: String] = [:], _ change: (inout AISettings) -> Void) throws {
        var value = settings
        change(&value)
        try save(value, keys: newKeys)
    }

    /// Adds a provider with its key. It becomes the one in use only when the
    /// current one has no key, so adding a second provider changes nothing by itself.
    @discardableResult
    func addProvider(_ kind: AIProvider, configuration: ProviderConfiguration,
                     key: String) throws -> (id: String, activated: Bool) {
        var value = settings
        let id = value.addProvider(kind, configuration: configuration, keyed: keyedIDs)
        let activated = !keyedIDs.contains(value.activeID) || value.activeID == id
        if activated { value.activeID = id }
        try save(value, keys: [id: key])
        return (id, activated)
    }

    func removeProvider(_ entryID: String) throws {
        var value = settings
        guard value.removeProvider(entryID, keyed: keyedIDs) else { return }
        try keys.write("", account: entryID)
        try save(value)
    }

    /// What a request needs. Throws the reason nothing can be sent.
    func credentials() throws -> (entryID: String, configuration: ProviderConfiguration, apiKey: String) {
        guard settings.isEnabled else { throw AIRequestError.disabled }
        let configuration = settings.configuration
        guard !configuration.model.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw AIRequestError.missingModel
        }
        let key = apiKey(for: settings.activeID)
        guard !key.isEmpty else { throw AIRequestError.missingKey }
        return (settings.activeID, configuration, key)
    }
}
