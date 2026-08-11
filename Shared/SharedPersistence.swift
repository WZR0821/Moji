import Darwin
import Foundation

enum SharedPersistenceError: LocalizedError {
    case invalidBackup
    case unsupportedBackup
    case unableToEncode

    var errorDescription: String? {
        switch self {
        case .invalidBackup:
            return "这个文件不是有效的 Moji 备份。"
        case .unsupportedBackup:
            return "这个备份来自更高版本的 App，请先更新 App 后再导入。"
        case .unableToEncode:
            return "暂时无法生成备份文件，请稍后再试。"
        }
    }
}

private struct PlanBackupArchive: Codable {
    let formatVersion: Int
    let appIdentifier: String
    let appVersion: String
    let exportedAt: Date
    let snapshot: PlanSnapshot
    let preferences: PlanPreferencesArchive?
}

private struct PlanPreferencesArchive: Codable {
    let focusMinutes: Int
    let shortBreakMinutes: Int
    let longBreakMinutes: Int
    let longBreakInterval: Int
    let longBreakEnabled: Bool?
    let pomodoroPhase: String
    let pomodoroRunning: Bool
    let pomodoroRemaining: Int
    let pomodoroTarget: Double
    let pomodoroSegmentStart: Double
    let pomodoroAccumulated: Int
    let pomodoroCompleted: Int
    let pomodoroTitle: String
    let pomodoroCategory: String
    let linkedPlanID: String
    let pomodoroPhaseDuration: Int?
    let pomodoroPhaseDurationIsPlanOwned: Bool?
    let defaultCategory: String?
    let customCategories: String?
    let quickPlanPresets: String?
    let defaultScheduleKind: String?
    let defaultPlannedDurationEnabled: Bool?
    let defaultPlannedMinutes: Int?
    let allDayReminderHour: Int?
    let planNotificationsEnabled: Bool?
    let notificationSoundEnabled: Bool?
    let hapticsEnabled: Bool?
    let liveActivitiesEnabled: Bool?
    let autoStartBreaks: Bool?
    let autoStartFocus: Bool?
    let keepScreenAwake: Bool?
    let appearanceMode: String?
    let inkMotionLevel: String?
    let paperTextureEnabled: Bool?
    let backupRetentionDays: Int?
    /// Only the folder's name, as a hint. The bookmark itself is bound to the
    /// install that made it, so the restoring install cannot reuse it — but
    /// telling the user which folder to pick again turns a blind search into
    /// one recognisable name.
    let backupFolderName: String?

