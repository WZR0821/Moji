import Combine
import EventKit
import Foundation
import UserNotifications
import WidgetKit

enum CalendarSyncError: LocalizedError {
    case accessDenied
    case noWritableCalendar

    var errorDescription: String? {
        switch self {
        case .accessDenied:
            return "没有 Apple 日历写入权限。你仍然可以只保存在本机。"
        case .noWritableCalendar:
            return "没有找到可写入的 Apple 日历。"
        }
    }
}

enum PlanNotificationError: LocalizedError {
    case accessDenied

    var errorDescription: String? {
        switch self {
        case .accessDenied:
            return "没有通知权限。你可以在系统“设置”中为 Moji 开启通知。"
        }
    }
}

final class PlanNotificationService {
    static let shared = PlanNotificationService()
    static let pomodoroIdentifier = "minuteplan.pomodoro.phase"

    private let center = UNUserNotificationCenter.current()

    private init() {}

    func authorizationStatus() async -> UNAuthorizationStatus {
        await center.notificationSettings().authorizationStatus
    }

    func requestAuthorizationIfNeeded() async throws -> Bool {
        switch await authorizationStatus() {
        case .authorized, .provisional, .ephemeral:
            return true
        case .notDetermined:
            return try await center.requestAuthorization(options: [.alert, .sound, .badge])
        case .denied:
            return false
        @unknown default:
            return false
        }
    }

    func syncPlanReminders(_ items: [CheckInItem], now: Date = Date()) async {
        let requests = await center.pendingNotificationRequests()
        let planIdentifiers = requests
            .map(\.identifier)
            .filter { $0.hasPrefix("minuteplan.plan.") }
        center.removePendingNotificationRequests(withIdentifiers: planIdentifiers)

        guard PlanSettingsKeys.bool(
            PlanSettingsKeys.planNotificationsEnabled,
            fallback: true
        ) else { return }

        let status = await authorizationStatus()
        guard [.authorized, .provisional, .ephemeral].contains(status) else { return }

        for item in items {
            guard
                item.status == .planned || item.status == .inProgress,
                let reminderMinutes = item.reminderMinutesBefore
            else { continue }

            let baseDate: Date
            if item.effectiveScheduleKind == .allDay {
                var components = Calendar.current.dateComponents(
                    [.year, .month, .day],
                    from: item.scheduledStart
                )
                components.hour = min(
                    23,
                    max(
                        0,
                        PlanSettingsKeys.integer(
                            PlanSettingsKeys.allDayReminderHour,
                            fallback: 9
                        )
                    )
                )
                components.minute = 0
                baseDate = Calendar.current.date(from: components) ?? item.scheduledStart
            } else {
                baseDate = item.scheduledStart
            }
            let fireDate = baseDate.addingTimeInterval(TimeInterval(-reminderMinutes * 60))
            guard fireDate > now else { continue }

            let content = UNMutableNotificationContent()
            content.title = item.title
            content.body = item.timingSummaryText
            content.sound = PlanSettingsKeys.bool(
                PlanSettingsKeys.notificationSoundEnabled,
                fallback: true
            ) ? .default : nil
            content.userInfo = ["checkInItemID": item.id.uuidString]

            let components = Calendar.current.dateComponents(
                [.year, .month, .day, .hour, .minute],
                from: fireDate
            )
            let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
            try? await center.add(
                UNNotificationRequest(
                    identifier: item.notificationIdentifier,
                    content: content,
                    trigger: trigger
                )
            )
        }
    }

    func schedulePomodoroCompletion(
        title: String,
        phaseName: String,
        targetDate: Date
    ) async {
        center.removePendingNotificationRequests(withIdentifiers: [Self.pomodoroIdentifier])
        guard targetDate > Date() else { return }
        guard PlanSettingsKeys.bool(
            PlanSettingsKeys.planNotificationsEnabled,
            fallback: true
        ) else { return }
        guard (try? await requestAuthorizationIfNeeded()) == true else { return }

        let content = UNMutableNotificationContent()
        content.title = "\(phaseName)结束"
        content.body = phaseName == "专注" ? "\(title) 已完成，休息一下。" : "休息结束，可以开始下一轮专注。"
        content.sound = PlanSettingsKeys.bool(
            PlanSettingsKeys.notificationSoundEnabled,
            fallback: true
        ) ? .default : nil
        let trigger = UNTimeIntervalNotificationTrigger(
            timeInterval: max(1, targetDate.timeIntervalSinceNow),
            repeats: false
        )
        try? await center.add(
            UNNotificationRequest(
                identifier: Self.pomodoroIdentifier,
                content: content,
                trigger: trigger
            )
        )
    }

