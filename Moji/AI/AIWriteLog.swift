import Foundation

/// One write Moji made from a draft the user accepted: the plans it added and
/// the plans it changed, kept so it can be taken back from 今天的整理 later in
/// the day, not only from the undo bar right after.
struct AIWriteEntry: Codable, Identifiable, Equatable {
    var id: UUID
    /// Seconds since 1970.
    var at: Double
    var summary: String
    var added: [CheckInItem]
    var before: [CheckInItem]
    var after: [CheckInItem]
    var undone: Bool

    var date: Date { Date(timeIntervalSince1970: at) }

    var undo: PlanDraftUndo {
        PlanDraftUndo(added: added, changed: PlanWorkflowUndo(before: before, after: after))
    }

    var total: Int { added.count + before.count }
}

/// The words for 留痕: where a plan came from and what one write did.
/// Pure, so both ends say exactly the same.
enum AIWriteText {
    /// 拆自「准备季度汇报」
    static func splitOrigin(_ title: String) -> String { "拆自「\(AIDailyBrief.shortTitle(title))」" }
    static let textOrigin = "从文字整理"
    static let imageOrigin = "从截图整理"
    static let shareOrigin = "从分享整理"
    static let nextWeekOrigin = "按上周记录拟"
    static func preparationOrigin(_ title: String) -> String { "为「\(AIDailyBrief.shortTitle(title))」拟" }

    /// The vermilion part of a row's small line: 墨记拆自「准备季度汇报」.
    static func mark(_ origin: String) -> String { "墨记\(origin)" }

    static func breakdown(count: Int, title: String, archived: Bool) -> String {
        "拆成 \(count) 步：「\(AIDailyBrief.shortTitle(title))」" + (archived ? "，原计划已归档" : "")
    }

    static func entrySplit(count: Int, original: String) -> String {
        "拆成 \(count) 条：「\(AIDailyBrief.shortTitle(original))」"
    }

    /// What a suggestion changed, in the order 日期、时间、时长、类型、重复、标题.
    static func changedFields(from old: CheckInItem, to new: CheckInItem,
                              calendar: Calendar = .current) -> [String] {
        var fields: [String] = []
        if !calendar.isDate(old.scheduledStart, inSameDayAs: new.scheduledStart) { fields.append("日期") }
        let oldTime = calendar.dateComponents([.hour, .minute], from: old.scheduledStart)
        let newTime = calendar.dateComponents([.hour, .minute], from: new.scheduledStart)
        if old.effectiveScheduleKind != new.effectiveScheduleKind
            || (new.effectiveScheduleKind == .exactTime && oldTime != newTime) { fields.append("时间") }
        if old.plannedDurationMinutes != new.plannedDurationMinutes { fields.append("时长") }
        if old.category != new.category { fields.append("类型") }
        if old.repeatRule != new.repeatRule { fields.append("重复") }
        if old.title != new.title { fields.append("标题") }
        return fields
    }

    /// 改了「交周报」的日期、时间
    static func entryChange(title: String, fields: [String]) -> String {
        "改了「\(AIDailyBrief.shortTitle(title))」的\(fields.joined(separator: "、"))"
    }

    static func imported(count: Int, origin: String) -> String {
        switch origin {
        case imageOrigin: return "从截图加入 \(count) 条"
        case shareOrigin: return "从分享加入 \(count) 条"
        default: return "从文字加入 \(count) 条"
        }
    }

    static func nextWeek(count: Int) -> String { "拟下周 \(count) 条" }

    static func preparation(count: Int, title: String) -> String {
        "为「\(AIDailyBrief.shortTitle(title))」拟 \(count) 条"
    }

    /// 收尾：顺延 1 件，明早 2 件，放下 1 件
    static func wrap(tomorrow: Int, morning: Int, dropped: Int) -> String {
        var parts: [String] = []
        if tomorrow > 0 { parts.append("顺延 \(tomorrow) 件") }
        if morning > 0 { parts.append("明早 \(morning) 件") }
        if dropped > 0 { parts.append("放下 \(dropped) 件") }
        return "收尾：" + parts.joined(separator: "，")
    }

