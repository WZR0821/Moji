import Foundation
import UniformTypeIdentifiers

/// The automatic backup uses a dedicated extension so it is easy to identify
/// in Files. Keep `.data` as a fallback for backups created before iOS learned
/// about this declaration; their contents are still fully validated on import.
enum MojiBackupFile {
    static let identifier = "com.raydon.moji.backup"

    static var contentType: UTType {
        UTType(exportedAs: identifier, conformingTo: .json)
    }

    static var readableContentTypes: [UTType] {
        [contentType, .json, .data]
    }
}

/// Keeps a copy of the data somewhere iOS will not delete.
///
/// Re-signing an IPA changes the application identity, so iOS treats the
/// install as a different app and discards the old sandbox — including the App
/// Group container. No amount of app code can prevent that. What the app *can*
/// do is keep a current copy in a folder the user owns (iCloud Drive, 我的
/// iPhone, anywhere the Files app can reach), because that folder is outside
/// the sandbox and survives.
///
/// The security-scoped bookmark itself lives in the sandbox and is lost with
/// everything else, so after a re-sign the user picks the folder once more and
/// the data comes straight back. That one tap is the whole cost.
@MainActor
final class ExternalBackupService: ObservableObject {
    static let shared = ExternalBackupService()

    /// Rolling file, always the newest state.
    nonisolated static let backupFileName = "Moji-自动备份.mojibackup"
    nonisolated private static let legacyBackupPrefix = "墨记-"
    nonisolated private static let legacyBackupFileName = "墨记-自动备份.mojibackup"

    private enum Keys {
        static let bookmark = "minuteplan.backup.folderBookmark"
        static let folderName = "minuteplan.backup.folderName"
        static let lastBackupAt = "minuteplan.backup.lastBackupAt"
        static let retentionDays = "minuteplan.backup.retentionDays"
        /// Written by an imported backup: the folder that install was using.
        static let expectedFolderName = "minuteplan.backup.expectedFolderName"
    }

    @Published private(set) var folderDisplayName: String?
    @Published private(set) var lastBackupDate: Date?
    @Published private(set) var lastErrorMessage: String?
    /// How many daily copies to keep. Defaults to half a year.
    @Published private(set) var retention: BackupRetention = .halfYear
    /// The folder the backup we imported came from, when we could not adopt it
    /// automatically. Only a name — the user still has to point at it once.
    @Published private(set) var expectedFolderName: String?

    private let defaults = SharedPersistence.sharedDefaults
    private var pendingBackupTask: Task<Void, Never>?

    private init() {
        reloadFromDefaults()
    }

    /// Importing a backup rewrites the shared defaults underneath us, so the
    /// published copies have to be read again afterwards.
    func reloadFromDefaults() {
        folderDisplayName = defaults.string(forKey: Keys.folderName)
        lastBackupDate = defaults.object(forKey: Keys.lastBackupAt) as? Date
        expectedFolderName = defaults.string(forKey: Keys.expectedFolderName)
        retention = BackupRetention(
            rawValue: PlanSettingsKeys.integer(
                Keys.retentionDays,
                fallback: BackupRetention.halfYear.rawValue,
                defaults: defaults
            )
        ) ?? .halfYear
    }

    func setRetention(_ newValue: BackupRetention) {
        retention = newValue
        defaults.set(newValue.rawValue, forKey: Keys.retentionDays)
        // A background backup also applies the new retention immediately.
        scheduleBackup(delay: 0)
    }

    var isConfigured: Bool {
        defaults.data(forKey: Keys.bookmark) != nil
    }

    // MARK: - Configuration

