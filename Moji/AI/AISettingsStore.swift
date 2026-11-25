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

    /// Entry points are on screen: ready, or off but not hidden by the user.
    /// Tapping one while not ready opens the introduction instead of sending anything.
    var showsEntries: Bool { isReady || settings.showsEntriesWhenOff }

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

    // MARK: Remembered beside the settings (not in the snapshot or backups)

    static let shareIntroKey = "moji.ai.shareIntroShown.v1"
    static let nextWeekOfferKey = "moji.ai.nextWeekOffer.v1"
    static let rememberedLimit = 200

    /// The introduction opens by itself at most once for each shared item
    /// while nothing is set up; the item stays in the inbox for later.
    func shouldIntroduceShare(_ itemID: String) -> Bool {
        !(defaults.stringArray(forKey: Self.shareIntroKey) ?? []).contains(itemID)
    }

    func markShareIntroduced(_ itemID: String) {
        remember(itemID, forKey: Self.shareIntroKey)
    }

    /// The 下周 offer under 周复盘, closed or taken up for that week ("2026-11-23").
    func isNextWeekOfferHandled(_ weekKey: String) -> Bool {
        (defaults.stringArray(forKey: Self.nextWeekOfferKey) ?? []).contains(weekKey)
    }

    func markNextWeekOfferHandled(_ weekKey: String) {
        objectWillChange.send()
        remember(weekKey, forKey: Self.nextWeekOfferKey)
    }

    private func remember(_ value: String, forKey key: String) {
        var list = defaults.stringArray(forKey: key) ?? []
        guard !list.contains(value) else { return }
        list.append(value)
        if list.count > Self.rememberedLimit { list.removeFirst(list.count - Self.rememberedLimit) }
        defaults.set(list, forKey: key)
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
