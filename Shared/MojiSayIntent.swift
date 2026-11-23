import AppIntents
import Foundation

/// 说一句添加: opens Moji with the plan field focused and the microphone on.
/// The lock-screen widget, the Control Center button and Shortcuts all end
/// here; the app picks the request up when it becomes active.
struct MojiSayIntent: AppIntent {
    static var title: LocalizedStringResource = "说一句添加"
    static var description = IntentDescription("打开墨记，直接说一句话添加计划。")
    static var openAppWhenRun: Bool = true

    func perform() async throws -> some IntentResult {
        MojiSayRequest.mark()
        return .result()
    }
}

/// The hand-over between the intent and the app: a time in the shared
/// settings, honoured for a minute.
enum MojiSayRequest {
    static let key = "moji.say.requestedAt"
    static let window: TimeInterval = 60
    static var url: URL? { URL(string: "moji://say") }

    static func mark(now: Date = Date()) {
        SharedPersistence.sharedDefaults.set(now.timeIntervalSince1970, forKey: key)
        SharedPersistence.sharedDefaults.synchronize()
        NotificationCenter.default.post(name: .mojiSayRequested, object: nil)
    }

    /// True once for a request made in the last minute; the request is cleared.
    static func take(now: Date = Date()) -> Bool {
        let defaults = SharedPersistence.sharedDefaults
        let seconds = defaults.double(forKey: key)
        guard seconds > 0 else { return false }
        defaults.removeObject(forKey: key)
        let age = now.timeIntervalSince1970 - seconds
        return age >= 0 && age <= window
    }
}

extension Notification.Name {
    static let mojiSayRequested = Notification.Name("moji.say.requested")
}