    func useFolder(at url: URL) {
        let didAccess = url.startAccessingSecurityScopedResource()
        defer {
            if didAccess { url.stopAccessingSecurityScopedResource() }
        }

        do {
            let bookmark = try url.bookmarkData(
                options: .minimalBookmark,
                includingResourceValuesForKeys: nil,
                relativeTo: nil
            )
            defaults.set(bookmark, forKey: Keys.bookmark)
            defaults.set(url.lastPathComponent, forKey: Keys.folderName)
            defaults.removeObject(forKey: Keys.expectedFolderName)
            folderDisplayName = url.lastPathComponent
            expectedFolderName = nil
            lastErrorMessage = nil
            Task { _ = await backupNow() }
        } catch {
            lastErrorMessage = "无法记住这个文件夹：\(error.localizedDescription)"
        }
    }

    func forgetFolder() {
        defaults.removeObject(forKey: Keys.bookmark)
        defaults.removeObject(forKey: Keys.folderName)
        defaults.removeObject(forKey: Keys.lastBackupAt)
        folderDisplayName = nil
        lastBackupDate = nil
        lastErrorMessage = nil
    }

    // MARK: - Restoring into the same folder

    /// Reads the newest backup in a folder the user picked, then keeps writing
    /// to that same folder.
    ///
    /// Picking the folder rather than a single file is what makes the backup
    /// setting survive a re-sign: a document picked in "open a file" mode only
    /// grants access to that one file, so an install restored from a file alone
    /// has nowhere to write its next backup and silently stops backing up.
    func restoreData(fromFolderAt url: URL) throws -> Data {
        let didAccess = url.startAccessingSecurityScopedResource()
        defer {
            if didAccess { url.stopAccessingSecurityScopedResource() }
        }
        guard let newest = Self.newestBackupURL(in: url) else {
            throw ExternalBackupError.noBackupInFolder
        }
        return try Data(contentsOf: newest)
    }

    /// Call after a successful import so the folder becomes the auto-backup
    /// target and the restored retention setting takes effect.
    func adoptFolderAfterRestore(at url: URL) {
        reloadFromDefaults()
        useFolder(at: url)
    }

    /// Best effort for the single-file path: the enclosing folder is usually
    /// out of reach for a file-scoped security bookmark, so this may fail —
    /// and when it does the user is asked for the folder instead.
    @discardableResult
    func adoptFolder(containing fileURL: URL) -> Bool {
        reloadFromDefaults()
        let folderURL = fileURL.deletingLastPathComponent()
        let didAccess = folderURL.startAccessingSecurityScopedResource()
        defer {
            if didAccess { folderURL.stopAccessingSecurityScopedResource() }
        }
        guard
            (try? FileManager.default.contentsOfDirectory(
                at: folderURL,
                includingPropertiesForKeys: nil
            )) != nil,
            let bookmark = try? folderURL.bookmarkData(
                options: .minimalBookmark,
                includingResourceValuesForKeys: nil,
                relativeTo: nil
            )
        else { return false }

        defaults.set(bookmark, forKey: Keys.bookmark)
        defaults.set(folderURL.lastPathComponent, forKey: Keys.folderName)
        defaults.removeObject(forKey: Keys.expectedFolderName)
        folderDisplayName = folderURL.lastPathComponent
        expectedFolderName = nil
        lastErrorMessage = nil
        Task { _ = await backupNow() }
        return true
    }

    /// The rolling file when it is the freshest, otherwise the newest dated
    /// copy. Modification date rather than the name, because a folder can hold
    /// hand-exported files whose names say nothing about when they were made.
    nonisolated static func newestBackupURL(in folderURL: URL) -> URL? {
        guard
            let contents = try? FileManager.default.contentsOfDirectory(
                at: folderURL,
                includingPropertiesForKeys: [.contentModificationDateKey]
            )
        else { return nil }

        let candidates = contents.filter {
            ["mojibackup", "json"].contains($0.pathExtension.lowercased())
        }
        return candidates.max { lhs, rhs in
            let lhsDate = Self.modificationDate(of: lhs)
            let rhsDate = Self.modificationDate(of: rhs)
            if lhsDate != rhsDate { return lhsDate < rhsDate }
            // Same second: prefer the rolling file, which is always rewritten.
            return rhs.lastPathComponent == backupFileName
        }
    }

