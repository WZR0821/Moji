import Foundation
#if canImport(ActivityKit)
import ActivityKit
#endif

struct RecordCategory: RawRepresentable, Codable, CaseIterable, Identifiable, Hashable {
    let rawValue: String

    static let study = RecordCategory(validatedRawValue: "study")
    static let work = RecordCategory(validatedRawValue: "work")
    static let customSummary = RecordCategory(validatedRawValue: "_customSummary")
    static let allCases: [RecordCategory] = [.study, .work]

    var id: String { rawValue }

    init?(rawValue: String) {
        let normalized = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        if [
            Self.study.rawValue,
            Self.work.rawValue,
            Self.customSummary.rawValue
        ].contains(normalized) {
            self.rawValue = normalized
        } else {
            guard
                normalized.hasPrefix("custom:"),
                !normalized.dropFirst("custom:".count)
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                    .isEmpty
            else { return nil }
            self.rawValue = normalized
        }
    }

    private init(validatedRawValue: String) {
        rawValue = validatedRawValue
    }

    static func custom(named name: String) -> RecordCategory? {
        let cleanName = PlanCategoryLibrary.normalizedName(name)
        guard !cleanName.isEmpty else { return nil }
        return RecordCategory(validatedRawValue: "custom:\(cleanName)")
    }

    var isCustom: Bool {
        rawValue.hasPrefix("custom:")
    }

    var displayName: String {
        if self == .study { return "学习" }
        if self == .work { return "工作" }
        if self == .customSummary { return "其他" }
        return String(rawValue.dropFirst("custom:".count))
    }

    var symbolName: String {
        if self == .study { return "book.closed.fill" }
        if self == .work { return "briefcase.fill" }
        return "tag.fill"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let rawValue = try container.decode(String.self)
        guard let category = RecordCategory(rawValue: rawValue) else {
            throw DecodingError.dataCorruptedError(
                in: container,
                debugDescription: "Unknown record category: \(rawValue)"
            )
        }
        self = category
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(rawValue)
    }
}

enum PlanCategoryLibrary {
    static func normalizedName(_ name: String) -> String {
        String(
            name
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .prefix(24)
        )
    }

    static func customCategories(from rawJSON: String) -> [RecordCategory] {
        guard
            let data = rawJSON.data(using: .utf8),
            let names = try? JSONDecoder().decode([String].self, from: data)
        else { return [] }

        var seen = Set<String>()
        return names.compactMap { rawName in
            let name = normalizedName(rawName)
            let comparisonKey = name.folding(
                options: [.caseInsensitive, .diacriticInsensitive],
                locale: .current
            )
            guard
                !name.isEmpty,
                !["学习", "工作"].contains(name),
                seen.insert(comparisonKey).inserted
            else { return nil }
            return RecordCategory.custom(named: name)
        }
    }

    static func availableCategories(
        customJSON: String,
        including current: RecordCategory? = nil
    ) -> [RecordCategory] {
        var result = RecordCategory.allCases + customCategories(from: customJSON)
        if let current, current != .customSummary, !result.contains(current) {
            result.append(current)
        }
        return result
    }

    static func adding(
        name: String,
        to rawJSON: String
    ) -> (json: String, category: RecordCategory)? {
        guard let category = RecordCategory.custom(named: name) else { return nil }
        var categories = customCategories(from: rawJSON)
        guard
            !RecordCategory.allCases.contains(where: {
                $0.displayName.compare(
                    category.displayName,
                    options: [.caseInsensitive, .diacriticInsensitive]
                ) == .orderedSame
            }),
            !categories.contains(where: {
                $0.displayName.compare(
                    category.displayName,
                    options: [.caseInsensitive, .diacriticInsensitive]
                ) == .orderedSame
            })
        else { return nil }
        categories.append(category)
        return (encode(categories), category)
    }

    static func removing(
        _ category: RecordCategory,
        from rawJSON: String
    ) -> String {
        encode(customCategories(from: rawJSON).filter { $0 != category })
    }

    private static func encode(_ categories: [RecordCategory]) -> String {
        let names = categories.filter(\.isCustom).map(\.displayName)
        guard
            let data = try? JSONEncoder().encode(names),
            let string = String(data: data, encoding: .utf8)
        else { return "[]" }
        return string
    }
}

enum AppAppearanceMode: String, Codable, CaseIterable, Identifiable {
    case system
    case light
    case dark

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .system: return "跟随系统"
        case .light: return "浅色宣纸"
        case .dark: return "深色墨夜"
        }
    }
}

enum InkMotionLevel: String, Codable, CaseIterable, Identifiable {
    case full
    case reduced
    case off

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .full: return "完整"
        case .reduced: return "轻量"
        case .off: return "关闭"
        }
    }
}

enum CheckInKind: String, Codable, CaseIterable, Identifiable {
    case planned
    case completedLog

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .planned: return "计划要做"
        case .completedLog: return "记录已做"
        }
    }
}

enum CheckInStatus: String, Codable {
    case planned
    case inProgress
    case completed
    case skipped
}

enum ScheduleKind: String, Codable, CaseIterable, Identifiable {
    case allDay
    case morning
    case afternoon
    case evening
    case exactTime

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .allDay: return "全天"
        case .morning: return "上午"
        case .afternoon: return "下午"
        case .evening: return "晚上"
        case .exactTime: return "具体时间"
        }
    }

    var symbolName: String {
        switch self {
        case .allDay: return "sun.max.fill"
        case .morning: return "sunrise.fill"
        case .afternoon: return "sun.max.fill"
        case .evening: return "moon.stars.fill"
        case .exactTime: return "clock.fill"
        }
    }

    var sortOrder: Int {
        switch self {
        case .allDay: return 0
        case .morning: return 1
        case .afternoon: return 2
        case .evening: return 3
        case .exactTime: return 4
        }
    }

    var representativeHour: Int? {
        switch self {
        case .allDay: return 0
        case .morning: return 9
        case .afternoon: return 14
        case .evening: return 19
        case .exactTime: return nil
        }
    }
}

enum PlanRepeatRule: String, Codable, CaseIterable, Identifiable {
    case never
    case daily
    case weekdays
    case weekly
    case customWeekdays
    case monthly

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .never: return "不重复"
        case .daily: return "每天"
        case .weekdays: return "每个工作日"
        case .weekly: return "每周"
        case .customWeekdays: return "自选星期"
        case .monthly: return "每月"
        }
    }
}

struct CheckInItem: Codable, Identifiable, Equatable {
    var id: UUID
    var title: String
    var category: RecordCategory
    var kind: CheckInKind
    var scheduledStart: Date
    var plannedMinutes: Int
    var note: String
    var status: CheckInStatus
    var actualStartDate: Date?
    var actualEndDate: Date?
    var completedAt: Date?
    var calendarSyncEnabled: Bool
    var calendarEventIdentifier: String?
    var createdAt: Date
    var scheduleKind: ScheduleKind?
    var detailsConfigured: Bool?
    var repeatRule: PlanRepeatRule?
    var repeatWeekdays: [Int]?
    var seriesID: UUID?
    var reminderMinutesBefore: Int?
    var generatedFromOccurrenceID: UUID?
    var plannedDurationEnabled: Bool?