    init(defaults: UserDefaults) {
        focusMinutes = Self.value(for: PomodoroStorageKeys.focusMinutes, fallback: 25, defaults: defaults)
        shortBreakMinutes = Self.value(
            for: PomodoroStorageKeys.shortBreakMinutes,
            fallback: 5,
            defaults: defaults
        )
        longBreakMinutes = Self.value(
            for: PomodoroStorageKeys.longBreakMinutes,
            fallback: 15,
            defaults: defaults
        )
        longBreakInterval = Self.value(
            for: PomodoroStorageKeys.longBreakInterval,
            fallback: 4,
            defaults: defaults
        )
        longBreakEnabled = PlanSettingsKeys.bool(
            PomodoroStorageKeys.longBreakEnabled,
            fallback: true,
            defaults: defaults
        )
        pomodoroPhase = defaults.string(forKey: PomodoroStorageKeys.phase)
            ?? PomodoroStorageKeys.focusPhase
        pomodoroRunning = defaults.bool(forKey: PomodoroStorageKeys.running)
        pomodoroRemaining = Self.value(
            for: PomodoroStorageKeys.remaining,
            fallback: focusMinutes * 60,
            defaults: defaults
        )
        pomodoroTarget = defaults.double(forKey: PomodoroStorageKeys.target)
        pomodoroSegmentStart = defaults.double(forKey: PomodoroStorageKeys.segmentStart)
        pomodoroAccumulated = defaults.integer(forKey: PomodoroStorageKeys.accumulated)
        pomodoroCompleted = defaults.integer(forKey: PomodoroStorageKeys.completed)
        pomodoroTitle = defaults.string(forKey: PomodoroStorageKeys.title) ?? "番茄专注"
        pomodoroCategory = defaults.string(forKey: PomodoroStorageKeys.category) ?? "study"
        linkedPlanID = defaults.string(forKey: PomodoroStorageKeys.linkedPlanID) ?? ""
        pomodoroPhaseDuration = Self.value(
            for: PomodoroStorageKeys.phaseDuration,
            fallback: focusMinutes * 60,
            defaults: defaults
        )
        pomodoroPhaseDurationIsPlanOwned = PlanSettingsKeys.bool(
            PomodoroStorageKeys.phaseDurationIsPlanOwned,
            fallback: false,
            defaults: defaults
        )
        defaultCategory = defaults.string(forKey: PlanSettingsKeys.defaultCategory)
            ?? RecordCategory.study.rawValue
        customCategories = defaults.string(forKey: PlanSettingsKeys.customCategories) ?? "[]"
        quickPlanPresets = defaults.string(forKey: PlanSettingsKeys.quickPlanPresets)
            ?? QuickPlanPresetStore.defaultJSON
        defaultScheduleKind = defaults.string(forKey: PlanSettingsKeys.defaultScheduleKind)
            ?? ScheduleKind.allDay.rawValue
        defaultPlannedDurationEnabled = PlanSettingsKeys.bool(
            PlanSettingsKeys.defaultPlannedDurationEnabled,
            fallback: false,
            defaults: defaults
        )
        defaultPlannedMinutes = PlanSettingsKeys.integer(
            PlanSettingsKeys.defaultPlannedMinutes,
            fallback: 25,
            defaults: defaults
        )
        allDayReminderHour = PlanSettingsKeys.integer(
            PlanSettingsKeys.allDayReminderHour,
            fallback: 9,
            defaults: defaults
        )
        planNotificationsEnabled = PlanSettingsKeys.bool(
            PlanSettingsKeys.planNotificationsEnabled,
            fallback: true,
            defaults: defaults
        )
        notificationSoundEnabled = PlanSettingsKeys.bool(
            PlanSettingsKeys.notificationSoundEnabled,
            fallback: true,
            defaults: defaults
        )
        hapticsEnabled = PlanSettingsKeys.bool(
            PlanSettingsKeys.hapticsEnabled,
            fallback: true,
            defaults: defaults
        )
        liveActivitiesEnabled = PlanSettingsKeys.bool(
            PlanSettingsKeys.liveActivitiesEnabled,
            fallback: true,
            defaults: defaults
        )
        autoStartBreaks = PlanSettingsKeys.bool(
            PlanSettingsKeys.autoStartBreaks,
            fallback: false,
            defaults: defaults
        )
        autoStartFocus = PlanSettingsKeys.bool(
            PlanSettingsKeys.autoStartFocus,
            fallback: false,
            defaults: defaults
        )
        keepScreenAwake = PlanSettingsKeys.bool(
            PlanSettingsKeys.keepScreenAwake,
            fallback: false,
            defaults: defaults
        )
        appearanceMode = defaults.string(forKey: PlanSettingsKeys.appearanceMode)
            ?? AppAppearanceMode.system.rawValue
        inkMotionLevel = defaults.string(forKey: PlanSettingsKeys.inkMotionLevel)
            ?? InkMotionLevel.full.rawValue
        paperTextureEnabled = PlanSettingsKeys.bool(
            PlanSettingsKeys.paperTextureEnabled,
            fallback: true,
            defaults: defaults
        )
        backupRetentionDays = PlanSettingsKeys.integer(
            "minuteplan.backup.retentionDays",
            fallback: 180,
            defaults: defaults
        )
        backupFolderName = defaults.string(forKey: "minuteplan.backup.folderName")
    }

