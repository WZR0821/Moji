import Foundation

struct PlanTemplate: Codable, Identifiable, Equatable {
    var id: UUID = UUID()
    var title: String
    var category: RecordCategory
    var note: String = ""
    var scheduleKind: ScheduleKind = .allDay
    var hour: Int = 0
    var minute: Int = 0
    var plannedMinutes: Int = 25
    var plannedDurationEnabled: Bool = false
    var reminderMinutesBefore: Int?
    var createdAt: Date = Date()

    init(from item: CheckInItem, calendar: Calendar = .current) {
        title = item.title; category = item.category; note = item.note
        scheduleKind = item.effectiveScheduleKind
        hour = calendar.component(.hour, from: item.scheduledStart)
        minute = calendar.component(.minute, from: item.scheduledStart)
        plannedMinutes = item.plannedMinutes
        plannedDurationEnabled = item.hasPlannedDuration
        reminderMinutesBefore = item.reminderMinutesBefore
    }

    func plan(on day: Date, calendar: Calendar = .current) -> CheckInItem {
        let date = calendar.date(bySettingHour: hour, minute: minute, second: 0, of: day) ?? day
        return CheckInItem(title: title, category: category, scheduledStart: date,
            plannedMinutes: plannedMinutes, note: note, scheduleKind: scheduleKind,
            reminderMinutesBefore: reminderMinutesBefore, plannedDurationEnabled: plannedDurationEnabled)
    }
}

struct WeeklyGoal: Codable, Identifiable, Equatable {
    var id: UUID = UUID()
    var weekStart: String
    var category: RecordCategory?
    var targetCount: Int?
    var targetMinutes: Int?
    var updatedAt: Date = Date()
}

struct WeeklyReflection: Codable, Identifiable, Equatable {
    var id: UUID = UUID()
    var weekStart: String
    var note: String = ""
    var nextFocus: String = ""
    var updatedAt: Date = Date()
}

struct PlanWorkflowUndo {
    var before: [CheckInItem]
    var after: [CheckInItem]

    /// Restore only the rows that have not been edited since the batch operation.
    /// Never rewind a snapshot: records, memo edits and focus writes may have changed.
    @discardableResult
    func apply(to state: inout PlanSnapshot, blockedID: UUID? = nil) -> Int {
        var count = 0
        for original in before {
            guard let changed = after.first(where: { $0.id == original.id }),
                  let index = state.checkInItems.firstIndex(where: { $0.id == original.id }),
                  state.checkInItems[index] == changed,
                  state.activeSession?.checkInItemID != original.id,
                  original.id != blockedID else { continue }
            state.checkInItems[index] = original; count += 1
        }
        return count
    }
}

enum PlanWorkflow {
    static func weekStart(_ date: Date, calendar: Calendar = .current) -> Date {
        let day = calendar.startOfDay(for: date)
        let offset = (calendar.component(.weekday, from: day) + 5) % 7
        return calendar.date(byAdding: .day, value: -offset, to: day) ?? day
    }