    init(
        id: UUID = UUID(),
        title: String,
        category: RecordCategory,
        kind: CheckInKind = .planned,
        scheduledStart: Date,
        plannedMinutes: Int,
        note: String = "",
        status: CheckInStatus = .planned,
        actualStartDate: Date? = nil,
        actualEndDate: Date? = nil,
        completedAt: Date? = nil,
        calendarSyncEnabled: Bool = false,
        calendarEventIdentifier: String? = nil,
        createdAt: Date = Date(),
        scheduleKind: ScheduleKind = .exactTime,
        detailsConfigured: Bool = true,
        repeatRule: PlanRepeatRule = .never,
        repeatWeekdays: [Int] = [],
        seriesID: UUID? = nil,
        reminderMinutesBefore: Int? = nil,
        generatedFromOccurrenceID: UUID? = nil,
        plannedDurationEnabled: Bool? = nil
    ) {
        self.id = id
        self.title = title
        self.category = category
        self.kind = kind
        self.scheduledStart = scheduledStart
        self.plannedMinutes = max(1, plannedMinutes)
        self.note = note
        self.status = status
        self.actualStartDate = actualStartDate
        self.actualEndDate = actualEndDate
        self.completedAt = completedAt
        self.calendarSyncEnabled = calendarSyncEnabled
        self.calendarEventIdentifier = calendarEventIdentifier
        self.createdAt = createdAt
        self.scheduleKind = scheduleKind
        self.detailsConfigured = detailsConfigured
        self.repeatRule = repeatRule
        self.repeatWeekdays = repeatWeekdays
        self.seriesID = seriesID
        self.reminderMinutesBefore = reminderMinutesBefore
        self.generatedFromOccurrenceID = generatedFromOccurrenceID
        self.plannedDurationEnabled = plannedDurationEnabled
    }

    var scheduledEnd: Date {
        scheduledStart.addingTimeInterval(
            TimeInterval((plannedDurationMinutes ?? 30) * 60)
        )
    }

    var isCompleted: Bool {
        status == .completed
    }

    var effectiveScheduleKind: ScheduleKind {
        scheduleKind ?? .exactTime
    }

    var hasConfiguredDetails: Bool {
        detailsConfigured ?? true
    }

    var hasPlannedDuration: Bool {
        guard kind == .planned else { return false }
        if let plannedDurationEnabled {
            return plannedDurationEnabled
        }
        // Before schema v8 every plan was forced to carry a duration. Preserve
        // intentional timed plans, but stop treating legacy day-part plans as
        // if the user had explicitly estimated them.
        return hasConfiguredDetails && effectiveScheduleKind == .exactTime
    }

    var plannedDurationMinutes: Int? {
        hasPlannedDuration ? max(1, plannedMinutes) : nil
    }

    var effectiveRepeatRule: PlanRepeatRule {
        repeatRule ?? .never
    }

    var effectiveRepeatWeekdays: [Int] {
        let valid = Set((repeatWeekdays ?? []).filter { (1...7).contains($0) })
        return valid.sorted()
    }

    var recurrenceSeriesID: UUID {
        seriesID ?? id
    }

    var notificationIdentifier: String {
        "minuteplan.plan.\(id.uuidString)"
    }

    var repeatSummaryText: String {
        guard effectiveRepeatRule == .customWeekdays else {
            return effectiveRepeatRule.displayName
        }
        let names = effectiveRepeatWeekdays.map(Self.shortWeekdayName)
        return names.isEmpty ? PlanRepeatRule.customWeekdays.displayName : names.joined(separator: "、")
    }

    var reminderSummaryText: String {
        guard let reminderMinutesBefore else { return "不提醒" }
        switch reminderMinutesBefore {
        case 0: return "开始时"
        case 1..<60: return "提前 \(reminderMinutesBefore) 分钟"
        case 60: return "提前 1 小时"
        case 1_440: return "提前 1 天"
        default:
            if reminderMinutesBefore.isMultiple(of: 60) {
                return "提前 \(reminderMinutesBefore / 60) 小时"
            }
            return "提前 \(reminderMinutesBefore) 分钟"
        }
    }

    /// The one rule for "does this plan belong on the checklist for `date`".
    ///
    /// A plan scheduled for that day always does. One scheduled later does not —
    /// future plans live on the calendar, and a repeating plan's next occurrence
    /// must stay out of sight until its own day. One scheduled earlier is
    /// overdue: it only follows the user forward when carry-over is on, and this
    /// is asked of unfinished plans only, since a finished plan belongs to the
    /// day it was finished on.
    func isVisibleInChecklist(
        on date: Date = Date(),
        carriesOverUnfinished: Bool = PlanSettingsKeys.carriesOverUnfinishedPlans,
        calendar: Calendar = .current
    ) -> Bool {
        if status == .inProgress { return true }
        let plannedDay = calendar.startOfDay(for: scheduledStart)
        let targetDay = calendar.startOfDay(for: date)
        if plannedDay == targetDay { return true }
        if plannedDay > targetDay { return false }
        return carriesOverUnfinished && !isCompleted && status != .skipped
    }

    var scheduleText: String {
        switch effectiveScheduleKind {
        case .exactTime:
            return scheduledStart.formatted(date: .omitted, time: .shortened)
        default:
            return effectiveScheduleKind.displayName
        }
    }

    var timingSummaryText: String {
        guard let plannedDurationMinutes else { return scheduleText }
        return "\(scheduleText) · 预计 \(DurationText.full(minutes: plannedDurationMinutes))"
    }

    func nextOccurrenceStart(
        after referenceDate: Date? = nil,
        calendar inputCalendar: Calendar = .current
    ) -> Date? {
        var calendar = inputCalendar
        calendar.timeZone = .current
        let reference = max(scheduledStart, referenceDate ?? scheduledStart)

        switch effectiveRepeatRule {
        case .never:
            return nil
        case .daily:
            return nextFixedDayInterval(
                1,
                after: reference,
                calendar: calendar
            )
        case .weekdays:
            return nextMatchingDay(
                after: reference,
                allowedWeekdays: Set([2, 3, 4, 5, 6]),
                calendar: calendar
            )
        case .weekly:
            return nextFixedDayInterval(
                7,
                after: reference,
                calendar: calendar
            )
        case .customWeekdays:
            let selected = Set(effectiveRepeatWeekdays)
            guard !selected.isEmpty else {
                return nextFixedDayInterval(
                    7,
                    after: reference,
                    calendar: calendar
                )
            }
            return nextMatchingDay(
                after: reference,
                allowedWeekdays: selected,
                calendar: calendar
            )
        case .monthly:
            let originalDay = calendar.component(.day, from: scheduledStart)
            let startMonth = calendar.date(
                from: calendar.dateComponents([.year, .month], from: scheduledStart)
            ) ?? scheduledStart
            let referenceMonth = calendar.date(
                from: calendar.dateComponents([.year, .month], from: reference)
            ) ?? reference
            let monthDistance = max(
                0,
                calendar.dateComponents([.month], from: startMonth, to: referenceMonth).month ?? 0
            )
            let time = calendar.dateComponents([.hour, .minute, .second], from: scheduledStart)

            for offset in max(1, monthDistance)...(monthDistance + 2) {
                guard let month = calendar.date(
                    byAdding: .month,
                    value: offset,
                    to: startMonth
                ) else { continue }
                var components = calendar.dateComponents([.year, .month], from: month)
                let monthRange = calendar.range(of: .day, in: .month, for: month)
                components.day = min(originalDay, monthRange?.count ?? originalDay)
                components.hour = time.hour
                components.minute = time.minute
                components.second = time.second
                guard let candidate = calendar.date(from: components) else { continue }
                if calendar.startOfDay(for: candidate) > calendar.startOfDay(for: reference) {
                    return candidate
                }
            }
            return nil
        }
    }

    func recurringSuccessor(calendar: Calendar = .current) -> CheckInItem? {
        guard
            kind == .planned,
            let nextStart = nextOccurrenceStart(
                after: completedAt,
                calendar: calendar
            )
        else { return nil }

        return CheckInItem(
            title: title,
            category: category,
            kind: kind,
            scheduledStart: nextStart,
            plannedMinutes: plannedMinutes,
            note: note,
            status: .planned,
            calendarSyncEnabled: calendarSyncEnabled,
            calendarEventIdentifier: calendarEventIdentifier,
            createdAt: Date(),
            scheduleKind: effectiveScheduleKind,
            detailsConfigured: hasConfiguredDetails,
            repeatRule: effectiveRepeatRule,
            repeatWeekdays: effectiveRepeatWeekdays,
            seriesID: recurrenceSeriesID,
            reminderMinutesBefore: reminderMinutesBefore,
            generatedFromOccurrenceID: id,
            plannedDurationEnabled: hasPlannedDuration
        )
    }