    func cancelPomodoroCompletion() {
        center.removePendingNotificationRequests(withIdentifiers: [Self.pomodoroIdentifier])
    }
}

@MainActor
final class CalendarSyncService {
    static let shared = CalendarSyncService()

    private let eventStore = EKEventStore()

    private init() {}

    var authorizationStatus: EKAuthorizationStatus {
        EKEventStore.authorizationStatus(for: .event)
    }

    func requestWriteAccessIfNeeded() async throws -> Bool {
        switch authorizationStatus {
        case .fullAccess, .writeOnly, .authorized:
            return true
        case .notDetermined:
            return try await eventStore.requestWriteOnlyAccessToEvents()
        case .denied, .restricted:
            return false
        @unknown default:
            return false
        }
    }

    func addEvent(for item: CheckInItem) async throws -> String {
        guard try await requestWriteAccessIfNeeded() else {
            throw CalendarSyncError.accessDenied
        }
        guard let calendar = eventStore.defaultCalendarForNewEvents else {
            throw CalendarSyncError.noWritableCalendar
        }

        let event = EKEvent(eventStore: eventStore)
        event.calendar = calendar
        event.title = item.title
        if
            item.kind == .completedLog,
            item.effectiveScheduleKind == .exactTime,
            let actualStart = item.actualStartDate,
            let actualEnd = item.actualEndDate,
            actualEnd > actualStart
        {
            event.startDate = actualStart
            event.endDate = actualEnd
        } else if item.effectiveScheduleKind != .exactTime {
            let startOfDay = Calendar.current.startOfDay(for: item.scheduledStart)
            event.isAllDay = true
            event.startDate = startOfDay
            event.endDate = Calendar.current.date(byAdding: .day, value: 1, to: startOfDay)
                ?? startOfDay.addingTimeInterval(24 * 60 * 60)
        } else {
            event.startDate = item.scheduledStart
            event.endDate = item.scheduledEnd
        }
        event.notes = [
            item.note,
            item.effectiveScheduleKind == .exactTime
                ? ""
                : "Moji 时段：\(item.effectiveScheduleKind.displayName)",
            "由 Moji 添加。日历同步为单向添加。"
        ]
        .filter { !$0.isEmpty }
        .joined(separator: "\n\n")
        if let recurrenceRule = recurrenceRule(for: item) {
            event.addRecurrenceRule(recurrenceRule)
        }
        try eventStore.save(event, span: .thisEvent, commit: true)
        return event.eventIdentifier
    }

    func addEvent(for countdown: CountdownEvent) async throws -> String {
        guard try await requestWriteAccessIfNeeded() else {
            throw CalendarSyncError.accessDenied
        }
        guard let calendar = eventStore.defaultCalendarForNewEvents else {
            throw CalendarSyncError.noWritableCalendar
        }

        let startOfDay = Calendar.current.startOfDay(for: countdown.targetDate)
        let event = EKEvent(eventStore: eventStore)
        event.calendar = calendar
        event.title = countdown.title
        event.isAllDay = true
        event.startDate = startOfDay
        event.endDate = Calendar.current.date(byAdding: .day, value: 1, to: startOfDay)
            ?? startOfDay.addingTimeInterval(24 * 60 * 60)
        event.notes = "由 Moji 添加。日历同步为单向添加。"

        let frequency: EKRecurrenceFrequency?
        switch countdown.effectiveRepeatRule {
        case .never: frequency = nil
        case .yearly: frequency = .yearly
        case .monthly: frequency = .monthly
        case .weekly: frequency = .weekly
        }
        if let frequency {
            event.addRecurrenceRule(
                EKRecurrenceRule(recurrenceWith: frequency, interval: 1, end: nil)
            )
        }

        try eventStore.save(event, span: .thisEvent, commit: true)
        return event.eventIdentifier
    }