    nonisolated private static func modificationDate(of url: URL) -> Date {
        (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?
            .contentModificationDate ?? .distantPast
    }

    // MARK: - Writing

    /// Coalesces the bursts of writes that a single user action can cause
    /// (completing a plan touches the snapshot several times).
    func scheduleBackup(delay: TimeInterval = 2.5) {
        guard isConfigured else { return }
        pendingBackupTask?.cancel()
        pendingBackupTask = Task { [weak self] in
            let delayNanoseconds = UInt64(max(0, delay) * 1_000_000_000)
            if delayNanoseconds > 0 {
                try? await Task.sleep(nanoseconds: delayNanoseconds)
            }
            guard !Task.isCancelled, let self else { return }
            _ = await self.backupNow()
        }
    }

    @discardableResult
    func backupNow() async -> Bool {
        guard let bookmark = defaults.data(forKey: Keys.bookmark) else { return false }
        let retentionLimit = retention.keptCopies
        do {
            let result = try await Task.detached(priority: .utility) {
                try Self.performBackup(
                    bookmark: bookmark,
                    retentionLimit: retentionLimit
                )
            }.value
            if let refreshedBookmark = result.refreshedBookmark {
                defaults.set(refreshedBookmark, forKey: Keys.bookmark)
            }
            defaults.set(result.date, forKey: Keys.lastBackupAt)
            lastBackupDate = result.date
            lastErrorMessage = nil
            return true
        } catch {
            lastErrorMessage = "自动备份失败：\(error.localizedDescription)"
            return false
        }
    }

    private struct BackupResult: Sendable {
        let date: Date
        let refreshedBookmark: Data?
    }

    /// Security-scoped I/O and JSON encoding can be slow when the selected
    /// folder lives in iCloud Drive, so none of it runs on the main actor.
    nonisolated private static func performBackup(
        bookmark: Data,
        retentionLimit: Int?
    ) throws -> BackupResult {
        var isStale = false
        let folderURL = try URL(
            resolvingBookmarkData: bookmark,
            options: [],
            relativeTo: nil,
            bookmarkDataIsStale: &isStale
        )
        let didAccess = folderURL.startAccessingSecurityScopedResource()
        defer {
            if didAccess { folderURL.stopAccessingSecurityScopedResource() }
        }

        let data = try SharedPersistence.exportBackup()
        let target = folderURL.appendingPathComponent(backupFileName)
        try data.write(to: target, options: .atomic)

        // A second, dated copy per day, so a bad import cannot take the only
        // good backup with it.
        let now = Date()
        let components = Calendar(identifier: .gregorian)
            .dateComponents([.year, .month, .day], from: now)
        let stamp = String(
            format: "%04d%02d%02d",
            components.year ?? 0,
            components.month ?? 0,
            components.day ?? 0
        )
        let dated = folderURL.appendingPathComponent("Moji-\(stamp).mojibackup")
        if !FileManager.default.fileExists(atPath: dated.path) {
            try? data.write(to: dated, options: .atomic)
        }
        pruneDatedBackups(in: folderURL, keeping: retentionLimit)

        let refreshedBookmark: Data?
        if isStale {
            refreshedBookmark = try? folderURL.bookmarkData(
                options: .minimalBookmark,
                includingResourceValuesForKeys: nil,
                relativeTo: nil
            )
        } else {
            refreshedBookmark = nil
        }
        return BackupResult(date: now, refreshedBookmark: refreshedBookmark)
    }

    /// Keeps the most recent dated copies and drops the rest.
    nonisolated private static func pruneDatedBackups(
        in folderURL: URL,
        keeping limit: Int?
    ) {
        guard let limit else { return }
        guard
            let contents = try? FileManager.default.contentsOfDirectory(
                at: folderURL,
                includingPropertiesForKeys: nil
            )
        else { return }

        let dated = contents
            .filter {
                guard
                    $0.pathExtension == "mojibackup",
                    $0.lastPathComponent != Self.backupFileName,
                    $0.lastPathComponent != Self.legacyBackupFileName,
                    ($0.lastPathComponent.hasPrefix("Moji-")
                        || $0.lastPathComponent.hasPrefix(Self.legacyBackupPrefix))
                else { return false }
                return Self.datedBackupStamp(for: $0) != nil
            }
            .sorted {
                (Self.datedBackupStamp(for: $0) ?? "")
                    > (Self.datedBackupStamp(for: $1) ?? "")
            }

        for url in dated.dropFirst(limit) {
            try? FileManager.default.removeItem(at: url)
        }
    }

    nonisolated private static func datedBackupStamp(for url: URL) -> String? {
        let stem = url.deletingPathExtension().lastPathComponent
        guard let separator = stem.lastIndex(of: "-") else { return nil }
        let stamp = String(stem[stem.index(after: separator)...])
        guard stamp.count == 8, stamp.allSatisfy(\.isNumber) else { return nil }
        return stamp
    }

    // MARK: - Reading

    /// Reads a backup the user picked from the Files app.
    static func readBackup(at url: URL) throws -> Data {
        let didAccess = url.startAccessingSecurityScopedResource()
        defer {
            if didAccess { url.stopAccessingSecurityScopedResource() }
        }
        return try Data(contentsOf: url)
    }
}

enum BackupRestoreCopy {
    static let folderHint = "选择上次存放备份的那个文件夹，Moji 会自动取出里面最新的一份恢复，并继续往同一个文件夹备份。"
    static let folderStillNeeded = "数据已恢复。iOS 没有把这个文件所在的文件夹一并授权给 Moji，请在「回顾 → 数据备份」里选一次备份文件夹，之后就会继续自动备份。"
}

/// The two ways back into a restored install, in one place so the first-run
/// sheet, the home screen card and the settings screen cannot drift apart.
@MainActor
enum BackupRestoreCoordinator {
    /// Restores from a folder and keeps backing up into it.
    static func restore(fromFolderAt url: URL, into store: PlanStore) throws {
        let service = ExternalBackupService.shared
        let data = try service.restoreData(fromFolderAt: url)
        try store.importBackup(data)
        service.adoptFolderAfterRestore(at: url)
    }