    private func nextFixedDayInterval(
        _ interval: Int,
        after reference: Date,
        calendar: Calendar
    ) -> Date? {
        let startDay = calendar.startOfDay(for: scheduledStart)
        let referenceDay = calendar.startOfDay(for: reference)
        let elapsedDays = max(
            0,
            calendar.dateComponents([.day], from: startDay, to: referenceDay).day ?? 0
        )
        var intervals = max(1, elapsedDays / interval)
        let candidate = calendar.date(
            byAdding: .day,
            value: intervals * interval,
            to: scheduledStart
        )
        if
            let candidate,
            calendar.startOfDay(for: candidate) <= calendar.startOfDay(for: reference)
        {
            intervals += 1
            return calendar.date(
                byAdding: .day,
                value: intervals * interval,
                to: scheduledStart
            )
        }
        return candidate
    }

    private func nextMatchingDay(
        after date: Date,
        allowedWeekdays: Set<Int>,
        calendar: Calendar
    ) -> Date? {
        let time = calendar.dateComponents([.hour, .minute, .second], from: scheduledStart)
        let startDay = calendar.startOfDay(for: date)
        for offset in 1...14 {
            guard let candidateDay = calendar.date(
                byAdding: .day,
                value: offset,
                to: startDay
            ) else {
                continue
            }
            var components = calendar.dateComponents([.year, .month, .day], from: candidateDay)
            components.hour = time.hour
            components.minute = time.minute
            components.second = time.second
            guard let candidate = calendar.date(from: components) else { continue }
            if allowedWeekdays.contains(calendar.component(.weekday, from: candidate)) {
                return candidate
            }
        }
        return nil
    }

    private static func shortWeekdayName(_ weekday: Int) -> String {
        switch weekday {
        case 1: return "周日"
        case 2: return "周一"
        case 3: return "周二"
        case 4: return "周三"
        case 5: return "周四"
        case 6: return "周五"
        case 7: return "周六"
        default: return ""
        }
    }
}

struct TimeRecord: Codable, Identifiable, Equatable {
    var id: UUID
    var title: String
    var category: RecordCategory
    var startDate: Date
    var endDate: Date
    var note: String
    var checkInItemID: UUID?
    var scheduleKind: ScheduleKind?

    init(
        id: UUID = UUID(),
        title: String,
        category: RecordCategory,
        startDate: Date,
        endDate: Date,
        note: String = "",
        checkInItemID: UUID? = nil,
        scheduleKind: ScheduleKind? = .exactTime
    ) {
        self.id = id
        self.title = title
        self.category = category
        self.startDate = startDate
        self.endDate = endDate
        self.note = note
        self.checkInItemID = checkInItemID
        self.scheduleKind = scheduleKind
    }

    var effectiveScheduleKind: ScheduleKind {
        scheduleKind ?? .exactTime
    }

    var hasPreciseTime: Bool {
        effectiveScheduleKind == .exactTime
    }

    var duration: TimeInterval {
        guard hasPreciseTime else { return 0 }
        return max(0, endDate.timeIntervalSince(startDate))
    }

    var durationMinutes: Int {
        guard duration > 0 else { return 0 }
        return max(1, Int(ceil(duration / 60)))
    }

    var timingSummaryText: String {
        guard hasPreciseTime else { return effectiveScheduleKind.displayName }
        return "\(startDate.formatted(date: .omitted, time: .shortened)) – \(endDate.formatted(date: .omitted, time: .shortened))"
    }
}

struct ActiveSession: Codable, Identifiable, Equatable {
    var id: UUID
    var title: String
    var category: RecordCategory
    var startedAt: Date
    var checkInItemID: UUID?
    var plannedDurationMinutes: Int?

    init(
        id: UUID = UUID(),
        title: String,
        category: RecordCategory,
        startedAt: Date = Date(),
        checkInItemID: UUID? = nil,
        plannedDurationMinutes: Int? = nil
    ) {
        self.id = id
        self.title = title
        self.category = category
        self.startedAt = startedAt
        self.checkInItemID = checkInItemID
        self.plannedDurationMinutes = plannedDurationMinutes
    }

    var targetDate: Date? {
        guard let plannedDurationMinutes else { return nil }
        return startedAt.addingTimeInterval(TimeInterval(plannedDurationMinutes * 60))
    }
}

#if canImport(ActivityKit)
struct PomodoroActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var title: String
        var phaseName: String
        var targetDate: Date?
        var remainingSeconds: Int
        var isRunning: Bool
    }

    var sessionID: UUID
}
#endif

enum CountdownRepeatRule: String, Codable, CaseIterable, Identifiable {
    case never
    case yearly
    case monthly
    case weekly

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .never: return "不重复"
        case .yearly: return "每年"
        case .monthly: return "每月"
        case .weekly: return "每周"
        }
    }
}

struct CountdownEvent: Codable, Identifiable, Equatable {
    var id: UUID
    var title: String
    var targetDate: Date
    var symbolName: String
    var colorName: String
    var isPinned: Bool
    var createdAt: Date
    var repeatRule: CountdownRepeatRule?
    var includesToday: Bool?
    var calendarSyncEnabled: Bool?
    var calendarEventIdentifier: String?
    var sortOrder: Int?

    init(
        id: UUID = UUID(),
        title: String,
        targetDate: Date,
        symbolName: String = "flag.fill",
        colorName: String = "ink",
        isPinned: Bool = false,
        createdAt: Date = Date(),
        repeatRule: CountdownRepeatRule = .never,
        includesToday: Bool = false,
        calendarSyncEnabled: Bool = false,
        calendarEventIdentifier: String? = nil,
        sortOrder: Int? = nil
    ) {
        self.id = id
        self.title = title
        self.targetDate = targetDate
        self.symbolName = symbolName
        self.colorName = colorName
        self.isPinned = isPinned
        self.createdAt = createdAt
        self.repeatRule = repeatRule
        self.includesToday = includesToday
        self.calendarSyncEnabled = calendarSyncEnabled
        self.calendarEventIdentifier = calendarEventIdentifier
        self.sortOrder = sortOrder
    }

    var effectiveRepeatRule: CountdownRepeatRule {
        repeatRule ?? .never
    }

    var countsToday: Bool {
        includesToday ?? false
    }

    var isSyncedToCalendar: Bool {
        calendarEventIdentifier != nil
    }

    var effectiveSortOrder: Int {
        sortOrder ?? Int.max
    }

    func isUrgent(relativeTo date: Date = Date(), calendar: Calendar = .current) -> Bool {
        let count = CountdownDayCalculator.count(
            to: occurrenceDate(relativeTo: date, calendar: calendar),
            from: date,
            calendar: calendar
        )
        return (0..<3).contains(count.signedDays)
    }

    var dateSummaryText: String {
        switch effectiveRepeatRule {
        case .never:
            return targetDate.formatted(date: .abbreviated, time: .omitted)
        case .yearly:
            return "\(targetDate.formatted(.dateTime.month().day())) · 每年"
        case .monthly:
            let day = Calendar.current.component(.day, from: targetDate)
            return "\(day) 日 · 每月"
        case .weekly:
            return "\(targetDate.formatted(.dateTime.weekday(.wide))) · 每周"
        }
    }

    /// Whole days from the original date up to today, or nil if it hasn't
    /// happened yet.
    func elapsedDays(relativeTo date: Date = Date(), calendar: Calendar = .current) -> Int? {
        let origin = calendar.startOfDay(for: targetDate)
        let today = calendar.startOfDay(for: date)
        guard origin < today else { return nil }
        return calendar.dateComponents([.day], from: origin, to: today).day
    }