    func restore(to defaults: UserDefaults) {
        defaults.set(focusMinutes, forKey: PomodoroStorageKeys.focusMinutes)
        defaults.set(shortBreakMinutes, forKey: PomodoroStorageKeys.shortBreakMinutes)
        defaults.set(longBreakMinutes, forKey: PomodoroStorageKeys.longBreakMinutes)
        defaults.set(longBreakInterval, forKey: PomodoroStorageKeys.longBreakInterval)
        Self.restore(longBreakEnabled, key: PomodoroStorageKeys.longBreakEnabled, to: defaults)
        defaults.set(pomodoroPhase, forKey: PomodoroStorageKeys.phase)
        defaults.set(pomodoroRunning, forKey: PomodoroStorageKeys.running)
        defaults.set(pomodoroRemaining, forKey: PomodoroStorageKeys.remaining)
        defaults.set(pomodoroTarget, forKey: PomodoroStorageKeys.target)
        defaults.set(pomodoroSegmentStart, forKey: PomodoroStorageKeys.segmentStart)
        defaults.set(pomodoroAccumulated, forKey: PomodoroStorageKeys.accumulated)
        defaults.set(pomodoroCompleted, forKey: PomodoroStorageKeys.completed)
        defaults.set(pomodoroTitle, forKey: PomodoroStorageKeys.title)
        defaults.set(pomodoroCategory, forKey: PomodoroStorageKeys.category)
        defaults.set(linkedPlanID, forKey: PomodoroStorageKeys.linkedPlanID)
        Self.restore(pomodoroPhaseDuration, key: PomodoroStorageKeys.phaseDuration, to: defaults)
        Self.restore(
            pomodoroPhaseDurationIsPlanOwned,
            key: PomodoroStorageKeys.phaseDurationIsPlanOwned,
            to: defaults
        )
        Self.restore(defaultCategory, key: PlanSettingsKeys.defaultCategory, to: defaults)
        Self.restore(customCategories, key: PlanSettingsKeys.customCategories, to: defaults)
        Self.restore(quickPlanPresets, key: PlanSettingsKeys.quickPlanPresets, to: defaults)
        Self.restore(defaultScheduleKind, key: PlanSettingsKeys.defaultScheduleKind, to: defaults)
        Self.restore(
            defaultPlannedDurationEnabled,
            key: PlanSettingsKeys.defaultPlannedDurationEnabled,
            to: defaults
        )
        Self.restore(defaultPlannedMinutes, key: PlanSettingsKeys.defaultPlannedMinutes, to: defaults)
        Self.restore(allDayReminderHour, key: PlanSettingsKeys.allDayReminderHour, to: defaults)
        Self.restore(
            planNotificationsEnabled,
            key: PlanSettingsKeys.planNotificationsEnabled,
            to: defaults
        )
        Self.restore(
            notificationSoundEnabled,
            key: PlanSettingsKeys.notificationSoundEnabled,
            to: defaults
        )
        Self.restore(hapticsEnabled, key: PlanSettingsKeys.hapticsEnabled, to: defaults)
        Self.restore(
            liveActivitiesEnabled,
            key: PlanSettingsKeys.liveActivitiesEnabled,
            to: defaults
        )
        Self.restore(autoStartBreaks, key: PlanSettingsKeys.autoStartBreaks, to: defaults)
        Self.restore(autoStartFocus, key: PlanSettingsKeys.autoStartFocus, to: defaults)
        Self.restore(keepScreenAwake, key: PlanSettingsKeys.keepScreenAwake, to: defaults)
        Self.restore(appearanceMode, key: PlanSettingsKeys.appearanceMode, to: defaults)
        Self.restore(inkMotionLevel, key: PlanSettingsKeys.inkMotionLevel, to: defaults)
        Self.restore(
            paperTextureEnabled,
            key: PlanSettingsKeys.paperTextureEnabled,
            to: defaults
        )
        // The backup folder bookmark is deliberately not restored: it is only
        // valid for the install that created it. The name is kept as a hint so
        // the restore screen can say which folder to pick.
        Self.restore(backupRetentionDays, key: "minuteplan.backup.retentionDays", to: defaults)
        Self.restore(backupFolderName, key: "minuteplan.backup.expectedFolderName", to: defaults)
    }

    private static func value(
        for key: String,
        fallback: Int,
        defaults: UserDefaults
    ) -> Int {
        defaults.object(forKey: key) == nil ? fallback : defaults.integer(forKey: key)
    }

    private static func restore<T>(_ value: T?, key: String, to defaults: UserDefaults) {
        guard let value else { return }
        defaults.set(value, forKey: key)
    }
}

enum SharedPersistence {
    // These identifiers intentionally retain their pre-Moji values. They are
    // persistent upgrade identities, not product-facing names: changing them
    // would disconnect existing installs from their App Group data.
    static let appGroupIdentifier = "group.com.raydon.minuteplan"
    static let snapshotKey = "minuteplan.snapshot.v1"
    static let sharedDefaults = UserDefaults(suiteName: appGroupIdentifier) ?? .standard

    private static let backupFormatVersion = 1
    private static let preferenceMigrationKey = "minuteplan.preferences.migratedToAppGroup.v1"
    private static let lock = NSLock()
    private static let fileManager = FileManager.default

