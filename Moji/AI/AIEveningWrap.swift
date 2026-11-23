import Foundation

/// The clock the evening and Sunday cards read. A debug scenario can stand
/// it at 21:30 or on Sunday evening so those screens can be photographed at
/// any hour; a release build always reads the real time.
enum AIClock {
    static func now() -> Date {
#if DEBUG
        if let date = AIQAFixture.clock { return date }
#endif
        return Date()
    }
}

/// One way to deal with a plan left over at the end of the day.
enum WrapAction: String, Codable, CaseIterable, Identifiable {
    case tomorrow, morning, split, drop, keep

    var id: String { rawValue }

    var title: String {
        switch self {
        case .tomorrow: return "顺延到明天"
        case .morning: return "明早先做"
        case .split: return "拆小，明早先做第一步"
        case .drop: return "放下"
        case .keep: return "留在今天"
        }
    }
}

/// The first sentence of 收尾, worked out on the device every time it is drawn.
struct EveningWrapFacts: Equatable {
    var completed = 0
    var focusMinutes = 0
    /// Focus on the days of this week before today, rounded; nil on a Monday.
    var weekAverage: Int?
}

/// What the model said for one plan.
struct WrapArrangement: Equatable {
    var action: WrapAction
    var note: String?
}

/// 晚间收尾: what is said, what is sent, how the answer is read and what
/// 照此处理 writes. Pure, so the same samples replay in tests.
enum AIEveningWrap {
    static let startHour = 21
    static let maximumRows = 6
    static let maximumPlans = 20
    static let maximumDone = 20

    static func isEvening(_ now: Date, calendar: Calendar = .current) -> Bool {
        calendar.component(.hour, from: now) >= startHour
    }

    // MARK: Facts

    static func facts(items: [CheckInItem], records: [TimeRecord], today: Date,
                      calendar: Calendar = .mojiISO) -> EveningWrapFacts {
        let week = CheckInAnalytics.week(items: items, records: records, containing: today, calendar: calendar)
        let start = calendar.startOfDay(for: today)
        let day = week.days.first { calendar.isDate($0.date, inSameDayAs: today) }
        let before = week.days.filter { $0.date < start }
        let average = before.isEmpty
            ? nil
            : Int((Double(before.reduce(0) { $0 + $1.actualMinutes }) / Double(before.count)).rounded())
        return EveningWrapFacts(completed: day?.completedCount ?? 0, focusMinutes: day?.actualMinutes ?? 0,
                                weekAverage: average)
    }

    /// 今天完成 3 件，专注 1 小时 40 分钟，比这周平均多 20 分钟。
    static func factsLine(_ facts: EveningWrapFacts) -> String {
        var text = facts.completed > 0 ? "今天完成 \(facts.completed) 件" : "今天还没有完成的计划"
        if facts.focusMinutes > 0 {
            text += "，专注 \(DurationText.full(minutes: facts.focusMinutes))"
            if let average = facts.weekAverage, average > 0 {
                let difference = facts.focusMinutes - average
                if difference >= 10 {
                    text += "，比这周平均多 \(DurationText.full(minutes: difference))"
                } else if difference <= -10 {
                    text += "，比这周平均少 \(DurationText.full(minutes: -difference))"
                }
            }
        }
        return text + "。"
    }

    static func shouldShow(_ facts: EveningWrapFacts, pending: Int) -> Bool {
        facts.completed >= 1 || pending >= 1
    }

    static func secondLine(applied: Bool, pending: Int, hasArrangement: Bool) -> String {
        if applied { return "剩下的已经安排好了。" }
        if pending == 0 { return "今天的计划都做完了。" }
        if hasArrangement { return "剩下 \(pending) 件这样安排：" }
        return "剩下 \(pending) 件没做完。"
    }

    // MARK: Asking

    static func instructions(now: Date, calendar: Calendar = .current) -> String {
        """
        \(AIDrafting.preface)
        任务：现在是晚上，替用户收尾今天：给每件没做完的计划一个安排。\(AIDrafting.todayLine(now, calendar: calendar))
        安排只能是四种之一："tomorrow"（顺延到明天，时间不变）、"morning"（挪到明天上午先做）、"split"（太大或顺延多天的，拆小后明早先做第一步）、"drop"（看起来不必再做的，放下）。拿不准时用 "tomorrow"；只有输入里看得出不必再做时才用 "drop"。不要编造输入里没有的事。
        输出 JSON：{"items":[{"index":计划编号,"action":"tomorrow"|"morning"|"split"|"drop","note":"…"}]}
        note 不超过 16 个字，说为什么这样安排，可以提到已经顺延几天、离哪个日子还有几天；不要复述标题，不要夸奖，不要用感叹号。
        """
    }