    /// Restores from a single file. Returns whether the enclosing folder could
    /// also be adopted for future backups; when it could not, the caller has to
    /// ask the user to pick that folder.
    @discardableResult
    static func restore(fromFileAt url: URL, into store: PlanStore) throws -> Bool {
        let data = try ExternalBackupService.readBackup(at: url)
        try store.importBackup(data)
        return ExternalBackupService.shared.adoptFolder(containing: url)
    }
}

enum ExternalBackupError: LocalizedError {
    case noBackupInFolder

    var errorDescription: String? {
        switch self {
        case .noBackupInFolder:
            return "这个文件夹里没有 Moji 备份文件。请选择存放 .mojibackup 的那个文件夹。"
        }
    }
}

/// How long the dated copies are kept. One copy is written per day, so the
/// count and the number of days are the same thing.
enum BackupRetention: Int, CaseIterable, Identifiable {
    case oneMonth = 30
    case threeMonths = 90
    case halfYear = 180
    case oneYear = 365
    case forever = 0

    var id: Int { rawValue }

    /// `nil` means never prune.
    var keptCopies: Int? {
        self == .forever ? nil : rawValue
    }

    var displayName: String {
        switch self {
        case .oneMonth: return "1 个月"
        case .threeMonths: return "3 个月"
        case .halfYear: return "半年"
        case .oneYear: return "1 年"
        case .forever: return "一直保留"
        }
    }
}