    private static let sharedPreferenceKeys = [
        "minuteplan.settings.focusMinutes",
        "minuteplan.settings.shortBreakMinutes",
        "minuteplan.settings.longBreakMinutes",
        "minuteplan.settings.longBreakInterval",
        "minuteplan.settings.longBreakEnabled",
        "minuteplan.pomodoro.phase",
        "minuteplan.pomodoro.running",
        "minuteplan.pomodoro.remaining",
        "minuteplan.pomodoro.target",
        "minuteplan.pomodoro.segmentStart",
        "minuteplan.pomodoro.accumulated",
        "minuteplan.pomodoro.completed",
        "minuteplan.pomodoro.title",
        "minuteplan.pomodoro.category",
        "minuteplan.pomodoro.linkedPlanID",
        "minuteplan.pomodoro.liveActivityVisible",
        "minuteplan.pomodoro.phaseDuration",
        "minuteplan.pomodoro.phaseDurationIsPlanOwned",
        "minuteplan.settings.defaultCategory",
        "minuteplan.settings.customCategories",
        "minuteplan.settings.quickPlanPresets",
        "minuteplan.settings.defaultScheduleKind",
        "minuteplan.settings.defaultPlannedDurationEnabled",
        "minuteplan.settings.defaultPlannedMinutes",
        "minuteplan.settings.allDayReminderHour",
        "minuteplan.settings.planNotificationsEnabled",
        "minuteplan.settings.notificationSoundEnabled",
        "minuteplan.settings.hapticsEnabled",
        "minuteplan.settings.liveActivitiesEnabled",
        "minuteplan.settings.autoStartBreaks",
        "minuteplan.settings.autoStartFocus",
        "minuteplan.settings.keepScreenAwake",
        "minuteplan.settings.appearanceMode",
        "minuteplan.settings.inkMotionLevel",
        "minuteplan.settings.paperTextureEnabled"
    ]

    static func load() -> PlanSnapshot {
        withPersistenceLock {
            migrateLegacyPreferencesUnlocked()
            return loadUnlocked()
        }
    }

    @discardableResult
    static func mutate(_ mutation: (inout PlanSnapshot) -> Void) -> PlanSnapshot {
        let result: (snapshot: PlanSnapshot, didSave: Bool) = withPersistenceLock {
            migrateLegacyPreferencesUnlocked()

            var snapshot = loadUnlocked()
            // A downgraded build may decode the fields it knows, but must never
            // rewrite a newer snapshot and erase fields introduced later.
            guard snapshot.schemaVersion <= PlanSnapshot.currentSchemaVersion else {
                return (snapshot, false)
            }
            mutation(&snapshot)
            snapshot.normalize()
            saveUnlocked(snapshot)
            return (snapshot, true)
        }
        return result.snapshot
    }

    static func replace(with snapshot: PlanSnapshot) {
        _ = withPersistenceLock {
            guard snapshot.schemaVersion <= PlanSnapshot.currentSchemaVersion else { return false }
            var normalized = snapshot
            normalized.normalize()
            saveUnlocked(normalized)
            return true
        }
    }

    static func exportBackup() throws -> Data {
        try withPersistenceLock {
            let archive = PlanBackupArchive(
                formatVersion: backupFormatVersion,
                appIdentifier: "com.raydon.moji",
                appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "unknown",
                exportedAt: Date(),
                snapshot: loadUnlocked(),
                preferences: PlanPreferencesArchive(defaults: sharedDefaults)
            )
            let backupEncoder = encoder
            backupEncoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
            do {
                return try backupEncoder.encode(archive)
            } catch {
                throw SharedPersistenceError.unableToEncode
            }
        }
    }

    @discardableResult
    static func importBackup(_ data: Data) throws -> PlanSnapshot {
        let imported = try withPersistenceLock {
            var imported: PlanSnapshot
            var preferences: PlanPreferencesArchive?
            if let archive = try? decoder.decode(PlanBackupArchive.self, from: data) {
                guard archive.formatVersion <= backupFormatVersion else {
                    throw SharedPersistenceError.unsupportedBackup
                }
                imported = archive.snapshot
                preferences = archive.preferences
            } else if let legacySnapshot = try? decoder.decode(PlanSnapshot.self, from: data) {
                imported = legacySnapshot
            } else {
                throw SharedPersistenceError.invalidBackup
            }

            // Validate everything before changing either settings or user data.
            guard imported.schemaVersion <= PlanSnapshot.currentSchemaVersion else {
                throw SharedPersistenceError.unsupportedBackup
            }
            imported.normalize()
            preferences?.restore(to: sharedDefaults)
            saveUnlocked(imported)
            return imported
        }
        return imported
    }