    private func recurrenceRule(for item: CheckInItem) -> EKRecurrenceRule? {
        switch item.effectiveRepeatRule {
        case .never:
            return nil
        case .daily:
            return EKRecurrenceRule(recurrenceWith: .daily, interval: 1, end: nil)
        case .weekly:
            return EKRecurrenceRule(recurrenceWith: .weekly, interval: 1, end: nil)
        case .monthly:
            return EKRecurrenceRule(recurrenceWith: .monthly, interval: 1, end: nil)
        case .weekdays, .customWeekdays:
            let rawWeekdays = item.effectiveRepeatRule == .weekdays
                ? [2, 3, 4, 5, 6]
                : item.effectiveRepeatWeekdays
            let weekdays = rawWeekdays.compactMap { rawValue -> EKRecurrenceDayOfWeek? in
                guard let weekday = EKWeekday(rawValue: rawValue) else { return nil }
                return EKRecurrenceDayOfWeek(weekday)
            }
            guard !weekdays.isEmpty else { return nil }
            return EKRecurrenceRule(
                recurrenceWith: .weekly,
                interval: 1,
                daysOfTheWeek: weekdays,
                daysOfTheMonth: nil,
                monthsOfTheYear: nil,
                weeksOfTheYear: nil,
                daysOfTheYear: nil,
                setPositions: nil,
                end: nil
            )
        }
    }
}

@MainActor
final class PlanStore: ObservableObject {
    @Published private(set) var snapshot: PlanSnapshot
    private var planNotificationSyncTask: Task<Void, Never>?

    init(snapshot: PlanSnapshot? = nil) {
        self.snapshot = snapshot ?? SharedPersistence.load()
    }

    var records: [TimeRecord] { snapshot.records }
    var checkInItems: [CheckInItem] { snapshot.checkInItems }
    var countdowns: [CountdownEvent] { snapshot.countdowns }
    var memos: [MemoItem] { snapshot.memos }
    var activeSession: ActiveSession? { snapshot.activeSession }

    /// A pomodoro focus counts as something already in progress, even though it
    /// is stored outside the snapshot.
    var isPomodoroFocusActive: Bool {
        PomodoroSharedState.hasActiveFocusClaim()
    }

    func reload() {
        snapshot = SharedPersistence.load()
        refreshPlanNotifications()
    }

    func applyPreferenceChanges() {
        refreshPlanNotifications()
        reloadWidgets()
    }

    func startSession(
        title: String,
        category: RecordCategory,
        at date: Date = Date(),
        checkInItemID: UUID? = nil,
        plannedDurationMinutes: Int? = nil
    ) {
        guard snapshot.activeSession == nil, !isPomodoroFocusActive else { return }
        let cleanTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        snapshot = SharedPersistence.mutate { state in
            state.activeSession = ActiveSession(
                title: cleanTitle.isEmpty ? "专注记录" : cleanTitle,
                category: category,
                startedAt: date,
                checkInItemID: checkInItemID,
                plannedDurationMinutes: plannedDurationMinutes
            )
            if
                let checkInItemID,
                let index = state.checkInItems.firstIndex(where: { $0.id == checkInItemID })
            {
                state.checkInItems[index].status = .inProgress
                state.checkInItems[index].actualStartDate = date
            }
        }
        reloadWidgets()
    }

    func startCheckIn(id: UUID, at date: Date = Date()) {
        guard
            snapshot.activeSession == nil,
            let item = snapshot.checkInItems.first(where: { $0.id == id }),
            item.status == .planned
        else { return }
        startSession(
            title: item.title,
            category: item.category,
            at: date,
            checkInItemID: item.id,
            plannedDurationMinutes: item.plannedDurationMinutes
        )
    }