    /// What was done today, the plans left (numbered as for 今日提要) and the
    /// days coming up. Nothing else is sent.
    static func input(done: [CheckInItem], pending: [CheckInItem], countdowns: [CountdownEvent], today: Date,
                      calendar: Calendar = .mojiISO) -> String {
        var lines: [String] = []
        if !done.isEmpty {
            lines.append("今天做完的：")
            for item in done.prefix(maximumDone) {
                lines.append("- \(AIDailyBrief.oneLine(item.title, limit: 60))（\(item.category.displayName)）")
            }
        }
        lines.append("没做完的：")
        for (index, item) in pending.prefix(maximumPlans).enumerated() {
            lines.append(AIDailyBrief.planLine(item, number: index + 1, today: today, calendar: calendar))
        }
        if let days = AIDailyBrief.daysLine(countdowns, today: today, calendar: calendar) { lines.append(days) }
        return lines.joined(separator: "\n")
    }

    /// Plan number → what to do with it. Numbers outside 1…count, unknown
    /// actions and repeated numbers are dropped; nothing usable throws.
    static func arrangement(from answer: String, count: Int) throws -> [Int: WrapArrangement] {
        guard count > 0 else { throw AIRequestError.unreadableAnswer }
        let object = try AIDrafting.object(in: answer)
        var result: [Int: WrapArrangement] = [:]
        for raw in object["items"] as? [Any] ?? [] {
            guard let item = raw as? [String: Any],
                  let number = AIDrafting.integer(item["index"]), (1...count).contains(number),
                  result[number] == nil,
                  let name = AIDrafting.string(item["action"]),
                  let action = WrapAction(rawValue: name), action != .keep else { continue }
            result[number] = WrapArrangement(action: action, note: note(item["note"]))
        }
        guard !result.isEmpty else { throw AIRequestError.unreadableAnswer }
        return result
    }

    static func note(_ value: Any?) -> String? {
        guard var text = AIDrafting.string(value)?.components(separatedBy: .newlines).joined(separator: " ") else {
            return nil
        }
        while let last = text.last, "。.！!".contains(last) { text.removeLast() }
        text = text.trimmingCharacters(in: .whitespaces)
        return text.isEmpty ? nil : String(text.prefix(24))
    }

    // MARK: 照此处理

    struct Outcome: Equatable {
        var changed: [CheckInItem] = []
        var tomorrow = 0
        var morning = 0
        var dropped = 0
        /// The first plan to break down once the rest is written.
        var firstSplit: UUID?

        var summary: String { AIWriteText.wrap(tomorrow: tomorrow, morning: morning, dropped: dropped) }
    }

    /// Plans moved to tomorrow keep their time; 明早 and 拆小 move to tomorrow
    /// morning with their length; 放下 archives. A plan being timed stays.
    static func apply(_ plans: [(item: CheckInItem, action: WrapAction)], today: Date,
                      calendar: Calendar = .current) -> Outcome {
        var outcome = Outcome()
        guard let tomorrow = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: today)) else {
            return outcome
        }
        for (item, action) in plans where item.kind == .planned && item.status == .planned {
            switch action {
            case .tomorrow:
                guard let moved = PlanQuickChange.date(tomorrow).applying(to: item, calendar: calendar) else { continue }
                outcome.changed.append(moved)
                outcome.tomorrow += 1
            case .morning, .split:
                guard let moved = PlanQuickChange.date(tomorrow).applying(to: item, calendar: calendar),
                      let morning = PlanQuickChange.schedule(.morning, hour: 9, minute: 0,
                                                             estimatedMinutes: item.plannedDurationMinutes)
                          .applying(to: moved, calendar: calendar) else { continue }
                outcome.changed.append(morning)
                outcome.morning += 1
                if action == .split, outcome.firstSplit == nil { outcome.firstSplit = item.id }
            case .drop:
                var archived = item
                archived.isArchived = true
                outcome.changed.append(archived)
                outcome.dropped += 1
            case .keep:
                break
            }
        }
        return outcome
    }
}

/// What 晚间收尾 remembers on this device: the evening's answer, the evening
/// the card was closed, the evening it was carried out. App-local only.
@MainActor
final class AIEveningWrapState: ObservableObject {
    struct Entry: Codable, Equatable {
        var id: String
        var action: WrapAction
        var note: String?
    }

