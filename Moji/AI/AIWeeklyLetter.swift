import Foundation
import UIKit
import UserNotifications

/// One 本周来信, kept on this device with the sentence of facts it was
/// written under.
struct WeeklyLetter: Codable, Hashable, Identifiable {
    struct Evidence: Codable, Hashable {
        var point: String
        /// Titles of the plans the point rests on, looked up locally.
        var plans: [String]
    }

    /// The Monday of the week, yyyy-MM-dd.
    var week: String
    var title: String
    var body: String
    var nextFocus: String
    var facts: String
    var evidence: [Evidence]
    /// Seconds since 1970.
    var createdAt: Double

    var id: String { week }
}

/// 本周来信: which week, what is said on the device, what is sent and how
/// the answer is read. Pure, so the same samples replay in tests.
enum AIWeeklyLetter {
    /// Sunday at this hour the week counts as over.
    static let hour = 20
    static let minimumPlans = 3
    static let limit = 52
    static let notificationIdentifier = "moji.ai.weeklyLetter"

    /// This Sunday 20:00 for the week containing `date`.
    static func letterMoment(ofWeekContaining date: Date, calendar: Calendar = .mojiISO) -> Date {
        let monday = PlanWorkflow.weekStart(date, calendar: calendar)
        let sunday = calendar.date(byAdding: .day, value: 6, to: monday) ?? monday
        return calendar.date(bySettingHour: hour, minute: 0, second: 0, of: sunday) ?? sunday
    }

    /// The Monday of the week the letter is about: this week from Sunday
    /// 20:00 on, the week before until then.
    static func letterWeek(now: Date, calendar: Calendar = .mojiISO) -> Date {
        let monday = PlanWorkflow.weekStart(now, calendar: calendar)
        if now >= letterMoment(ofWeekContaining: now, calendar: calendar) { return monday }
        return calendar.date(byAdding: .day, value: -7, to: monday) ?? monday
    }

    /// The next Sunday 20:00 strictly after `now`.
    static func nextNotification(after now: Date, calendar: Calendar = .mojiISO) -> Date {
        let moment = letterMoment(ofWeekContaining: now, calendar: calendar)
        if now < moment { return moment }
        return calendar.date(byAdding: .day, value: 7, to: moment) ?? moment
    }

    /// Sunday 20:00 to Tuesday 0:00, when the teaser may stand on the page.
    static func isTeaserTime(_ now: Date, calendar: Calendar = .mojiISO) -> Bool {
        let week = letterWeek(now: now, calendar: calendar)
        let moment = letterMoment(ofWeekContaining: week, calendar: calendar)
        let end = calendar.date(byAdding: .hour, value: 28, to: moment) ?? moment
        return now >= moment && now < end
    }

    /// Sunday 12:00–20:00, when the letter is written ahead of its notification.
    static func isPreparingTime(_ now: Date, calendar: Calendar = .mojiISO) -> Bool {
        let moment = letterMoment(ofWeekContaining: now, calendar: calendar)
        guard let start = calendar.date(byAdding: .hour, value: -8, to: moment) else { return false }
        return now >= start && now < moment
    }

    static func weekKey(_ monday: Date, calendar: Calendar = .mojiISO) -> String {
        PlanWorkflow.weekKey(monday, calendar: calendar)
    }

    /// 11月16日 – 22日 (the month again when the week crosses one).
    static func rangeText(_ monday: Date, calendar: Calendar = .mojiISO) -> String {
        let sunday = calendar.date(byAdding: .day, value: 6, to: monday) ?? monday
        let start = calendar.dateComponents([.month, .day], from: monday)
        let end = calendar.dateComponents([.month, .day], from: sunday)
        let tail = start.month == end.month ? "\(end.day ?? 1)日" : "\(end.month ?? 1)月\(end.day ?? 1)日"
        return "\(start.month ?? 1)月\(start.day ?? 1)日 – \(tail)"
    }