    func stopSession(at date: Date = Date()) {
        guard snapshot.activeSession != nil else { return }
        snapshot = SharedPersistence.mutate { state in
            guard let active = state.activeSession else { return }
            state.records.append(
                TimeRecord(
                    title: active.title,
                    category: active.category,
                    startDate: active.startedAt,
                    endDate: max(date, active.startedAt.addingTimeInterval(1)),
                    checkInItemID: active.checkInItemID
                )
            )
            if
                let itemID = active.checkInItemID,
                let index = state.checkInItems.firstIndex(where: { $0.id == itemID })
            {
                state.checkInItems[index].status = .completed
                state.checkInItems[index].actualStartDate = active.startedAt
                state.checkInItems[index].actualEndDate = max(date, active.startedAt.addingTimeInterval(1))
                state.checkInItems[index].completedAt = date
                let completedItem = state.checkInItems[index]
                PlanRecurrenceEngine.appendSuccessorIfNeeded(
                    after: completedItem,
                    to: &state.checkInItems
                )
            }
            state.activeSession = nil
        }
        refreshPlanNotifications()
        reloadWidgets()
    }

    func saveCheckIn(_ item: CheckInItem) {
        snapshot = SharedPersistence.mutate { state in
            if item.kind == .completedLog {
                let actualStart = item.actualStartDate ?? item.scheduledStart
                let actualEnd: Date
                if item.effectiveScheduleKind == .exactTime {
                    guard
                        let preciseEnd = item.actualEndDate,
                        preciseEnd > actualStart
                    else { return }
                    actualEnd = preciseEnd
                } else {
                    actualEnd = actualStart
                }
                let originalPlanIndex = state.checkInItems.firstIndex {
                    $0.id == item.id && $0.kind == .planned
                }
                state.records.removeAll { $0.checkInItemID == item.id }
                state.records.append(
                    TimeRecord(
                        title: item.title,
                        category: item.category,
                        startDate: actualStart,
                        endDate: actualEnd,
                        note: item.note,
                        checkInItemID: item.id,
                        scheduleKind: item.effectiveScheduleKind
                    )
                )

                if let originalPlanIndex {
                    // Switching an existing plan to “记录已做” completes that
                    // occurrence and keeps its plan history. A brand-new
                    // completed log, by contrast, remains only a time record.
                    var completedPlan = state.checkInItems[originalPlanIndex]
                    completedPlan.title = item.title
                    completedPlan.category = item.category
                    completedPlan.note = item.note
                    completedPlan.status = .completed
                    completedPlan.actualStartDate = actualStart
                    completedPlan.actualEndDate = actualEnd
                    completedPlan.completedAt = actualEnd
                    completedPlan.detailsConfigured = true
                    state.checkInItems[originalPlanIndex] = completedPlan
                    PlanRecurrenceEngine.appendSuccessorIfNeeded(
                        after: completedPlan,
                        to: &state.checkInItems
                    )
                } else {
                    // Clean up shadow items produced by versions before v1.13.
                    state.checkInItems.removeAll { $0.id == item.id }
                }
            } else if let index = state.checkInItems.firstIndex(where: { $0.id == item.id }) {
                state.checkInItems[index] = item
            } else {
                state.checkInItems.append(item)
            }
        }
        refreshPlanNotifications()
        reloadWidgets()
    }

    func addCheckInToCalendar(id: UUID) async throws {
        guard var item = checkInItems.first(where: { $0.id == id }) else { return }
        guard item.calendarEventIdentifier == nil else { return }
        item.calendarSyncEnabled = true
        item.detailsConfigured = true
        item.calendarEventIdentifier = try await CalendarSyncService.shared.addEvent(for: item)
        saveCheckIn(item)
    }