    /// "已经 6 年 27 天" — how long since the date itself.
    ///
    /// A repeating anniversary otherwise only ever shows the countdown to its
    /// next occurrence, which loses the thing people actually keep these for:
    /// how long it has been.
    func elapsedSummaryText(
        relativeTo date: Date = Date(),
        calendar: Calendar = .current
    ) -> String? {
        guard let totalDays = elapsedDays(relativeTo: date, calendar: calendar) else {
            return nil
        }
        let origin = calendar.startOfDay(for: targetDate)
        let today = calendar.startOfDay(for: date)
        let parts = calendar.dateComponents([.year, .month, .day], from: origin, to: today)
        let years = parts.year ?? 0
        let months = parts.month ?? 0
        let days = parts.day ?? 0

        if years >= 1 {
            var text = "已经 \(years) 年"
            if months > 0 { text += " \(months) 个月" }
            if years < 3, days > 0 { text += " \(days) 天" }
            return "\(text) · 共 \(totalDays) 天"
        }
        if months >= 1 {
            return days > 0
                ? "已经 \(months) 个月 \(days) 天 · 共 \(totalDays) 天"
                : "已经 \(months) 个月 · 共 \(totalDays) 天"
        }
        return "已经 \(totalDays) 天"
    }

    /// The line between the 倒数日 page and the 纪念日 page, and between the two
    /// countdown widgets. A repeating date always has a next occurrence, so it
    /// never counts as past — it belongs with the dates still to come.
    func hasPassed(relativeTo date: Date = Date(), calendar: Calendar = .current) -> Bool {
        CountdownDayCalculator.count(
            to: occurrenceDate(relativeTo: date, calendar: calendar),
            from: date,
            includesToday: countsToday,
            calendar: calendar
        ).isPast
    }

    func occurrenceDate(relativeTo date: Date = Date(), calendar: Calendar = .current) -> Date {
        let today = calendar.startOfDay(for: date)
        let original = calendar.startOfDay(for: targetDate)

        switch effectiveRepeatRule {
        case .never:
            return original
        case .weekly:
            let weekday = calendar.component(.weekday, from: original)
            let todayWeekday = calendar.component(.weekday, from: today)
            let daysAhead = (weekday - todayWeekday + 7) % 7
            return calendar.date(byAdding: .day, value: daysAhead, to: today) ?? original
        case .monthly:
            let targetDay = calendar.component(.day, from: original)
            for offset in 0..<24 {
                guard
                    let month = calendar.date(byAdding: .month, value: offset, to: today),
                    let candidate = calendar.date(
                        from: DateComponents(
                            year: calendar.component(.year, from: month),
                            month: calendar.component(.month, from: month),
                            day: targetDay
                        )
                    )
                else { continue }
                guard calendar.component(.day, from: candidate) == targetDay else { continue }
                let normalized = calendar.startOfDay(for: candidate)
                if normalized >= today { return normalized }
            }
            return original
        case .yearly:
            let month = calendar.component(.month, from: original)
            let day = calendar.component(.day, from: original)
            let currentYear = calendar.component(.year, from: today)
            for year in currentYear...(currentYear + 16) {
                guard let candidate = calendar.date(
                    from: DateComponents(year: year, month: month, day: day)
                ) else { continue }
                guard
                    calendar.component(.month, from: candidate) == month,
                    calendar.component(.day, from: candidate) == day
                else { continue }
                let normalized = calendar.startOfDay(for: candidate)
                if normalized >= today { return normalized }
            }
            return original
        }
    }
}

enum MemoMode: String, Codable, CaseIterable {
    case note
    case checklist
}

struct MemoChecklistItem: Codable, Identifiable, Equatable {
    var id: UUID
    var text: String
    var isCompleted: Bool

    init(
        id: UUID = UUID(),
        text: String = "",
        isCompleted: Bool = false
    ) {
        self.id = id
        self.text = text
        self.isCompleted = isCompleted
    }
}

struct MemoChecklistCaret: Equatable {
    let itemID: UUID
    let utf16Offset: Int
}

enum MemoChecklistReturnOutcome: Equatable {
    case focus(MemoChecklistCaret)
    case endEditing
    case unchanged
}

enum MemoChecklistDeleteOutcome: Equatable {
    case handled(MemoChecklistCaret)
    case system
}

/// Keeps checklist keyboard behavior predictable and independently testable.
/// UIKit selections use UTF-16 offsets, so all split and merge operations do too.
enum MemoChecklistEditingEngine {
    static func handleReturn(
        items: inout [MemoChecklistItem],
        itemID: UUID,
        selection: NSRange
    ) -> MemoChecklistReturnOutcome {
        guard let index = items.firstIndex(where: { $0.id == itemID }) else {
            return .unchanged
        }

        let source = items[index].text as NSString
        let location = min(max(selection.location, 0), source.length)
        let length = min(max(selection.length, 0), source.length - location)

        // As in Notes, Return on a blank checklist row ends the list instead of
        // accumulating another empty item.
        guard !items[index].text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return .endEditing
        }

        let prefix = source.substring(to: location)
        let suffix = source.substring(from: location + length)
        items[index].text = prefix

        let newItem = MemoChecklistItem(text: suffix)
        items.insert(newItem, at: index + 1)
        return .focus(MemoChecklistCaret(itemID: newItem.id, utf16Offset: 0))
    }

    static func handleBackspaceAtStart(
        items: inout [MemoChecklistItem],
        itemID: UUID
    ) -> MemoChecklistDeleteOutcome {
        guard let index = items.firstIndex(where: { $0.id == itemID }) else {
            return .system
        }

        if index > 0 {
            let previousIndex = index - 1
            let caretOffset = (items[previousIndex].text as NSString).length
            items[previousIndex].text += items[index].text
            let previousID = items[previousIndex].id
            items.remove(at: index)
            return .handled(
                MemoChecklistCaret(itemID: previousID, utf16Offset: caretOffset)
            )
        }

        if items[index].text.isEmpty, items.count > 1 {
            items.remove(at: index)
            return .handled(
                MemoChecklistCaret(itemID: items[0].id, utf16Offset: 0)
            )
        }

        return .system
    }

    @discardableResult
    static func removeItem(
        items: inout [MemoChecklistItem],
        itemID: UUID
    ) -> MemoChecklistCaret? {
        guard let index = items.firstIndex(where: { $0.id == itemID }) else {
            return nil
        }

        items.remove(at: index)
        if items.isEmpty {
            let replacement = MemoChecklistItem()
            items = [replacement]
            return MemoChecklistCaret(itemID: replacement.id, utf16Offset: 0)
        }

        let focusIndex = min(index, items.count - 1)
        let offset = focusIndex < index ? (items[focusIndex].text as NSString).length : 0
        return MemoChecklistCaret(itemID: items[focusIndex].id, utf16Offset: offset)
    }
}

struct MemoItem: Codable, Identifiable, Equatable {
    var id: UUID
    var title: String
    var content: String
    var mode: MemoMode
    var checklistItems: [MemoChecklistItem]
    var isPinned: Bool
    var createdAt: Date
    var updatedAt: Date