    struct Saved: Codable, Equatable {
        var day: String
        var items: [Entry]
    }

    enum Keys {
        static let wrap = "moji.ai.wrap.v1"
        static let attempt = "moji.ai.wrap.attempt"
        static let dismissedDay = "moji.ai.wrap.dismissedDay"
        static let appliedDay = "moji.ai.wrap.appliedDay"
    }

    static let shared = AIEveningWrapState(defaults: makeDefaults())

    @Published private(set) var saved: Saved?
    @Published private(set) var isLoading = false
    @Published private(set) var dismissedDay: String?
    @Published private(set) var appliedDay: String?

    private let defaults: UserDefaults

    init(defaults: UserDefaults) {
        self.defaults = defaults
        saved = defaults.data(forKey: Keys.wrap).flatMap { try? JSONDecoder().decode(Saved.self, from: $0) }
        dismissedDay = defaults.string(forKey: Keys.dismissedDay)
        appliedDay = defaults.string(forKey: Keys.appliedDay)
    }

    private static func makeDefaults() -> UserDefaults {
#if DEBUG
        if AIQAFixture.isActive, let defaults = UserDefaults(suiteName: "moji.qa.ai.wrap") {
            defaults.removePersistentDomain(forName: "moji.qa.ai.wrap")
            return defaults
        }
#endif
        return .standard
    }

    func arrangement(on day: Date) -> [UUID: Entry] {
        guard let saved, saved.day == AIDailyState.dayKey(day) else { return [:] }
        var result: [UUID: Entry] = [:]
        for entry in saved.items {
            if let id = UUID(uuidString: entry.id), result[id] == nil { result[id] = entry }
        }
        return result
    }

    /// An answer for today that still speaks about a plan left on the list.
    func hasArrangement(on day: Date, pending: [CheckInItem]) -> Bool {
        let ids = arrangement(on: day)
        return pending.contains { ids[$0.id] != nil }
    }

    func isDismissed(on day: Date) -> Bool { dismissedDay == AIDailyState.dayKey(day) }

    func dismiss(on day: Date) {
        let key = AIDailyState.dayKey(day)
        dismissedDay = key
        defaults.set(key, forKey: Keys.dismissedDay)
    }

    func isApplied(on day: Date) -> Bool { appliedDay == AIDailyState.dayKey(day) }

    func markApplied(on day: Date) {
        let key = AIDailyState.dayKey(day)
        appliedDay = key
        defaults.set(key, forKey: Keys.appliedDay)
    }

    var lastAttempt: Date? {
        let seconds = defaults.double(forKey: Keys.attempt)
        return seconds > 0 ? Date(timeIntervalSince1970: seconds) : nil
    }

    /// Asks once an evening, while the card is on screen, for what to do
    /// with each plan left. A failed request waits half an hour, quietly.
    func refreshIfNeeded(done: [CheckInItem], pending: [CheckInItem], countdowns: [CountdownEvent],
                         now: Date, ai store: AISettingsStore? = nil) {
        let ai = store ?? .shared
        guard ai.isReady, ai.settings.eveningWrap, !isLoading, !pending.isEmpty,
              AIEveningWrap.isEvening(now), !isDismissed(on: now), !isApplied(on: now),
              arrangement(on: now).isEmpty else { return }
        if let last = lastAttempt, now >= last, now.timeIntervalSince(last) < AIDailyBrief.retryInterval { return }
        defaults.set(now.timeIntervalSince1970, forKey: Keys.attempt)
        isLoading = true
        let plans = Array(pending.prefix(AIEveningWrap.maximumPlans))
        let day = AIDailyState.dayKey(now)
        Task {
            do {
                let result = try await AIAssistant(store: ai).eveningWrap(done: done, pending: plans,
                                                                         countdowns: countdowns, now: now)
                let items = result.sorted { $0.key < $1.key }.compactMap { number, value -> Entry? in
                    guard plans.indices.contains(number - 1) else { return nil }
                    return Entry(id: plans[number - 1].id.uuidString, action: value.action, note: value.note)
                }
                let value = Saved(day: day, items: items)
                self.saved = value
                if let data = try? JSONEncoder().encode(value) { self.defaults.set(data, forKey: Keys.wrap) }
            } catch {
                // Quiet on purpose: the card keeps its first two lines.
            }
            self.isLoading = false
        }
    }
}