    func toggleCheckIn(id: UUID, at date: Date = Date()) {
        snapshot = SharedPersistence.mutate { state in
            guard let index = state.checkInItems.firstIndex(where: { $0.id == id }) else { return }

            if state.checkInItems[index].isCompleted {
                let completedItem = state.checkInItems[index]
                PlanRecurrenceEngine.removePendingSuccessorIfNeeded(
                    after: completedItem,
                    from: &state.checkInItems
                )
                guard
                    let restoredIndex = state.checkInItems.firstIndex(where: { $0.id == id })
                else { return }
                state.checkInItems[restoredIndex].status = .planned
                state.checkInItems[restoredIndex].completedAt = nil
                state.checkInItems[restoredIndex].actualStartDate = nil
                state.checkInItems[restoredIndex].actualEndDate = nil
                PlanCompletionReversion.removeCompletionRecord(
                    for: completedItem,
                    from: &state.records
                )
            } else if state.checkInItems[index].status == .skipped {
                let skippedItem = state.checkInItems[index]
                PlanRecurrenceEngine.removePendingSuccessorIfNeeded(
                    after: skippedItem,
                    from: &state.checkInItems
                )
                guard
                    let restoredIndex = state.checkInItems.firstIndex(where: { $0.id == id })
                else { return }
                state.checkInItems[restoredIndex].status = .planned
                state.checkInItems[restoredIndex].completedAt = nil
            } else if state.activeSession?.checkInItemID == id {
                guard let active = state.activeSession else { return }
                let safeEndDate = max(date, active.startedAt.addingTimeInterval(1))
                state.records.append(
                    TimeRecord(
                        title: active.title,
                        category: active.category,
                        startDate: active.startedAt,
                        endDate: safeEndDate,
                        checkInItemID: id
                    )
                )
                state.checkInItems[index].status = .completed
                state.checkInItems[index].actualStartDate = active.startedAt
                state.checkInItems[index].actualEndDate = safeEndDate
                state.checkInItems[index].completedAt = date
                state.activeSession = nil
                let completedItem = state.checkInItems[index]
                PlanRecurrenceEngine.appendSuccessorIfNeeded(
                    after: completedItem,
                    to: &state.checkInItems
                )
            } else {
                state.checkInItems[index].status = .completed
                state.checkInItems[index].completedAt = date
                let completedItem = state.checkInItems[index]
                PlanRecurrenceEngine.appendSuccessorIfNeeded(
                    after: completedItem,
                    to: &state.checkInItems
                )
            }
        }
        refreshPlanNotifications()
        reloadWidgets()
    }

    func skipCheckIn(id: UUID, at date: Date = Date()) {
        snapshot = SharedPersistence.mutate { state in
            guard
                state.activeSession?.checkInItemID != id,
                let index = state.checkInItems.firstIndex(where: { $0.id == id }),
                !state.checkInItems[index].isCompleted
            else { return }
            state.checkInItems[index].status = .skipped
            state.checkInItems[index].completedAt = date
            let skippedItem = state.checkInItems[index]
            PlanRecurrenceEngine.appendSuccessorIfNeeded(
                after: skippedItem,
                to: &state.checkInItems
            )
        }
        refreshPlanNotifications()
        reloadWidgets()
    }

    func postponeCheckIn(id: UUID, byDays days: Int = 1) {
        guard days > 0 else { return }
        snapshot = SharedPersistence.mutate { state in
            guard
                state.activeSession?.checkInItemID != id,
                let index = state.checkInItems.firstIndex(where: { $0.id == id }),
                !state.checkInItems[index].isCompleted
            else { return }
            let current = state.checkInItems[index].scheduledStart
            state.checkInItems[index].scheduledStart = Calendar.current.date(
                byAdding: .day,
                value: days,
                to: current
            ) ?? current.addingTimeInterval(TimeInterval(days * 24 * 60 * 60))
            state.checkInItems[index].status = .planned
            state.checkInItems[index].completedAt = nil
            state.checkInItems[index].actualStartDate = nil
            state.checkInItems[index].actualEndDate = nil
        }
        refreshPlanNotifications()
        reloadWidgets()
    }

    func deleteCheckIn(id: UUID) {
        snapshot = SharedPersistence.mutate { state in
            state.checkInItems.removeAll { $0.id == id }
            state.records.removeAll { $0.checkInItemID == id }
            if state.activeSession?.checkInItemID == id {
                state.activeSession = nil
            }
        }
        clearPomodoroLink(matching: id)
        refreshPlanNotifications()
        reloadWidgets()
    }

    /// Drops a dangling pomodoro link so a deleted plan cannot be "completed"
    /// later by a timer that is still counting against its identifier.
    private func clearPomodoroLink(matching id: UUID) {
        let defaults = SharedPersistence.sharedDefaults
        guard
            defaults.string(forKey: PomodoroStorageKeys.linkedPlanID) == id.uuidString
        else { return }
        defaults.set("", forKey: PomodoroStorageKeys.linkedPlanID)
    }

    func checkIns(on date: Date, calendar: Calendar = .current) -> [CheckInItem] {
        checkInItems.filter { calendar.isDate($0.scheduledStart, inSameDayAs: date) }
    }

