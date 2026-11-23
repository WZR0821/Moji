import Foundation

/// The facts on the first line of 今日提要. They are worked out on the device
/// each time the card is drawn and never cached, so they cannot go stale.
struct DailyBriefFacts: Equatable {
    struct Carried: Equatable {
        var id: UUID
        var title: String
        var days: Int
        var isPlanned: Bool
    }

    var count = 0
    var totalMinutes = 0
    var countdownTitle: String?
    var countdownDays = 0
    /// Plans waiting since an earlier day, the longest wait first.
    var carried: [Carried] = []

    var hasNearCountdown: Bool { countdownTitle != nil }
}

/// 今日提要 and 顺延旁批: what is said, what is sent and how the answer is
/// read. Pure, so the same samples replay in tests.
enum AIDailyBrief {
    /// A day this close is named on the card.
    static let countdownWindow = 14
    /// Days this close are sent with the plans.
    static let inputCountdownWindow = 30
    static let maximumPlans = 20
    /// After a failed request the card waits this long before asking again.
    static let retryInterval: TimeInterval = 30 * 60
    /// A plan carried this many days gets a note under its row.
    static let carryNoteDays = 3

    // MARK: Facts

    /// Whole days from today to the event's next occurrence; today is 0.
    static func daysUntil(_ event: CountdownEvent, today: Date, calendar: Calendar) -> Int {
        let start = calendar.startOfDay(for: today)
        let target = calendar.startOfDay(for: event.occurrenceDate(relativeTo: start, calendar: calendar))
        return calendar.dateComponents([.day], from: start, to: target).day ?? -1
    }

    /// Days ahead within `days`, nearest first; among equals a 重点标记 day first.
    static func upcoming(_ countdowns: [CountdownEvent], today: Date, within days: Int,
                         calendar: Calendar) -> [(event: CountdownEvent, days: Int)] {
        countdowns.enumerated()
            .map { (offset: $0.offset, event: $0.element, days: daysUntil($0.element, today: today, calendar: calendar)) }
            .filter { (0...days).contains($0.days) }
            .sorted { lhs, rhs in
                if lhs.days != rhs.days { return lhs.days < rhs.days }
                if lhs.event.isPinned != rhs.event.isPinned { return lhs.event.isPinned }
                return lhs.offset < rhs.offset
            }
            .map { (event: $0.event, days: $0.days) }
    }

    static func daysLate(_ item: CheckInItem, today: Date, calendar: Calendar) -> Int {
        CarryOverSeal.daysLate(scheduledStart: item.scheduledStart, on: today, calendar: calendar) ?? 0
    }

    static func facts(pending: [CheckInItem], countdowns: [CountdownEvent], today: Date,
                      calendar: Calendar = .mojiISO) -> DailyBriefFacts {
        var facts = DailyBriefFacts()
        facts.count = pending.count
        facts.totalMinutes = pending.compactMap(\.plannedDurationMinutes).reduce(0, +)
        if let first = upcoming(countdowns, today: today, within: countdownWindow, calendar: calendar).first {
            facts.countdownTitle = first.event.title
            facts.countdownDays = first.days
        }
        facts.carried = pending.enumerated()
            .compactMap { offset, item -> (offset: Int, carried: DailyBriefFacts.Carried)? in
                let days = daysLate(item, today: today, calendar: calendar)
                guard days >= 1 else { return nil }
                return (offset, DailyBriefFacts.Carried(id: item.id, title: item.title, days: days,
                                                         isPlanned: item.status == .planned))
            }
            .sorted { $0.carried.days != $1.carried.days ? $0.carried.days > $1.carried.days : $0.offset < $1.offset }
            .map(\.carried)
        return facts
    }

    /// Two plans, or one with something worth saying about it.
    static func shouldShow(_ facts: DailyBriefFacts) -> Bool {
        facts.count >= 2 || (facts.count >= 1 && (!facts.carried.isEmpty || facts.hasNearCountdown))
    }

    static func shortTitle(_ title: String) -> String {
        let line = title.components(separatedBy: .newlines).joined(separator: " ")
            .trimmingCharacters(in: .whitespaces)
        return line.count > 12 ? String(line.prefix(11)) + "…" : line
    }