    static func weekKey(_ date: Date, calendar: Calendar = .current) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: weekStart(date, calendar: calendar))
        return String(format: "%04d-%02d-%02d", c.year ?? 2000, c.month ?? 1, c.day ?? 1)
    }

    static func validWeekKey(_ key: String) -> Bool {
        let parts = key.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3, key.count == 10 else { return false }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        guard let date = calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2])) else { return false }
        return weekKey(date, calendar: calendar) == key
    }

    @discardableResult
    static func convertMemo(in state: inout PlanSnapshot, memoID: UUID, itemID: UUID? = nil,
                            day: Date, category: RecordCategory = .study) -> UUID? {
        if let existing = state.checkInItems.first(where: {
            $0.sourceMemoID == memoID && $0.sourceMemoChecklistItemID == itemID
        }) { return existing.id }
        guard let memo = state.memos.first(where: { $0.id == memoID }) else { return nil }
        let body = memo.mode == .checklist
            ? memo.checklistItems.map { "\($0.isCompleted ? "☑" : "☐") \($0.text)" }.joined(separator: "\n")
            : memo.content
        let title: String
        if let itemID {
            guard let item = memo.checklistItems.first(where: { $0.id == itemID }),
                  !item.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
            title = item.text.trimmingCharacters(in: .whitespacesAndNewlines)
        } else {
            title = memo.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? (memo.mode == .checklist ? memo.checklistItems.first(where: { !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty })?.text : memo.content.components(separatedBy: .newlines).first(where: { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty })) ?? ""
                : memo.title.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        }
        let plan = CheckInItem(title: title, category: category,
            scheduledStart: Calendar.current.startOfDay(for: day), plannedMinutes: 25,
            note: body, scheduleKind: .allDay, plannedDurationEnabled: false,
            sourceMemoID: memoID, sourceMemoChecklistItemID: itemID)
        state.checkInItems.append(plan)
        return plan.id
    }

    static func copied(_ source: CheckInItem, to day: Date, calendar: Calendar = .current) -> CheckInItem {
        // A copy is a new one-off plan, not another occurrence in the source series.
        let template = PlanTemplate(from: source, calendar: calendar)
        return template.plan(on: day, calendar: calendar)
    }

    @discardableResult
    static func batch(in state: inout PlanSnapshot, ids: Set<UUID>, day: Date? = nil,
                      category: RecordCategory? = nil, archived: Bool? = nil,
                      nextWeekOf: Date? = nil, blockedID: UUID? = nil,
                      calendar: Calendar = .current) -> PlanWorkflowUndo {
        var before: [CheckInItem] = []; var after: [CheckInItem] = []
        for index in state.checkInItems.indices {
            var item = state.checkInItems[index]
            guard ids.contains(item.id), item.kind == .planned, item.status != .inProgress,
                  item.id != state.activeSession?.checkInItemID, item.id != blockedID else { continue }
            if (day != nil || nextWeekOf != nil) && item.status != .planned { continue }
            before.append(item)
            if let nextWeekOf {
                let start = weekStart(nextWeekOf, calendar: calendar)
                let weekday = (calendar.component(.weekday, from: item.scheduledStart) + 5) % 7
                let target = calendar.date(byAdding: .day, value: 7 + weekday, to: start) ?? start
                let time = calendar.dateComponents([.hour, .minute], from: item.scheduledStart)
                item.scheduledStart = calendar.date(bySettingHour: time.hour ?? 0, minute: time.minute ?? 0, second: 0, of: target) ?? target
            } else if let day {
                let time = calendar.dateComponents([.hour, .minute], from: item.scheduledStart)
                item.scheduledStart = calendar.date(bySettingHour: time.hour ?? 0, minute: time.minute ?? 0, second: 0, of: day) ?? day
            }
            if let category { item.category = category }
            if let archived { item.isArchived = archived }
            state.checkInItems[index] = item; after.append(item)
        }
        return PlanWorkflowUndo(before: before, after: after)
    }
}

struct WorkflowWeekSummary {
    var scheduled: [CheckInItem]
    var carryover: [CheckInItem]
    var completed: [CheckInItem]
    var estimatedMinutes: Int
    var actualMinutes: Int
    var untrackedCount: Int
    var pending: [CheckInItem] { scheduled.filter { $0.status == .planned && $0.isArchived != true } }

    static func make(snapshot: PlanSnapshot, week: Date, category: RecordCategory? = nil,
                     calendar: Calendar = .current) -> Self {
        let start = PlanWorkflow.weekStart(week, calendar: calendar)
        let end = calendar.date(byAdding: .day, value: 7, to: start) ?? start
        let plans = snapshot.checkInItems.filter { $0.kind == .planned && (category == nil || $0.category == category) }
        let scheduled = plans.filter { $0.scheduledStart >= start && $0.scheduledStart < end }
        let completed = plans.filter { $0.status == .completed && ($0.completedAt ?? $0.actualEndDate ?? $0.scheduledStart) >= start && ($0.completedAt ?? $0.actualEndDate ?? $0.scheduledStart) < end }
        let records = snapshot.records.filter { $0.hasPreciseTime && (category == nil || $0.category == category) }
        let seconds = records.reduce(0.0) { total, record in
            total + max(0, min(end, record.endDate).timeIntervalSince(max(start, record.startDate)))
        }
        return Self(scheduled: scheduled,
            carryover: plans.filter { $0.scheduledStart < start && $0.status == .planned && $0.isArchived != true },
            completed: completed,
            estimatedMinutes: scheduled.compactMap(\.plannedDurationMinutes).reduce(0, +),
            actualMinutes: seconds > 0 ? Int(ceil(seconds / 60)) : 0,
            untrackedCount: completed.filter { plan in !records.contains { $0.checkInItemID == plan.id } }.count)
    }
}