    static func monday(fromKey key: String, calendar: Calendar = .mojiISO) -> Date? {
        let parts = key.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        return calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2]))
    }

    // MARK: Facts

    struct Facts: Equatable {
        var planned = 0
        var completed = 0
        var focusMinutes = 0
        var previousFocusMinutes = 0
    }

    /// The same numbers as 本周回顾 for that week, and the focus of the week before.
    static func facts(items: [CheckInItem], records: [TimeRecord], week monday: Date,
                      calendar: Calendar = .mojiISO) -> Facts {
        let summary = CheckInAnalytics.week(items: items, records: records, containing: monday, calendar: calendar)
        let previous = calendar.date(byAdding: .day, value: -7, to: monday) ?? monday
        let before = CheckInAnalytics.week(items: items, records: records, containing: previous, calendar: calendar)
        return Facts(planned: summary.plannedCount, completed: summary.completedCount,
                     focusMinutes: summary.actualMinutes, previousFocusMinutes: before.actualMinutes)
    }

    /// 这周完成 9/14，专注 6 小时 20 分钟，比上周多 1 小时。
    static func factsLine(_ facts: Facts) -> String {
        var text = "这周完成 \(facts.completed)/\(facts.planned)"
        if facts.focusMinutes > 0 {
            text += "，专注 \(DurationText.full(minutes: facts.focusMinutes))"
            if facts.previousFocusMinutes > 0 {
                let difference = facts.focusMinutes - facts.previousFocusMinutes
                if difference >= 10 {
                    text += "，比上周多 \(DurationText.full(minutes: difference))"
                } else if difference <= -10 {
                    text += "，比上周少 \(DurationText.full(minutes: -difference))"
                }
            }
        }
        return text + "。"
    }

    // MARK: Asking

    static let instructions = """
    \(AIDrafting.preface)
    任务：一周结束了，替用户写一封“本周来信”：一个题签和两三句这一周的情况。只依据记录里的事实；数字只能照抄记录，不要推算新的数字；不评价用户的为人，不夸奖，不用感叹号，不用“你”。
    要讲跨天的事：哪几天顺、哪几天卡住，哪些计划一直顺延、卡在哪一步；不要逐条复述记录。
    输出 JSON：{"title":"不超过 10 个字的题签","body":"两三句，不超过 80 字","next_focus":"给下周的一句提醒，不超过 40 字","evidence":[{"point":"不超过 20 字的一句依据","plans":[计划编号]}]}
    evidence 给 1 到 3 条，编号只能用记录里出现过的。
    """

    struct Draft: Equatable {
        var title: String
        var body: String
        var nextFocus: String
        var evidence: [WeeklyLetter.Evidence]
    }

    private static let titleMarks = CharacterSet(charactersIn: "「」『』“”\"'《》").union(.whitespacesAndNewlines)

    private static func flat(_ text: String) -> String {
        text.components(separatedBy: .newlines).joined(separator: " ").trimmingCharacters(in: .whitespaces)
    }

    static func letter(from answer: String, plans: [CheckInItem]) throws -> Draft {
        let object = try AIDrafting.object(in: answer)
        guard let rawTitle = AIDrafting.string(object["title"]) else { throw AIRequestError.unreadableAnswer }
        let title = String(flat(rawTitle.trimmingCharacters(in: titleMarks)).prefix(12))
        guard !title.isEmpty, let body = AIDrafting.string(object["body"]).map(flat), !body.isEmpty else {
            throw AIRequestError.unreadableAnswer
        }
        let evidence = (object["evidence"] as? [Any] ?? []).compactMap { raw -> WeeklyLetter.Evidence? in
            guard let item = raw as? [String: Any], let point = AIDrafting.string(item["point"]) else { return nil }
            let titles = (item["plans"] as? [Any] ?? []).compactMap { AIDrafting.integer($0) }
                .filter { $0 >= 1 && $0 <= plans.count }.map { plans[$0 - 1].title }
            guard !titles.isEmpty else { return nil }
            return WeeklyLetter.Evidence(point: String(flat(point).prefix(40)), plans: Array(titles.prefix(4)))
        }
        return Draft(title: title, body: String(body.prefix(120)),
                     nextFocus: String((AIDrafting.string(object["next_focus"]).map(flat) ?? "").prefix(60)),
                     evidence: Array(evidence.prefix(3)))
    }

    /// 依据：「归纳上周待办」顺延 4 次 · 「准备季度汇报」还没开始
    static func evidenceLine(_ evidence: [WeeklyLetter.Evidence]) -> String? {
        guard !evidence.isEmpty else { return nil }
        return "依据：" + evidence.map(\.point).joined(separator: " · ")
    }

    /// The letter as it goes into 周复盘's draft area.
    static func reflectionDraft(_ letter: WeeklyLetter) -> ReflectionDraft {
        ReflectionDraft(note: letter.facts + letter.body, nextFocus: letter.nextFocus,
                        evidence: letter.evidence.map { ReflectionDraft.Evidence(point: $0.point, planTitles: $0.plans) })
    }

    static func notificationBody(title: String?) -> String {
        if let title, !title.isEmpty { return "这周的来信：「\(title)」" }
        return "这一周过完了，打开回顾看看墨记的来信。"
    }
}