    /// 今天 3 件，约 1 小时 15 分钟；离「项目交付」还有 2 天；「归纳昨日待办」已顺延 3 天。
    static func factsLine(_ facts: DailyBriefFacts) -> String {
        var parts: [String] = []
        var count = "今天 \(facts.count) 件"
        if facts.totalMinutes > 0 { count += "，约 \(DurationText.full(minutes: facts.totalMinutes))" }
        parts.append(count)
        if let title = facts.countdownTitle {
            let name = shortTitle(title)
            switch facts.countdownDays {
            case 0: parts.append("「\(name)」就是今天")
            case 1: parts.append("明天就是「\(name)」")
            default: parts.append("离「\(name)」还有 \(facts.countdownDays) 天")
            }
        }
        if let longest = facts.carried.first {
            let name = shortTitle(longest.title)
            if facts.carried.count == 1 {
                parts.append("「\(name)」已顺延 \(longest.days) 天")
            } else {
                parts.append("「\(name)」等 \(facts.carried.count) 件已顺延，最久 \(longest.days) 天")
            }
        }
        return parts.joined(separator: "；") + "。"
    }

    // MARK: Asking

    static func instructions(now: Date, calendar: Calendar = .current) -> String {
        """
        \(AIDrafting.preface)
        任务：替用户排今天计划的先后，并说明为什么先做第一件。\(AIDrafting.todayLine(now, calendar: calendar))
        有具体钟点的按钟点先后；和临近日子有关的、顺延多天的优先；不要编造输入里没有的事。
        输出 JSON：{"order":[计划编号…],"reason":"…"}
        order 列出全部编号，第一个是建议先做的那件。reason 不超过 24 个字，说为什么先做它，可以提到离哪个日子还有几天、已经顺延几天；不要复述标题，不要夸奖，不要用感叹号。
        """
    }

    static func oneLine(_ text: String, limit: Int) -> String {
        String(text.components(separatedBy: .newlines).joined(separator: " ").prefix(limit))
    }

    /// [1] 标题（14:30，45 分钟，工作，已顺延 3 天）
    static func planLine(_ item: CheckInItem, number: Int, today: Date, calendar: Calendar = .mojiISO) -> String {
        var parts: [String] = []
        if item.effectiveScheduleKind == .exactTime {
            let time = calendar.dateComponents([.hour, .minute], from: item.scheduledStart)
            parts.append(String(format: "%02d:%02d", time.hour ?? 0, time.minute ?? 0))
        } else {
            parts.append(item.effectiveScheduleKind.displayName)
        }
        if let minutes = item.plannedDurationMinutes { parts.append(DurationText.full(minutes: minutes)) }
        parts.append(item.category.displayName)
        if item.status == .inProgress { parts.append("正在进行") }
        let late = daysLate(item, today: today, calendar: calendar)
        if late >= 1 { parts.append("已顺延 \(late) 天") }
        return "[\(number)] \(oneLine(item.title, limit: 60))（\(parts.joined(separator: "，"))）"
    }

    /// 最近的日子：项目交付（还有 2 天）、…
    static func daysLine(_ countdowns: [CountdownEvent], today: Date, calendar: Calendar = .mojiISO) -> String? {
        let days = upcoming(countdowns, today: today, within: inputCountdownWindow, calendar: calendar).prefix(3)
        guard !days.isEmpty else { return nil }
        return "最近的日子：" + days.map {
            "\(oneLine($0.event.title, limit: 60))（\($0.days == 0 ? "就是今天" : "还有 \($0.days) 天")）"
        }.joined(separator: "、")
    }

    /// The plans as numbered lines, then the days coming up. Nothing else is sent.
    static func input(pending: [CheckInItem], countdowns: [CountdownEvent], today: Date,
                      calendar: Calendar = .mojiISO) -> String {
        var lines = ["今天的计划："]
        for (index, item) in pending.prefix(maximumPlans).enumerated() {
            lines.append(planLine(item, number: index + 1, today: today, calendar: calendar))
        }
        if let days = daysLine(countdowns, today: today, calendar: calendar) { lines.append(days) }
        return lines.joined(separator: "\n")
    }