    func nextPlannedCheckIn(now: Date = Date(), calendar: Calendar = .current) -> CheckInItem? {
        let today = checkInItems
            .filter {
                $0.isVisibleInChecklist(on: now, calendar: calendar)
                    && ($0.status == .planned || $0.status == .inProgress)
            }
        return today.sorted { $0.scheduledStart < $1.scheduledStart }.first
    }

    /// Closes a widget-started session into a real record without completing
    /// its plan, because the user is handing that work over to the pomodoro
    /// rather than finishing it. The minutes stay, the plan stays open.
    func yieldActiveSession(at date: Date = Date()) {
        guard snapshot.activeSession != nil else { return }
        snapshot = SharedPersistence.mutate { state in
            guard let active = state.activeSession else { return }
            let safeEnd = max(date, active.startedAt.addingTimeInterval(1))
            if safeEnd.timeIntervalSince(active.startedAt) >= 60 {
                state.records.append(
                    TimeRecord(
                        title: active.title,
                        category: active.category,
                        startDate: active.startedAt,
                        endDate: safeEnd,
                        note: "由桌面小组件记录",
                        checkInItemID: active.checkInItemID
                    )
                )
            }
            if
                let itemID = active.checkInItemID,
                let index = state.checkInItems.firstIndex(where: { $0.id == itemID }),
                state.checkInItems[index].status == .inProgress
            {
                state.checkInItems[index].status = .planned
                state.checkInItems[index].actualStartDate = nil
                state.checkInItems[index].actualEndDate = nil
            }
            state.activeSession = nil
        }
        reloadWidgets()
    }

    /// Marks a plan as 进行中 because a pomodoro focus just started on it.
    func beginPomodoroFocus(id: UUID, at date: Date = Date()) {
        snapshot = SharedPersistence.mutate { state in
            guard
                let index = state.checkInItems.firstIndex(where: { $0.id == id }),
                state.checkInItems[index].status == .planned
            else { return }
            state.checkInItems[index].status = .inProgress
            state.checkInItems[index].actualStartDate = date
        }
        reloadWidgets()
    }

    /// Hands a plan back to the checklist after an abandoned focus. Completed
    /// plans are left alone — the focus finished, it was only the link that ended.
    func releasePomodoroFocus(id: UUID) {
        snapshot = SharedPersistence.mutate { state in
            guard
                let index = state.checkInItems.firstIndex(where: { $0.id == id }),
                state.checkInItems[index].status == .inProgress
            else { return }
            state.checkInItems[index].status = .planned
            state.checkInItems[index].actualStartDate = nil
            state.checkInItems[index].actualEndDate = nil
        }
        reloadWidgets()
    }

    /// The single entry point for turning focus time into stored data.
    ///
    /// `completesPlan` separates a phase that ran to its end (the plan is done,
    /// and a repeating plan generates its successor) from an abandoned one,
    /// where the minutes were still real but the plan returns to the checklist.
    func recordFocusSession(
        title: String,
        category: RecordCategory,
        startDate: Date,
        endDate: Date,
        linkedCheckInID: UUID?,
        completesPlan: Bool
    ) {
        snapshot = SharedPersistence.mutate { state in
            FocusSessionEngine.record(
                title: title,
                category: category,
                startDate: startDate,
                endDate: endDate,
                linkedCheckInID: linkedCheckInID,
                completesPlan: completesPlan,
                records: &state.records,
                checkInItems: &state.checkInItems
            )
        }
        refreshPlanNotifications()
        reloadWidgets()
    }

    func completePomodoro(
        title: String,
        category: RecordCategory,
        startDate: Date,
        endDate: Date,
        linkedCheckInID: UUID?
    ) {
        recordFocusSession(
            title: title,
            category: category,
            startDate: startDate,
            endDate: endDate,
            linkedCheckInID: linkedCheckInID,
            completesPlan: true
        )
    }

    func importBackup(_ data: Data) throws {
        snapshot = try SharedPersistence.importBackup(data)
        refreshPlanNotifications()
        reloadWidgets()
    }

    func saveMemo(_ memo: MemoItem) {
        snapshot = SharedPersistence.mutate { state in
            if let index = state.memos.firstIndex(where: { $0.id == memo.id }) {
                state.memos[index] = memo
            } else {
                state.memos.append(memo)
            }
        }
        reloadWidgets()
    }