    /// The same three sentences as the undo bar.
    static func undoResult(undone: Int, total: Int) -> String {
        if undone == total { return "已撤销" }
        if undone > 0 { return "已撤销 \(undone) 条；之后改过的保持不变" }
        return "这些计划之后都改过，保持不变"
    }
}

/// 今天的整理: every write Moji made, kept for seven days in the app's own
/// defaults (not the snapshot, not backups, not the widget).
@MainActor
final class AIWriteLog: ObservableObject {
    static let key = "moji.ai.writes.v1"
    static let keepDays = 7
    static let limit = 200

    static let shared = AIWriteLog(defaults: makeDefaults())

    @Published private(set) var entries: [AIWriteEntry]

    private let defaults: UserDefaults

    init(defaults: UserDefaults) {
        self.defaults = defaults
        let saved = defaults.data(forKey: Self.key).flatMap { try? JSONDecoder().decode([AIWriteEntry].self, from: $0) } ?? []
        entries = Self.pruned(saved, now: Date())
    }

    private static func makeDefaults() -> UserDefaults {
#if DEBUG
        if AIQAFixture.isActive, let defaults = UserDefaults(suiteName: "moji.qa.ai.writes") {
            defaults.removePersistentDomain(forName: "moji.qa.ai.writes")
            return defaults
        }
#endif
        return .standard
    }

    /// Entries from the last seven days, at most 200, oldest dropped first.
    static func pruned(_ entries: [AIWriteEntry], now: Date, calendar: Calendar = .current) -> [AIWriteEntry] {
        let start = calendar.date(byAdding: .day, value: -(keepDays - 1), to: calendar.startOfDay(for: now)) ?? now
        let kept = entries.filter { $0.date >= start }
        return kept.count > limit ? Array(kept.suffix(limit)) : kept
    }

    @discardableResult
    func record(summary: String, added: [CheckInItem], before: [CheckInItem], after: [CheckInItem],
                at date: Date = Date()) -> UUID {
        let entry = AIWriteEntry(id: UUID(), at: date.timeIntervalSince1970, summary: summary,
                                 added: added, before: before, after: after, undone: false)
        entries = Self.pruned(entries + [entry], now: date)
        save()
        return entry.id
    }

    func markUndone(_ id: UUID) {
        guard let index = entries.firstIndex(where: { $0.id == id }), !entries[index].undone else { return }
        entries[index].undone = true
        save()
    }

    func entry(_ id: UUID) -> AIWriteEntry? { entries.first { $0.id == id } }

    /// That day's writes, newest first.
    func entries(on day: Date, calendar: Calendar = .current) -> [AIWriteEntry] {
        entries.filter { calendar.isDate($0.date, inSameDayAs: day) }.sorted { $0.at > $1.at }
    }

    /// 今天墨记替你整理了 N 处: the writes still standing.
    func activeCount(on day: Date, calendar: Calendar = .current) -> Int {
        entries(on: day, calendar: calendar).filter { !$0.undone }.count
    }

#if DEBUG
    /// Three writes for the photographed screens, one of them taken back.
    func seedForQA(plans: [CheckInItem], now: Date = Date()) {
        let calendar = Calendar.current
        func at(_ hour: Int, _ minute: Int) -> Date {
            calendar.date(bySettingHour: hour, minute: minute, second: 0, of: now) ?? now
        }
        let first = plans.first.map { [$0] } ?? []
        entries = [
            AIWriteEntry(id: UUID(), at: at(9, 12).timeIntervalSince1970,
                         summary: AIWriteText.breakdown(count: 3, title: "准备季度汇报", archived: true),
                         added: [], before: [], after: [], undone: false),
            AIWriteEntry(id: UUID(), at: at(13, 40).timeIntervalSince1970,
                         summary: AIWriteText.entryChange(title: first.first?.title ?? "交周报", fields: ["日期", "时间"]),
                         added: [], before: first, after: first, undone: true),
            AIWriteEntry(id: UUID(), at: at(18, 5).timeIntervalSince1970,
                         summary: AIWriteText.imported(count: 2, origin: AIWriteText.textOrigin),
                         added: [], before: [], after: [], undone: false)
        ]
        save()
    }
#endif

    private func save() {
        if let data = try? JSONEncoder().encode(entries) { defaults.set(data, forKey: Self.key) }
    }
}