    private static func loadUnlocked() -> PlanSnapshot {
        // App Group defaults are cached per process. Synchronize while holding
        // the cross-process file lock so widget and app see the latest commit.
        sharedDefaults.synchronize()
        let defaultsData = sharedDefaults.data(forKey: snapshotKey)
        let primaryData = primarySnapshotURL.flatMap { try? Data(contentsOf: $0) }
        let backupData = fallbackSnapshotURL.flatMap { try? Data(contentsOf: $0) }
        let legacyData = UserDefaults.standard.data(forKey: snapshotKey)

        let candidates: [(Data?, Bool)] = [
            (defaultsData, true),
            (primaryData, false),
            (backupData, false),
            (legacyData, false)
        ]

        var decoded: [(snapshot: PlanSnapshot, isPrimaryDefaults: Bool)] = []
        for (candidate, isPrimaryDefaults) in candidates {
            guard let candidate else { continue }
            guard let snapshot = try? decoder.decode(PlanSnapshot.self, from: candidate) else {
                if isPrimaryDefaults {
                    sharedDefaults.set(candidate, forKey: "\(snapshotKey).corrupt-backup")
                }
                continue
            }
            decoded.append((snapshot, isPrimaryDefaults))
        }

        guard let selected = decoded.max(by: {
            $0.snapshot.lastUpdated < $1.snapshot.lastUpdated
        }) else {
            return .empty
        }

        var snapshot = selected.snapshot
        guard snapshot.schemaVersion <= PlanSnapshot.currentSchemaVersion else {
            return snapshot
        }
        if snapshot.schemaVersion < PlanSnapshot.currentSchemaVersion
            || !selected.isPrimaryDefaults
        {
            snapshot.normalize()
            saveUnlocked(snapshot)
        }
        return snapshot
    }

    private static func saveUnlocked(_ snapshot: PlanSnapshot) {
        var current = snapshot
        current.schemaVersion = PlanSnapshot.currentSchemaVersion
        guard let data = try? encoder.encode(current) else { return }
        sharedDefaults.set(data, forKey: snapshotKey)
        sharedDefaults.synchronize()

        guard let primaryURL = primarySnapshotURL else { return }
        do {
            try fileManager.createDirectory(
                at: primaryURL.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            if
                fileManager.fileExists(atPath: primaryURL.path),
                let existingData = try? Data(contentsOf: primaryURL),
                (try? decoder.decode(PlanSnapshot.self, from: existingData)) != nil,
                let backupURL = fallbackSnapshotURL
            {
                try? fileManager.removeItem(at: backupURL)
                try? fileManager.copyItem(at: primaryURL, to: backupURL)
            }
            try data.write(to: primaryURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        } catch {
            // UserDefaults remains the authoritative copy if file protection is unavailable.
        }
    }

    private static func migrateLegacyPreferencesUnlocked() {
        guard !sharedDefaults.bool(forKey: preferenceMigrationKey) else { return }
        for key in sharedPreferenceKeys where sharedDefaults.object(forKey: key) == nil {
            if let value = UserDefaults.standard.object(forKey: key) {
                sharedDefaults.set(value, forKey: key)
            }
        }
        sharedDefaults.set(true, forKey: preferenceMigrationKey)
        sharedDefaults.synchronize()
    }

    /// `NSLock` only protects threads inside one process. Widget App Intents run
    /// in another process, so every read-modify-write also takes an advisory
    /// lock in the shared App Group container.
    private static func withPersistenceLock<T>(
        _ operation: () throws -> T
    ) rethrows -> T {
        lock.lock()
        let descriptor = crossProcessLockDescriptor()
        if descriptor >= 0 {
            flock(descriptor, LOCK_EX)
        }
        defer {
            if descriptor >= 0 {
                flock(descriptor, LOCK_UN)
                Darwin.close(descriptor)
            }
            lock.unlock()
        }
        return try operation()
    }

    private static func crossProcessLockDescriptor() -> Int32 {
        guard let directory = storageDirectoryURL else { return -1 }
        try? fileManager.createDirectory(
            at: directory,
            withIntermediateDirectories: true
        )
        let lockURL = directory.appendingPathComponent("snapshot.lock")
        return lockURL.path.withCString {
            Darwin.open($0, O_CREAT | O_RDWR | O_CLOEXEC, S_IRUSR | S_IWUSR)
        }
    }

    private static var storageDirectoryURL: URL? {
        if let groupURL = fileManager.containerURL(
            forSecurityApplicationGroupIdentifier: appGroupIdentifier
        ) {
            return groupURL.appendingPathComponent("MinutePlan", isDirectory: true)
        }
        return try? fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        .appendingPathComponent("MinutePlan", isDirectory: true)
    }

    private static var primarySnapshotURL: URL? {
        storageDirectoryURL?.appendingPathComponent("snapshot.json")
    }

    private static var fallbackSnapshotURL: URL? {
        storageDirectoryURL?.appendingPathComponent("snapshot.previous.json")
    }

    private static var encoder: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        return encoder
    }

    private static var decoder: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }
}