    init(
        id: UUID = UUID(),
        title: String = "",
        content: String = "",
        mode: MemoMode = .note,
        checklistItems: [MemoChecklistItem] = [],
        isPinned: Bool = false,
        createdAt: Date = Date(),
        updatedAt: Date = Date()
    ) {
        self.id = id
        self.title = title
        self.content = content
        self.mode = mode
        self.checklistItems = checklistItems
        self.isPinned = isPinned
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    private enum CodingKeys: String, CodingKey {
        case id
        case title
        case content
        case mode
        case checklistItems
        case isPinned
        case createdAt
        case updatedAt
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decodeIfPresent(UUID.self, forKey: .id) ?? UUID()
        title = try container.decodeIfPresent(String.self, forKey: .title) ?? ""
        content = try container.decodeIfPresent(String.self, forKey: .content) ?? ""
        checklistItems = try container.decodeIfPresent(
            [MemoChecklistItem].self,
            forKey: .checklistItems
        ) ?? []
        mode = try container.decodeIfPresent(MemoMode.self, forKey: .mode)
            ?? (checklistItems.isEmpty ? .note : .checklist)
        isPinned = try container.decodeIfPresent(Bool.self, forKey: .isPinned) ?? false
        createdAt = try container.decodeIfPresent(Date.self, forKey: .createdAt) ?? Date()
        updatedAt = try container.decodeIfPresent(Date.self, forKey: .updatedAt) ?? createdAt
    }

    var displayTitle: String {
        let cleanTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard cleanTitle.isEmpty else { return cleanTitle }

        let firstLine: String
        if mode == .checklist {
            firstLine = checklistItems
                .lazy
                .map { $0.text.trimmingCharacters(in: .whitespacesAndNewlines) }
                .first(where: { !$0.isEmpty }) ?? ""
        } else {
            firstLine = content
                .split(whereSeparator: \.isNewline)
                .first
                .map(String.init)?
                .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        }
        return firstLine.isEmpty ? "无标题备忘" : String(firstLine.prefix(32))
    }
}

struct PlanSnapshot: Codable, Equatable {
    static let currentSchemaVersion = 12

    var schemaVersion: Int
    var records: [TimeRecord]
    var checkInItems: [CheckInItem]
    var countdowns: [CountdownEvent]
    var memos: [MemoItem]
    var activeSession: ActiveSession?
    var lastUpdated: Date

    init(
        schemaVersion: Int = PlanSnapshot.currentSchemaVersion,
        records: [TimeRecord] = [],
        checkInItems: [CheckInItem] = [],
        countdowns: [CountdownEvent] = [],
        memos: [MemoItem] = [],
        activeSession: ActiveSession? = nil,
        lastUpdated: Date = Date()
    ) {
        self.schemaVersion = schemaVersion
        self.records = records
        self.checkInItems = checkInItems
        self.countdowns = countdowns
        self.memos = memos
        self.activeSession = activeSession
        self.lastUpdated = lastUpdated
    }

    static let empty = PlanSnapshot()

    private enum CodingKeys: String, CodingKey {
        case schemaVersion
        case records
        case checkInItems
        case countdowns
        case memos
        case activeSession
        case lastUpdated
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        schemaVersion = try container.decodeIfPresent(Int.self, forKey: .schemaVersion) ?? 1
        records = try container.decodeIfPresent([TimeRecord].self, forKey: .records) ?? []
        checkInItems = try container.decodeIfPresent([CheckInItem].self, forKey: .checkInItems) ?? []
        countdowns = try container.decodeIfPresent([CountdownEvent].self, forKey: .countdowns) ?? []
        memos = try container.decodeIfPresent([MemoItem].self, forKey: .memos) ?? []
        activeSession = try container.decodeIfPresent(ActiveSession.self, forKey: .activeSession)
        lastUpdated = try container.decodeIfPresent(Date.self, forKey: .lastUpdated) ?? Date()
    }

    mutating func normalize() {
        schemaVersion = Self.currentSchemaVersion
        records = records
            .filter {
                !$0.hasPreciseTime || $0.endDate > $0.startDate
            }
            .sorted { $0.startDate > $1.startDate }

        checkInItems = checkInItems
            .map { item in
                var normalized = item
                normalized.plannedMinutes = max(1, normalized.plannedMinutes)
                return normalized
            }
            .sorted {
                let lhsDay = Calendar.current.startOfDay(for: $0.scheduledStart)
                let rhsDay = Calendar.current.startOfDay(for: $1.scheduledStart)
                if lhsDay != rhsDay {
                    return lhsDay < rhsDay
                }
                if $0.scheduledStart != $1.scheduledStart {
                    return $0.scheduledStart < $1.scheduledStart
                }
                return $0.createdAt < $1.createdAt
            }

        countdowns = countdowns
            .enumerated()
            .map { offset, event in
                var normalized = event
                normalized.targetDate = Calendar.current.startOfDay(for: event.targetDate)
                normalized.sortOrder = event.sortOrder ?? offset
                return normalized
            }
            .sorted {
                if $0.effectiveSortOrder != $1.effectiveSortOrder {
                    return $0.effectiveSortOrder < $1.effectiveSortOrder
                }
                return $0.createdAt < $1.createdAt
            }
            .enumerated()
            .map { offset, event in
                var normalized = event
                normalized.sortOrder = offset
                return normalized
            }

        memos = memos.sorted {
            if $0.isPinned != $1.isPinned {
                return $0.isPinned && !$1.isPinned
            }
            if $0.updatedAt != $1.updatedAt {
                return $0.updatedAt > $1.updatedAt
            }
            return $0.createdAt > $1.createdAt
        }
        lastUpdated = Date()
    }
}

/// How a 倒数日 / 纪念日 list is ordered. `manual` keeps the order the user
/// dragged into place; `date` ignores it and puts the nearest date first.
enum CountdownSortMode: String, CaseIterable, Identifiable, Codable {
    case manual
    case date

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .manual: return "手动排序"
        case .date: return "按时间排序"
        }
    }

    var symbolName: String {
        switch self {
        case .manual: return "hand.draw"
        case .date: return "calendar.badge.clock"
        }
    }
}

enum CountdownOrdering {
    /// The order the user dragged into place. 重点标记 deliberately does not
    /// float to the top here — a hand-arranged list that silently rearranged
    /// itself would make dragging a marked row impossible.
    static func sorted(_ events: [CountdownEvent]) -> [CountdownEvent] {
        events.sorted {
            if $0.effectiveSortOrder != $1.effectiveSortOrder {
                return $0.effectiveSortOrder < $1.effectiveSortOrder
            }
            return $0.createdAt < $1.createdAt
        }
    }

    /// Days between now and the event's own occurrence, unsigned. Both lists
    /// read "closest to today first" from it: the 倒数 list counts forward, the
    /// 纪念 list counts back, and neither mixes the two directions.
    static func dayDistance(
        for event: CountdownEvent,
        relativeTo date: Date = Date(),
        calendar: Calendar = .current
    ) -> Int {
        CountdownDayCalculator.count(
            to: event.occurrenceDate(relativeTo: date, calendar: calendar),
            from: date,
            includesToday: event.countsToday,
            calendar: calendar
        ).magnitude
    }

    static func sorted(
        _ events: [CountdownEvent],
        mode: CountdownSortMode,
        relativeTo date: Date = Date(),
        calendar: Calendar = .current
    ) -> [CountdownEvent] {
        guard mode == .date else { return sorted(events) }
        return events.sorted { lhs, rhs in
            if lhs.isPinned != rhs.isPinned {
                return lhs.isPinned && !rhs.isPinned
            }
            let lhsDistance = dayDistance(for: lhs, relativeTo: date, calendar: calendar)
            let rhsDistance = dayDistance(for: rhs, relativeTo: date, calendar: calendar)
            if lhsDistance != rhsDistance {
                return lhsDistance < rhsDistance
            }
            return lhs.createdAt < rhs.createdAt
        }
    }

    /// What the 重要倒数日 widget shows. 重点标记 finally earns its name here:
    /// a marked date wins over a nearer unmarked one, which is the only reason
    /// to mark it in the first place.
    static func next(
        _ events: [CountdownEvent],
        relativeTo date: Date = Date(),
        calendar: Calendar = .current
    ) -> CountdownEvent? {
        let today = calendar.startOfDay(for: date)
        let eligible = sorted(events).filter {
            $0.occurrenceDate(relativeTo: date, calendar: calendar) >= today
        }
        return eligible.first(where: \.isPinned) ?? eligible.first
    }