    /// Plan numbers 1…count in the order the model gave, the missing ones
    /// after them in their own order, and the reason for the first.
    static func order(from answer: String, count: Int) throws -> (order: [Int], reason: String?) {
        guard count > 0 else { throw AIRequestError.unreadableAnswer }
        let object = try AIDrafting.object(in: answer)
        var seen = Set<Int>()
        var order: [Int] = []
        for raw in object["order"] as? [Any] ?? [] {
            guard let number = AIDrafting.integer(raw), (1...count).contains(number),
                  seen.insert(number).inserted else { continue }
            order.append(number)
        }
        guard !order.isEmpty else { throw AIRequestError.unreadableAnswer }
        for number in 1...count where !seen.contains(number) { order.append(number) }
        var reason = AIDrafting.string(object["reason"])?
            .components(separatedBy: .newlines).joined(separator: " ")
        while let last = reason?.last, "。.！!".contains(last) { reason?.removeLast() }
        reason = reason?.trimmingCharacters(in: .whitespaces)
        if let text = reason { reason = text.isEmpty ? nil : String(text.prefix(40)) }
        return (order, reason)
    }

    // MARK: Showing

    /// 宜先做「整理发布说明」，离项目交付只剩两天，先把说明定稿。
    static func adviceLine(title: String, reason: String?) -> String {
        let name = title.components(separatedBy: .newlines).joined(separator: " ")
        if let reason, !reason.isEmpty { return "宜先做「\(name)」，\(reason)。" }
        return "宜先做「\(name)」。"
    }

    /// The plan to do first: the first one in the model's order that is still
    /// waiting. `isModelFirst` is false once the model's own first is gone,
    /// and then its reason no longer applies.
    static func firstPlan(order ids: [UUID], pending: [CheckInItem]) -> (item: CheckInItem, isModelFirst: Bool)? {
        for (offset, id) in ids.enumerated() {
            if let item = pending.first(where: { $0.id == id }) { return (item, offset == 0) }
        }
        return nil
    }

    /// `items` in the order of `ids`; plans not in it follow in their own order.
    static func ordered(_ items: [CheckInItem], by ids: [UUID]) -> [CheckInItem] {
        var rank: [UUID: Int] = [:]
        for (index, id) in ids.enumerated() where rank[id] == nil { rank[id] = index }
        return items.enumerated().sorted { lhs, rhs in
            switch (rank[lhs.element.id], rank[rhs.element.id]) {
            case let (left?, right?): return left < right
            case (.some, nil): return true
            case (nil, .some): return false
            case (nil, nil): return lhs.offset < rhs.offset
            }
        }.map(\.element)
    }

    /// 顺延三天了，拆小一点 ›
    static func carryNoteText(daysLate: Int) -> String {
        daysLate >= 100
            ? "顺延 \(daysLate) 天了，拆小一点 ›"
            : "顺延\(QuickEntryParser.chineseNumeral(daysLate))天了，拆小一点 ›"
    }
}

/// What 今日提要 and 顺延旁批 remember on this device: the day's answer, the
/// day the card was closed, the order the user put the list in, and the notes
/// waved away. Kept in the app's own defaults: not in the plan snapshot, not
/// in backups, not on the widget.
@MainActor
final class AIDailyState: ObservableObject {
    struct Brief: Codable, Equatable {
        var day: String
        var order: [String]
        var reason: String?
    }

    struct DayOrder: Codable, Equatable {
        var day: String
        var ids: [String]
    }

    enum Keys {
        static let brief = "moji.ai.brief.v1"
        static let attempt = "moji.ai.brief.attempt"
        static let dismissedDay = "moji.ai.brief.dismissedDay"
        static let todayOrder = "moji.ai.todayOrder.v1"
        static let carryDismissed = "moji.ai.carryNotes.dismissed.v1"
    }

    static let carryDismissedLimit = 300

    static let shared = AIDailyState(defaults: makeDefaults())

    @Published private(set) var brief: Brief?
    @Published private(set) var isLoading = false
    @Published private(set) var dismissedDay: String?
    @Published private(set) var dayOrder: DayOrder?
    @Published private(set) var dismissedCarryNotes: [String]

    private let defaults: UserDefaults

    init(defaults: UserDefaults) {
        self.defaults = defaults
        brief = defaults.data(forKey: Keys.brief).flatMap { try? JSONDecoder().decode(Brief.self, from: $0) }
        dismissedDay = defaults.string(forKey: Keys.dismissedDay)
        dayOrder = defaults.data(forKey: Keys.todayOrder).flatMap { try? JSONDecoder().decode(DayOrder.self, from: $0) }
        dismissedCarryNotes = defaults.stringArray(forKey: Keys.carryDismissed) ?? []
    }