    func toggleMemoPin(id: UUID, at date: Date = Date()) {
        snapshot = SharedPersistence.mutate { state in
            guard let index = state.memos.firstIndex(where: { $0.id == id }) else { return }
            state.memos[index].isPinned.toggle()
            state.memos[index].updatedAt = date
        }
        reloadWidgets()
    }

    func deleteMemo(id: UUID) {
        snapshot = SharedPersistence.mutate { state in
            state.memos.removeAll { $0.id == id }
        }
        reloadWidgets()
    }

    func saveRecord(_ record: TimeRecord) {
        snapshot = SharedPersistence.mutate { state in
            if let index = state.records.firstIndex(where: { $0.id == record.id }) {
                state.records[index] = record
            } else {
                state.records.append(record)
            }
        }
        reloadWidgets()
    }

    func deleteRecord(id: UUID) {
        snapshot = SharedPersistence.mutate { state in
            TimeRecordDeletionEngine.remove(
                recordID: id,
                from: &state.records,
                checkInItems: &state.checkInItems
            )
        }
        refreshPlanNotifications()
        reloadWidgets()
    }

    func saveCountdown(_ event: CountdownEvent) {
        var normalized = event
        normalized.targetDate = Calendar.current.startOfDay(for: event.targetDate)
        snapshot = SharedPersistence.mutate { state in
            if let index = state.countdowns.firstIndex(where: { $0.id == normalized.id }) {
                state.countdowns[index] = normalized
            } else {
                normalized.sortOrder = (
                    state.countdowns.compactMap(\.sortOrder).max() ?? -1
                ) + 1
                state.countdowns.append(normalized)
            }
        }
        reloadWidgets()
    }

    func reorderCountdowns(orderedIDs: [UUID]) {
        snapshot = SharedPersistence.mutate { state in
            state.countdowns = CountdownOrdering.applyingVisibleOrder(
                orderedIDs,
                to: state.countdowns
            )
        }
        reloadWidgets()
    }

    func addCountdownToCalendar(id: UUID) async throws {
        guard var event = countdowns.first(where: { $0.id == id }) else { return }
        guard event.calendarEventIdentifier == nil else { return }
        event.calendarSyncEnabled = true
        event.calendarEventIdentifier = try await CalendarSyncService.shared.addEvent(for: event)
        saveCountdown(event)
    }

    func deleteCountdown(id: UUID) {
        snapshot = SharedPersistence.mutate { state in
            state.countdowns.removeAll { $0.id == id }
        }
        reloadWidgets()
    }

    func todayRecords(now: Date = Date(), calendar: Calendar = .current) -> [TimeRecord] {
        records.filter { calendar.isDate($0.startDate, inSameDayAs: now) }
    }

    func minutesToday(now: Date = Date()) -> Int {
        let summary = WeeklyAnalytics.summary(records: records, containing: now)
        return summary.days.first(where: { Calendar.current.isDate($0.date, inSameDayAs: now) })?.totalMinutes ?? 0
    }

    func nextCountdown(now: Date = Date()) -> CountdownEvent? {
        CountdownOrdering.next(countdowns, relativeTo: now)
    }

    /// Every write path already funnels through here, so this is the one place
    /// that has to remember to refresh the off-device copy.
    private func reloadWidgets() {
        WidgetCenter.shared.reloadAllTimelines()
        ExternalBackupService.shared.scheduleBackup()
    }

    /// True on a fresh install — the signal that a re-sign wiped the sandbox
    /// and the user should be offered a restore.
    var isEmpty: Bool {
        snapshot.checkInItems.isEmpty
            && snapshot.records.isEmpty
            && snapshot.countdowns.isEmpty
            && snapshot.memos.isEmpty
    }

    private func refreshPlanNotifications() {
        let items = snapshot.checkInItems
        let previous = planNotificationSyncTask
        planNotificationSyncTask = Task {
            // Reminder sync removes and recreates the whole plan set. Running
            // snapshots concurrently lets an older task finish last and restore
            // stale reminders, so writes are deliberately chained in order.
            await previous?.value
            await PlanNotificationService.shared.syncPlanReminders(items)
        }
    }
}