    static func applyingVisibleOrder(
        _ orderedIDs: [UUID],
        to events: [CountdownEvent]
    ) -> [CountdownEvent] {
        let allEvents = sorted(events)
        // A malformed or hand-edited backup can contain duplicate identifiers.
        // `Dictionary(uniqueKeysWithValues:)` traps on duplicates, so reject the
        // reorder while preserving the imported events for manual recovery.
        guard Set(allEvents.map(\.id)).count == allEvents.count else {
            return allEvents
        }
        let eventsByID = Dictionary(uniqueKeysWithValues: allEvents.map { ($0.id, $0) })
        let validIDs = orderedIDs.filter { eventsByID[$0] != nil }
        let visibleIDs = Set(validIDs)
        guard validIDs.count == visibleIDs.count else { return allEvents }

        var nextVisibleIndex = 0
        var reordered = allEvents.map { event -> CountdownEvent in
            guard visibleIDs.contains(event.id), nextVisibleIndex < validIDs.count else {
                return event
            }
            defer { nextVisibleIndex += 1 }
            return eventsByID[validIDs[nextVisibleIndex]] ?? event
        }

        for index in reordered.indices {
            reordered[index].sortOrder = index
        }
        return reordered
    }
}

enum PlanCompletionReversion {
    /// Removes only the record that completed this occurrence. Earlier partial
    /// focus records share the same plan ID and must remain part of the history.
    static func removeCompletionRecord(
        for item: CheckInItem,
        from records: inout [TimeRecord]
    ) {
        guard let actualStart = item.actualStartDate, let actualEnd = item.actualEndDate else {
            return
        }
        records.removeAll { record in
            record.checkInItemID == item.id
                && abs(record.startDate.timeIntervalSince(actualStart)) < 0.5
                && abs(record.endDate.timeIntervalSince(actualEnd)) < 0.5
        }
    }
}

enum TimeRecordDeletionEngine {
    @discardableResult
    static func remove(
        recordID: UUID,
        from records: inout [TimeRecord],
        checkInItems: inout [CheckInItem]
    ) -> TimeRecord? {
        guard let record = records.first(where: { $0.id == recordID }) else {
            return nil
        }

        records.removeAll { $0.id == recordID }
        guard
            let linkedItemID = record.checkInItemID,
            let linkedItem = checkInItems.first(where: { $0.id == linkedItemID })
        else {
            return record
        }

        if linkedItem.kind == .completedLog {
            checkInItems.removeAll { $0.id == linkedItemID }
            return record
        }

        // A completed plan and its generated successor are one occurrence.
        // Removing an accidental actual-time/Pomodoro record restores that
        // occurrence instead of leaving a "completed" plan with no evidence.
        guard !records.contains(where: { $0.checkInItemID == linkedItemID }) else {
            return record
        }

        if linkedItem.status == .completed {
            PlanRecurrenceEngine.removePendingSuccessorIfNeeded(
                after: linkedItem,
                from: &checkInItems
            )
        }

        guard let restoredIndex = checkInItems.firstIndex(where: { $0.id == linkedItemID })
        else {
            return record
        }
        if checkInItems[restoredIndex].status == .completed {
            checkInItems[restoredIndex].status = .planned
            checkInItems[restoredIndex].completedAt = nil
        }
        checkInItems[restoredIndex].actualStartDate = nil
        checkInItems[restoredIndex].actualEndDate = nil
        return record
    }
}

/// Turns focus time into stored data.
///
/// Both the in-app engine and the lock-screen intent funnel through here so a
/// pomodoro means exactly one thing regardless of where it was ended. Keeping
/// it a pure function over the two arrays — the same shape as
/// `PlanRecurrenceEngine` — is what makes the rules testable.
enum FocusSessionEngine {
    @discardableResult
    static func record(
        title: String,
        category: RecordCategory,
        startDate: Date,
        endDate: Date,
        linkedCheckInID: UUID?,
        completesPlan: Bool,
        note: String? = nil,
        records: inout [TimeRecord],
        checkInItems: inout [CheckInItem],
        calendar: Calendar = .current
    ) -> TimeRecord {
        let cleanTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let safeEnd = max(endDate, startDate.addingTimeInterval(1))
        let entry = TimeRecord(
            title: cleanTitle.isEmpty ? "番茄专注" : cleanTitle,
            category: category,
            startDate: startDate,
            endDate: safeEnd,
            note: note ?? (completesPlan ? "由番茄钟完成" : "由番茄钟记录"),
            checkInItemID: linkedCheckInID
        )
        records.append(entry)

        guard
            let linkedCheckInID,
            let index = checkInItems.firstIndex(where: { $0.id == linkedCheckInID })
        else { return entry }

        // An abandoned focus still happened, but it did not finish the plan.
        // The plan returns to the checklist and only the record remains.
        guard completesPlan else {
            if checkInItems[index].status == .inProgress {
                checkInItems[index].status = .planned
                checkInItems[index].actualStartDate = nil
                checkInItems[index].actualEndDate = nil
            }
            return entry
        }

        checkInItems[index].status = .completed
        checkInItems[index].actualStartDate = startDate
        checkInItems[index].actualEndDate = safeEnd
        checkInItems[index].completedAt = safeEnd
        let completedItem = checkInItems[index]
        PlanRecurrenceEngine.appendSuccessorIfNeeded(
            after: completedItem,
            to: &checkInItems,
            calendar: calendar
        )
        return entry
    }
}

enum PlanRecurrenceEngine {
    @discardableResult
    static func appendSuccessorIfNeeded(
        after item: CheckInItem,
        to items: inout [CheckInItem],
        calendar: Calendar = .current
    ) -> CheckInItem? {
        guard let successor = item.recurringSuccessor(calendar: calendar) else { return nil }

        let alreadyExists = items.contains {
            $0.id != item.id
                && $0.recurrenceSeriesID == successor.recurrenceSeriesID
                && calendar.isDate($0.scheduledStart, inSameDayAs: successor.scheduledStart)
                && calendar.component(.hour, from: $0.scheduledStart)
                    == calendar.component(.hour, from: successor.scheduledStart)
                && calendar.component(.minute, from: $0.scheduledStart)
                    == calendar.component(.minute, from: successor.scheduledStart)
        }
        guard !alreadyExists else { return nil }
        items.append(successor)
        return successor
    }

    @discardableResult
    static func removePendingSuccessorIfNeeded(
        after item: CheckInItem,
        from items: inout [CheckInItem],
        calendar: Calendar = .current
    ) -> CheckInItem? {
        guard
            let expectedStart = item.nextOccurrenceStart(
                after: item.completedAt,
                calendar: calendar
            ),
            let index = items.firstIndex(where: { candidate in
                guard
                    candidate.id != item.id,
                    candidate.status == .planned,
                    candidate.recurrenceSeriesID == item.recurrenceSeriesID
                else { return false }

                let matchesExpectedTime =
                    calendar.isDate(candidate.scheduledStart, inSameDayAs: expectedStart)
                    && calendar.component(.hour, from: candidate.scheduledStart)
                        == calendar.component(.hour, from: expectedStart)
                    && calendar.component(.minute, from: candidate.scheduledStart)
                        == calendar.component(.minute, from: expectedStart)

                return candidate.generatedFromOccurrenceID == item.id
                    || (candidate.generatedFromOccurrenceID == nil && matchesExpectedTime)
            })
        else { return nil }

        return items.remove(at: index)
    }
}

struct CheckInDaySummary: Identifiable, Equatable {
    let date: Date
    let plannedCount: Int
    let completedCount: Int
    let plannedMinutes: Int
    let actualMinutes: Int

    var id: Date { date }