    private static func makeDefaults() -> UserDefaults {
#if DEBUG
        // Screens photographed for QA start from nothing every launch.
        if AIQAFixture.isActive, let defaults = UserDefaults(suiteName: "moji.qa.ai.daily") {
            defaults.removePersistentDomain(forName: "moji.qa.ai.daily")
            return defaults
        }
#endif
        return .standard
    }

    static func dayKey(_ date: Date, calendar: Calendar = .current) -> String {
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", parts.year ?? 2000, parts.month ?? 1, parts.day ?? 1)
    }

    // MARK: Brief

    func brief(on day: Date) -> Brief? {
        brief?.day == Self.dayKey(day) ? brief : nil
    }

    func briefOrder(on day: Date) -> [UUID] {
        brief(on: day)?.order.compactMap(UUID.init(uuidString:)) ?? []
    }

    func isDismissed(on day: Date) -> Bool { dismissedDay == Self.dayKey(day) }

    func dismiss(on day: Date) {
        let key = Self.dayKey(day)
        dismissedDay = key
        defaults.set(key, forKey: Keys.dismissedDay)
    }

    var lastAttempt: Date? {
        let seconds = defaults.double(forKey: Keys.attempt)
        return seconds > 0 ? Date(timeIntervalSince1970: seconds) : nil
    }

    /// Asks the model once a day for the order of today's plans, while the
    /// card is on screen and nothing is cached for today. A failed request
    /// waits half an hour; nothing is shown for it.
    func refreshIfNeeded(pending: [CheckInItem], countdowns: [CountdownEvent], now: Date = Date(),
                         ai store: AISettingsStore? = nil) {
        // Default arguments are not on the main actor, so the shared store is picked here.
        let ai = store ?? .shared
        guard ai.isReady, ai.settings.dailyBrief, !isLoading, !pending.isEmpty,
              !isDismissed(on: now), brief(on: now) == nil,
              AIDailyBrief.shouldShow(AIDailyBrief.facts(pending: pending, countdowns: countdowns, today: now)) else { return }
        if let last = lastAttempt, now >= last, now.timeIntervalSince(last) < AIDailyBrief.retryInterval { return }
        defaults.set(now.timeIntervalSince1970, forKey: Keys.attempt)
        isLoading = true
        let plans = Array(pending.prefix(AIDailyBrief.maximumPlans))
        let day = Self.dayKey(now)
        Task {
            do {
                let result = try await AIAssistant(store: ai).dailyBrief(pending: plans, countdowns: countdowns, now: now)
                let ids = result.order.compactMap { plans.indices.contains($0 - 1) ? plans[$0 - 1].id.uuidString : nil }
                let value = Brief(day: day, order: ids, reason: result.reason)
                self.brief = value
                if let data = try? JSONEncoder().encode(value) { self.defaults.set(data, forKey: Keys.brief) }
            } catch {
                // Quiet on purpose: the card keeps its first line.
            }
            self.isLoading = false
        }
    }

    // MARK: Order of today's list

    func orderIDs(on day: Date) -> [UUID]? {
        guard let dayOrder, dayOrder.day == Self.dayKey(day) else { return nil }
        return dayOrder.ids.compactMap(UUID.init(uuidString:))
    }

    func applyOrder(_ ids: [UUID], on day: Date) {
        let value = DayOrder(day: Self.dayKey(day), ids: ids.map(\.uuidString))
        dayOrder = value
        if let data = try? JSONEncoder().encode(value) { defaults.set(data, forKey: Keys.todayOrder) }
    }

    func clearOrder() {
        dayOrder = nil
        defaults.removeObject(forKey: Keys.todayOrder)
    }

    // MARK: Carry-over notes

    func isCarryNoteDismissed(_ id: UUID) -> Bool { dismissedCarryNotes.contains(id.uuidString) }

    func dismissCarryNote(_ id: UUID) {
        guard !isCarryNoteDismissed(id) else { return }
        var list = dismissedCarryNotes + [id.uuidString]
        if list.count > Self.carryDismissedLimit { list.removeFirst(list.count - Self.carryDismissedLimit) }
        dismissedCarryNotes = list
        defaults.set(list, forKey: Keys.carryDismissed)
    }
}