/// The letters kept on this device (at most 52, one a week), the week whose
/// letter was put away, and the request in flight. App-local only.
@MainActor
final class AIWeeklyLetterState: ObservableObject {
    enum Keys {
        static let letters = "moji.ai.letters.v1"
        static let attempt = "moji.ai.letter.attempt"
        static let dismissedWeek = "moji.ai.letter.dismissedWeek"
    }

    static let shared = AIWeeklyLetterState(defaults: makeDefaults())

    /// Oldest first, as stored.
    @Published private(set) var letters: [WeeklyLetter]
    @Published private(set) var loadingWeek: String?
    @Published private(set) var dismissedWeek: String?

    private let defaults: UserDefaults

    init(defaults: UserDefaults) {
        self.defaults = defaults
        letters = defaults.data(forKey: Keys.letters).flatMap { try? JSONDecoder().decode([WeeklyLetter].self, from: $0) } ?? []
        dismissedWeek = defaults.string(forKey: Keys.dismissedWeek)
    }

    private static func makeDefaults() -> UserDefaults {
#if DEBUG
        if AIQAFixture.isActive, let defaults = UserDefaults(suiteName: "moji.qa.ai.letters") {
            defaults.removePersistentDomain(forName: "moji.qa.ai.letters")
            return defaults
        }
#endif
        return .standard
    }

    var newestFirst: [WeeklyLetter] { letters.sorted { $0.week > $1.week } }

    func letter(for monday: Date) -> WeeklyLetter? {
        let key = AIWeeklyLetter.weekKey(monday)
        return letters.first { $0.week == key }
    }

    func isDismissed(_ monday: Date) -> Bool { dismissedWeek == AIWeeklyLetter.weekKey(monday) }

    func dismiss(_ monday: Date) {
        let key = AIWeeklyLetter.weekKey(monday)
        dismissedWeek = key
        defaults.set(key, forKey: Keys.dismissedWeek)
    }

    func isLoading(_ monday: Date) -> Bool { loadingWeek == AIWeeklyLetter.weekKey(monday) }

    var lastAttempt: Date? {
        let seconds = defaults.double(forKey: Keys.attempt)
        return seconds > 0 ? Date(timeIntervalSince1970: seconds) : nil
    }

    /// Keeps one letter a week, the newest 52.
    static func merged(_ letters: [WeeklyLetter], with letter: WeeklyLetter) -> [WeeklyLetter] {
        var list = letters.filter { $0.week != letter.week } + [letter]
        list.sort { $0.week < $1.week }
        if list.count > AIWeeklyLetter.limit { list.removeFirst(list.count - AIWeeklyLetter.limit) }
        return list
    }

    func store(_ letter: WeeklyLetter) {
        letters = Self.merged(letters, with: letter)
        if let data = try? JSONEncoder().encode(letters) { defaults.set(data, forKey: Keys.letters) }
    }

    /// Whether a request for that week would go out now.
    func canAsk(for monday: Date, snapshot: PlanSnapshot, now: Date, ai: AISettingsStore) -> Bool {
        guard ai.isReady, ai.settings.weeklyLetter, loadingWeek == nil, letter(for: monday) == nil else { return false }
        let facts = AIWeeklyLetter.facts(items: snapshot.checkInItems, records: snapshot.records, week: monday)
        guard facts.planned >= AIWeeklyLetter.minimumPlans else { return false }
        if let last = lastAttempt, now >= last, now.timeIntervalSince(last) < AIDailyBrief.retryInterval { return false }
        return true
    }

    /// Writes the letter for that week when nothing is kept for it yet. A
    /// failed request waits half an hour; nothing is shown for it.
    @discardableResult
    func refreshIfNeeded(for monday: Date, snapshot: PlanSnapshot, now: Date,
                         ai store: AISettingsStore? = nil) -> Task<Void, Never>? {
        let ai = store ?? .shared
        guard canAsk(for: monday, snapshot: snapshot, now: now, ai: ai) else { return nil }
        defaults.set(now.timeIntervalSince1970, forKey: Keys.attempt)
        let key = AIWeeklyLetter.weekKey(monday)
        loadingWeek = key
        let facts = AIWeeklyLetter.factsLine(AIWeeklyLetter.facts(items: snapshot.checkInItems,
                                                                  records: snapshot.records, week: monday))
        return Task {
            do {
                let draft = try await AIAssistant(store: ai).weeklyLetter(snapshot: snapshot, week: monday, now: now)
                self.store(WeeklyLetter(week: key, title: draft.title, body: draft.body, nextFocus: draft.nextFocus,
                                        facts: facts, evidence: draft.evidence,
                                        createdAt: Date().timeIntervalSince1970))
                AIWeeklyLetterNotifier.reschedule(ai: ai)
            } catch {
                // Quiet on purpose: the card stays away until the next try.
            }
            self.loadingWeek = nil
        }
    }