    var completionRate: Double {
        guard plannedCount > 0 else { return 0 }
        return Double(completedCount) / Double(plannedCount)
    }
}

struct CheckInWeekSummary: Equatable {
    let startDate: Date
    let endDate: Date
    let days: [CheckInDaySummary]

    var plannedCount: Int { days.reduce(0) { $0 + $1.plannedCount } }
    var completedCount: Int { days.reduce(0) { $0 + $1.completedCount } }
    var plannedMinutes: Int { days.reduce(0) { $0 + $1.plannedMinutes } }
    var actualMinutes: Int { days.reduce(0) { $0 + $1.actualMinutes } }

    var completionRate: Double {
        guard plannedCount > 0 else { return 0 }
        return Double(completedCount) / Double(plannedCount)
    }
}

struct MonthActivityDaySummary: Identifiable, Equatable {
    let checkIn: CheckInDaySummary
    let studyMinutes: Int
    let workMinutes: Int
    let customMinutes: Int

    var id: Date { checkIn.date }
    var date: Date { checkIn.date }
    var actualMinutes: Int { studyMinutes + workMinutes + customMinutes }
}

struct CheckInMonthSummary: Equatable {
    let startDate: Date
    let endDate: Date
    let days: [MonthActivityDaySummary]
    let currentStreak: Int
    let longestStreak: Int

    var plannedCount: Int { days.reduce(0) { $0 + $1.checkIn.plannedCount } }
    var completedCount: Int { days.reduce(0) { $0 + $1.checkIn.completedCount } }
    var plannedMinutes: Int { days.reduce(0) { $0 + $1.checkIn.plannedMinutes } }
    var actualMinutes: Int { days.reduce(0) { $0 + $1.actualMinutes } }
    var studyMinutes: Int { days.reduce(0) { $0 + $1.studyMinutes } }
    var workMinutes: Int { days.reduce(0) { $0 + $1.workMinutes } }
    var customMinutes: Int { days.reduce(0) { $0 + $1.customMinutes } }

    var completionRate: Double {
        guard plannedCount > 0 else { return 0 }
        return Double(completedCount) / Double(plannedCount)
    }
}

enum CheckInAnalytics {
    static func day(
        items: [CheckInItem],
        records: [TimeRecord],
        containing date: Date,
        calendar inputCalendar: Calendar = .mojiISO
    ) -> CheckInDaySummary {
        var calendar = inputCalendar
        calendar.timeZone = .current
        let dayStart = calendar.startOfDay(for: date)
        let dayEnd = calendar.date(byAdding: .day, value: 1, to: dayStart) ?? dayStart
        let dayItems = items.filter {
            $0.kind == .planned
                && $0.scheduledStart >= dayStart
                && $0.scheduledStart < dayEnd
        }

        var actualSeconds: TimeInterval = 0
        for record in records where record.endDate > dayStart && record.startDate < dayEnd {
            let overlapStart = max(dayStart, record.startDate)
            let overlapEnd = min(dayEnd, record.endDate)
            if overlapEnd > overlapStart {
                actualSeconds += overlapEnd.timeIntervalSince(overlapStart)
            }
        }

        return CheckInDaySummary(
            date: dayStart,
            plannedCount: dayItems.count,
            completedCount: dayItems.filter(\.isCompleted).count,
            plannedMinutes: dayItems.reduce(0) {
                $0 + ($1.plannedDurationMinutes ?? 0)
            },
            actualMinutes: roundedMinutes(actualSeconds)
        )
    }

    static func week(
        items: [CheckInItem],
        records: [TimeRecord],
        containing anchorDate: Date,
        calendar inputCalendar: Calendar = .mojiISO
    ) -> CheckInWeekSummary {
        let timeSummary = WeeklyAnalytics.summary(
            records: records,
            containing: anchorDate,
            calendar: inputCalendar
        )
        let days = timeSummary.days.map { day in
            CheckInAnalytics.day(
                items: items,
                records: records,
                containing: day.date,
                calendar: inputCalendar
            )
        }
        return CheckInWeekSummary(
            startDate: timeSummary.startDate,
            endDate: timeSummary.endDate,
            days: days
        )
    }

    static func month(
        items: [CheckInItem],
        records: [TimeRecord],
        containing anchorDate: Date,
        now: Date = Date(),
        calendar inputCalendar: Calendar = .mojiISO
    ) -> CheckInMonthSummary {
        var calendar = inputCalendar
        calendar.timeZone = .current

        let anchorComponents = calendar.dateComponents([.year, .month], from: anchorDate)
        let monthStart = calendar.date(from: anchorComponents)
            .map { calendar.startOfDay(for: $0) }
            ?? calendar.startOfDay(for: anchorDate)
        let monthEnd = calendar.date(byAdding: .month, value: 1, to: monthStart) ?? monthStart
        let dayCount = calendar.dateComponents([.day], from: monthStart, to: monthEnd).day ?? 0

        let activityDays = (0..<dayCount).compactMap { offset -> MonthActivityDaySummary? in
            guard
                let dayStart = calendar.date(byAdding: .day, value: offset, to: monthStart),
                let dayEnd = calendar.date(byAdding: .day, value: 1, to: dayStart)
            else { return nil }

            var studySeconds: TimeInterval = 0
            var workSeconds: TimeInterval = 0
            var customSeconds: TimeInterval = 0
            for record in records where record.endDate > dayStart && record.startDate < dayEnd {
                let overlapStart = max(dayStart, record.startDate)
                let overlapEnd = min(dayEnd, record.endDate)
                guard overlapEnd > overlapStart else { continue }
                if record.category == .study {
                    studySeconds += overlapEnd.timeIntervalSince(overlapStart)
                } else if record.category == .work {
                    workSeconds += overlapEnd.timeIntervalSince(overlapStart)
                } else {
                    customSeconds += overlapEnd.timeIntervalSince(overlapStart)
                }
            }

            return MonthActivityDaySummary(
                checkIn: day(
                    items: items,
                    records: records,
                    containing: dayStart,
                    calendar: calendar
                ),
                studyMinutes: roundedMinutes(studySeconds),
                workMinutes: roundedMinutes(workSeconds),
                customMinutes: roundedMinutes(customSeconds)
            )
        }

        let completedDays = Set(
            items
                .filter(\.isCompleted)
                .map { calendar.startOfDay(for: $0.completedAt ?? $0.scheduledStart) }
        )
        let currentStreak = streak(
            completedDays: completedDays,
            through: calendar.startOfDay(for: now),
            calendar: calendar
        )
        let longestStreak = longestStreak(
            completedDays: Set(completedDays.filter { $0 >= monthStart && $0 < monthEnd }),
            calendar: calendar
        )

        return CheckInMonthSummary(
            startDate: monthStart,
            endDate: monthEnd,
            days: activityDays,
            currentStreak: currentStreak,
            longestStreak: longestStreak
        )
    }

    private static func streak(
        completedDays: Set<Date>,
        through date: Date,
        calendar: Calendar
    ) -> Int {
        var cursor = date
        if !completedDays.contains(cursor) {
            cursor = calendar.date(byAdding: .day, value: -1, to: cursor) ?? cursor
        }

        var count = 0
        while completedDays.contains(cursor) {
            count += 1
            guard let previous = calendar.date(byAdding: .day, value: -1, to: cursor) else {
                break
            }
            cursor = previous
        }
        return count
    }

    private static func longestStreak(
        completedDays: Set<Date>,
        calendar: Calendar
    ) -> Int {
        guard !completedDays.isEmpty else { return 0 }
        let ordered = completedDays.sorted()
        var longest = 1
        var current = 1
        for index in 1..<ordered.count {
            let expected = calendar.date(byAdding: .day, value: 1, to: ordered[index - 1])
            if expected == ordered[index] {
                current += 1
                longest = max(longest, current)
            } else {
                current = 1
            }
        }
        return longest
    }

    private static func roundedMinutes(_ seconds: TimeInterval) -> Int {
        guard seconds > 0 else { return 0 }
        return max(1, Int(ceil(seconds / 60)))
    }
}

struct WeekDaySummary: Identifiable, Equatable {
    let date: Date
    let studyMinutes: Int
    let workMinutes: Int
    let customMinutes: Int

    var id: Date { date }
    var totalMinutes: Int { studyMinutes + workMinutes + customMinutes }
}

struct WeekSummary: Equatable {
    let startDate: Date
    let endDate: Date
    let days: [WeekDaySummary]

    var studyMinutes: Int { days.reduce(0) { $0 + $1.studyMinutes } }
    var workMinutes: Int { days.reduce(0) { $0 + $1.workMinutes } }
    var customMinutes: Int { days.reduce(0) { $0 + $1.customMinutes } }
    var totalMinutes: Int { studyMinutes + workMinutes + customMinutes }
}

enum WeeklyAnalytics {
    static func summary(
        records: [TimeRecord],
        containing anchorDate: Date,
        calendar inputCalendar: Calendar = .mojiISO
    ) -> WeekSummary {
        var calendar = inputCalendar
        calendar.timeZone = .current

        let anchorStart = calendar.startOfDay(for: anchorDate)
        let weekday = calendar.component(.weekday, from: anchorStart)
        let daysSinceMonday = (weekday - calendar.firstWeekday + 7) % 7
        let weekStart = calendar.date(byAdding: .day, value: -daysSinceMonday, to: anchorStart) ?? anchorStart
        let weekEnd = calendar.date(byAdding: .day, value: 7, to: weekStart) ?? weekStart

        let relevantRecords = records.filter {
            $0.endDate > weekStart && $0.startDate < weekEnd
        }

        let daySummaries = (0..<7).compactMap { offset -> WeekDaySummary? in
            guard
                let dayStart = calendar.date(byAdding: .day, value: offset, to: weekStart),
                let dayEnd = calendar.date(byAdding: .day, value: 1, to: dayStart)
            else { return nil }

            var studySeconds: TimeInterval = 0
            var workSeconds: TimeInterval = 0
            var customSeconds: TimeInterval = 0

            for record in relevantRecords {
                let overlapStart = max(dayStart, record.startDate)
                let overlapEnd = min(dayEnd, record.endDate)
                guard overlapEnd > overlapStart else { continue }

                let seconds = overlapEnd.timeIntervalSince(overlapStart)
                if record.category == .study {
                    studySeconds += seconds
                } else if record.category == .work {
                    workSeconds += seconds
                } else {
                    customSeconds += seconds
                }
            }

            return WeekDaySummary(
                date: dayStart,
                studyMinutes: roundedMinutes(studySeconds),
                workMinutes: roundedMinutes(workSeconds),
                customMinutes: roundedMinutes(customSeconds)
            )
        }

        return WeekSummary(startDate: weekStart, endDate: weekEnd, days: daySummaries)
    }

    private static func roundedMinutes(_ seconds: TimeInterval) -> Int {
        guard seconds > 0 else { return 0 }
        return max(1, Int(ceil(seconds / 60)))
    }
}

struct CountdownParts: Equatable {
    let isPast: Bool
    let days: Int
    let hours: Int
    let minutes: Int
    let isImmediate: Bool

    var fullText: String {
        if isImmediate { return isPast ? "刚刚到期" : "不到 1 分钟" }

        var pieces: [String] = []
        if days > 0 { pieces.append("\(days) 天") }
        if hours > 0 { pieces.append("\(hours) 小时") }
        if minutes > 0 && days == 0 { pieces.append("\(minutes) 分钟") }
        let value = pieces.isEmpty ? "不到 1 分钟" : pieces.prefix(2).joined(separator: " ")
        return isPast ? "已过去 \(value)" : value
    }

    var compactText: String {
        if isImmediate { return isPast ? "已到期" : "<1分钟" }
        let prefix = isPast ? "已过 " : ""
        if days > 0 { return "\(prefix)\(days)天" }
        if hours > 0 { return "\(prefix)\(hours)小时" }
        return "\(prefix)\(minutes)分钟"
    }
}

enum CountdownCalculator {
    static func parts(until targetDate: Date, from now: Date = Date()) -> CountdownParts {
        let rawSeconds = targetDate.timeIntervalSince(now)
        let absoluteSeconds = abs(rawSeconds)
        let isImmediate = absoluteSeconds < 60
        let totalMinutes = Int(ceil(absoluteSeconds / 60))
        let days = totalMinutes / (24 * 60)
        let hours = (totalMinutes % (24 * 60)) / 60
        let minutes = totalMinutes % 60

        return CountdownParts(
            isPast: rawSeconds < 0,
            days: days,
            hours: hours,
            minutes: minutes,
            isImmediate: isImmediate
        )
    }
}

struct CountdownDayCount: Equatable {
    let signedDays: Int

    var isPast: Bool { signedDays < 0 }
    var isToday: Bool { signedDays == 0 }
    var magnitude: Int { abs(signedDays) }
    var directionText: String {
        if isToday { return "今天" }
        return isPast ? "正数" : "倒数"
    }

    var fullText: String {
        if isToday { return "就是今天" }
        return isPast ? "已经 \(magnitude) 天" : "还有 \(magnitude) 天"
    }

    var compactText: String {
        if isToday { return "今天" }
        return isPast ? "已 \(magnitude) 天" : "\(magnitude) 天"
    }
}

enum CountdownDayCalculator {
    static func count(
        to targetDate: Date,
        from now: Date = Date(),
        includesToday: Bool = false,
        calendar: Calendar = .current
    ) -> CountdownDayCount {
        let start = calendar.startOfDay(for: now)
        let target = calendar.startOfDay(for: targetDate)
        var days = calendar.dateComponents([.day], from: start, to: target).day ?? 0
        if includesToday, days != 0 {
            days += days > 0 ? 1 : -1
        }
        return CountdownDayCount(signedDays: days)
    }
}

enum DurationText {
    static func full(minutes: Int) -> String {
        let safeMinutes = max(0, minutes)
        let hours = safeMinutes / 60
        let remainingMinutes = safeMinutes % 60
        if hours == 0 { return "\(remainingMinutes) 分钟" }
        if remainingMinutes == 0 { return "\(hours) 小时" }
        return "\(hours) 小时 \(remainingMinutes) 分钟"
    }

    static func compact(minutes: Int) -> String {
        let safeMinutes = max(0, minutes)
        let hours = safeMinutes / 60
        let remainingMinutes = safeMinutes % 60
        if hours == 0 { return "\(remainingMinutes)分" }
        if remainingMinutes == 0 { return "\(hours)时" }
        return "\(hours)时\(remainingMinutes)分"
    }
}

enum ContinuousTimerProgress {
    static func elapsedFraction(
        durationSeconds: Int,
        storedRemainingSeconds: Int,
        targetTimestamp: Double,
        isRunning: Bool,
        at date: Date
    ) -> Double {
        let duration = Double(max(1, durationSeconds))
        let remaining: Double
        if isRunning, targetTimestamp > 0 {
            remaining = max(0, targetTimestamp - date.timeIntervalSince1970)
        } else {
            remaining = Double(max(0, storedRemainingSeconds))
        }
        return min(1, max(0, 1 - remaining / duration))
    }
}

extension Calendar {
    static var mojiISO: Calendar {
        var calendar = Calendar(identifier: .iso8601)
        calendar.locale = .current
        calendar.timeZone = .current
        calendar.firstWeekday = 2
        calendar.minimumDaysInFirstWeek = 4
        return calendar
    }
}

extension Date {
    func roundedDownToMinute(calendar: Calendar = .current) -> Date {
        let components = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: self)
        return calendar.date(from: components) ?? self
    }
}