    /// Sunday afternoon, so the 20:00 notification can carry the title. When
    /// the app is going to the background the request is given time to finish.
    func prepareIfNeeded(snapshot: PlanSnapshot, now: Date, inBackground: Bool) {
        guard AIWeeklyLetter.isPreparingTime(now) else { return }
        let monday = PlanWorkflow.weekStart(now, calendar: .mojiISO)
        guard inBackground else {
            refreshIfNeeded(for: monday, snapshot: snapshot, now: now)
            return
        }
        let background = BackgroundTaskBox()
        background.identifier = UIApplication.shared.beginBackgroundTask(withName: "moji.ai.weeklyLetter") {
            background.end()
        }
        guard let task = refreshIfNeeded(for: monday, snapshot: snapshot, now: now) else {
            background.end()
            return
        }
        Task {
            await task.value
            background.end()
        }
    }

#if DEBUG
    /// Three earlier letters for the photographed archive.
    func seedArchiveForQA(now: Date) {
        let current = AIWeeklyLetter.letterWeek(now: now)
        let samples: [(Int, String, String, String)] = [
            (1, "顺延集中在周四", "这周完成 10/14，专注 6 小时 50 分钟，比上周少 15 分钟。",
             "周一到周三按计划推进；周四的三件都挪到了周末，周日补完两件。"),
            (2, "前紧后松", "这周完成 11/13，专注 7 小时 5 分钟，比上周多 1 小时 45 分钟。",
             "周一到周三排得很满，周末留了空；顺延的只有一件。"),
            (3, "复习按天排开", "这周完成 8/12，专注 5 小时 20 分钟，比上周少 40 分钟。",
             "复习按天排开后每天都有进展；周五的两件挪到了周末。")
        ]
        for (weeksBack, title, facts, body) in samples {
            guard let monday = Calendar.mojiISO.date(byAdding: .day, value: -7 * weeksBack, to: current) else { continue }
            store(WeeklyLetter(week: AIWeeklyLetter.weekKey(monday), title: title, body: body,
                               nextFocus: "先把大事拆出能马上开始的第一步。", facts: facts,
                               evidence: [WeeklyLetter.Evidence(point: "「归纳上周待办」一直顺延", plans: ["归纳上周待办"]),
                                          WeeklyLetter.Evidence(point: "大事还没开始", plans: ["准备季度汇报"])],
                               createdAt: monday.timeIntervalSince1970 + 6 * 86_400 + 20 * 3_600))
        }
    }
#endif
}

/// The one Sunday 20:00 notification for 本周来信.
enum AIWeeklyLetterNotifier {
    @MainActor
    static func reschedule(ai: AISettingsStore? = nil, letters: AIWeeklyLetterState? = nil, now: Date = Date()) {
        let ai = ai ?? .shared
        let state = letters ?? .shared
        let wanted = ai.isReady && ai.settings.weeklyLetter
        let date = AIWeeklyLetter.nextNotification(after: now)
        let title = state.letter(for: PlanWorkflow.weekStart(date, calendar: .mojiISO))?.title
        Task {
            let center = UNUserNotificationCenter.current()
            center.removePendingNotificationRequests(withIdentifiers: [AIWeeklyLetter.notificationIdentifier])
            guard wanted else { return }
            let status = await center.notificationSettings().authorizationStatus
            guard [.authorized, .provisional, .ephemeral].contains(status) else { return }
            let content = UNMutableNotificationContent()
            content.title = "本周来信"
            content.body = AIWeeklyLetter.notificationBody(title: title)
            content.sound = .default
            content.threadIdentifier = AIWeeklyLetter.notificationIdentifier
            let parts = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: date)
            let request = UNNotificationRequest(identifier: AIWeeklyLetter.notificationIdentifier, content: content,
                                                trigger: UNCalendarNotificationTrigger(dateMatching: parts, repeats: false))
            try? await center.add(request)
        }
    }
}

/// Holds the background task while the letter is written, so the expiry
/// handler and the finished request end the same task, once.
@MainActor
private final class BackgroundTaskBox {
    var identifier = UIBackgroundTaskIdentifier.invalid

    func end() {
        guard identifier != .invalid else { return }
        UIApplication.shared.endBackgroundTask(identifier)
        identifier = .invalid
    }
}
