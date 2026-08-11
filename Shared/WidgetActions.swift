import AppIntents
import ActivityKit
import Foundation
import UserNotifications
import WidgetKit

enum PomodoroStorageKeys {
    // Persisted keys keep their legacy namespace so upgrades and widgets can
    // continue the timer that was already running before the Moji rename.
    static let phase = "minuteplan.pomodoro.phase"
    static let running = "minuteplan.pomodoro.running"
    static let remaining = "minuteplan.pomodoro.remaining"
    static let target = "minuteplan.pomodoro.target"
    static let segmentStart = "minuteplan.pomodoro.segmentStart"
    static let accumulated = "minuteplan.pomodoro.accumulated"
    static let completed = "minuteplan.pomodoro.completed"
    static let title = "minuteplan.pomodoro.title"
    static let category = "minuteplan.pomodoro.category"
    static let linkedPlanID = "minuteplan.pomodoro.linkedPlanID"
    static let liveActivityVisible = "minuteplan.pomodoro.liveActivityVisible"
    static let phaseDuration = "minuteplan.pomodoro.phaseDuration"
    static let phaseDurationIsPlanOwned = "minuteplan.pomodoro.phaseDurationIsPlanOwned"
    static let terminationConfirmationRequested =
        "minuteplan.pomodoro.terminationConfirmationRequested"

    static let focusMinutes = "minuteplan.settings.focusMinutes"
    static let shortBreakMinutes = "minuteplan.settings.shortBreakMinutes"
    static let longBreakMinutes = "minuteplan.settings.longBreakMinutes"
    static let longBreakInterval = "minuteplan.settings.longBreakInterval"
    static let longBreakEnabled = "minuteplan.settings.longBreakEnabled"

    static let focusPhase = "focus"
    static let shortBreakPhase = "shortBreak"
    static let longBreakPhase = "longBreak"
}

enum PlanSettingsKeys {
    // Persisted settings share the same upgrade-compatibility namespace.
    static let defaultCategory = "minuteplan.settings.defaultCategory"
    static let customCategories = "minuteplan.settings.customCategories"
    static let quickPlanPresets = "minuteplan.settings.quickPlanPresets"
    static let defaultScheduleKind = "minuteplan.settings.defaultScheduleKind"
    static let defaultPlannedDurationEnabled = "minuteplan.settings.defaultPlannedDurationEnabled"
    static let defaultPlannedMinutes = "minuteplan.settings.defaultPlannedMinutes"
    static let allDayReminderHour = "minuteplan.settings.allDayReminderHour"
    /// 倒数日 and 纪念日 each remember their own 手动 / 按时间 choice.
    static let countdownSortMode = "minuteplan.settings.countdownSortMode"
    static let anniversarySortMode = "minuteplan.settings.anniversarySortMode"
    /// Whether plans left unfinished on an earlier day keep showing up on
    /// today's checklist. Finished ones never carry over — they belong to the
    /// day they were finished on.
    static let carryOverUnfinishedPlans = "minuteplan.settings.carryOverUnfinishedPlans"

    static let planNotificationsEnabled = "minuteplan.settings.planNotificationsEnabled"
    static let notificationSoundEnabled = "minuteplan.settings.notificationSoundEnabled"
    static let hapticsEnabled = "minuteplan.settings.hapticsEnabled"
    static let liveActivitiesEnabled = "minuteplan.settings.liveActivitiesEnabled"
    static let autoStartBreaks = "minuteplan.settings.autoStartBreaks"
    static let autoStartFocus = "minuteplan.settings.autoStartFocus"
    static let keepScreenAwake = "minuteplan.settings.keepScreenAwake"

    static let appearanceMode = "minuteplan.settings.appearanceMode"
    static let inkMotionLevel = "minuteplan.settings.inkMotionLevel"
    static let paperTextureEnabled = "minuteplan.settings.paperTextureEnabled"

    static func bool(
        _ key: String,
        fallback: Bool,
        defaults: UserDefaults = SharedPersistence.sharedDefaults
    ) -> Bool {
        defaults.object(forKey: key) == nil ? fallback : defaults.bool(forKey: key)
    }

    /// Carrying unfinished plans forward is the old behaviour, so it stays the
    /// default; the setting only exists for people who want today to mean today.
    static var carriesOverUnfinishedPlans: Bool {
        bool(carryOverUnfinishedPlans, fallback: true)
    }

    static func integer(
        _ key: String,
        fallback: Int,
        defaults: UserDefaults = SharedPersistence.sharedDefaults
    ) -> Int {
        defaults.object(forKey: key) == nil ? fallback : defaults.integer(forKey: key)
    }
}

enum PomodoroCyclePolicy {
    static func nextBreakPhaseRaw(
        completedFocusCount: Int,
        longBreakInterval: Int,
        longBreakEnabled: Bool
    ) -> String {
        let interval = max(1, longBreakInterval)
        let shouldTakeLongBreak = longBreakEnabled
            && completedFocusCount > 0
            && completedFocusCount.isMultiple(of: interval)
        return shouldTakeLongBreak
            ? PomodoroStorageKeys.longBreakPhase
            : PomodoroStorageKeys.shortBreakPhase
    }
}

struct PomodoroSharedState {
    let title: String
    let phaseRaw: String
    let targetDate: Date?
    let remainingSeconds: Int
    let isRunning: Bool
    let shouldShowLiveActivity: Bool

    var phaseName: String {
        switch phaseRaw {
        case PomodoroStorageKeys.shortBreakPhase: return "短休息"
        case PomodoroStorageKeys.longBreakPhase: return "长休息"
        default: return "专注"
        }
    }

    var activityContentState: PomodoroActivityAttributes.ContentState {
        PomodoroActivityAttributes.ContentState(
            title: title,
            phaseName: phaseName,
            targetDate: targetDate,
            remainingSeconds: remainingSeconds,
            isRunning: isRunning
        )
    }

    static func current(
        at date: Date = Date(),
        defaults: UserDefaults = SharedPersistence.sharedDefaults
    ) -> PomodoroSharedState {
        let phaseRaw = defaults.string(forKey: PomodoroStorageKeys.phase)
            ?? PomodoroStorageKeys.focusPhase
        let isRunning = defaults.bool(forKey: PomodoroStorageKeys.running)
        let targetTimestamp = defaults.double(forKey: PomodoroStorageKeys.target)
        let fallbackDuration = durationSeconds(for: phaseRaw, defaults: defaults)
        let storedRemaining = defaults.object(forKey: PomodoroStorageKeys.remaining) == nil
            ? fallbackDuration
            : defaults.integer(forKey: PomodoroStorageKeys.remaining)
        let remaining = isRunning && targetTimestamp > 0
            ? max(0, Int(ceil(targetTimestamp - date.timeIntervalSince1970)))
            : max(0, storedRemaining)
        return PomodoroSharedState(
            title: defaults.string(forKey: PomodoroStorageKeys.title) ?? "番茄专注",
            phaseRaw: phaseRaw,
            targetDate: isRunning && targetTimestamp > 0
                ? Date(timeIntervalSince1970: targetTimestamp)
                : nil,
            remainingSeconds: remaining,
            isRunning: isRunning,
            // Treat an already-running timer from an older build as visible during migration.
            shouldShowLiveActivity: PlanSettingsKeys.bool(
                PlanSettingsKeys.liveActivitiesEnabled,
                fallback: true,
                defaults: defaults
            ) && (
                isRunning
                    || defaults.bool(forKey: PomodoroStorageKeys.liveActivityVisible)
            )
        )
    }

    static func hasActiveFocusClaim(
        defaults: UserDefaults = SharedPersistence.sharedDefaults
    ) -> Bool {
        let phaseRaw = defaults.string(forKey: PomodoroStorageKeys.phase)
            ?? PomodoroStorageKeys.focusPhase
        guard phaseRaw == PomodoroStorageKeys.focusPhase else { return false }
        if defaults.bool(forKey: PomodoroStorageKeys.running) { return true }
        if defaults.bool(forKey: PomodoroStorageKeys.liveActivityVisible) { return true }
        if defaults.integer(forKey: PomodoroStorageKeys.accumulated) > 0 { return true }
        let duration = max(
            1,
            defaults.integer(forKey: PomodoroStorageKeys.phaseDuration)
        )
        guard defaults.object(forKey: PomodoroStorageKeys.remaining) != nil else {
            return false
        }
        return defaults.integer(forKey: PomodoroStorageKeys.remaining) < duration
    }

    /// Settles a phase whose absolute target has already passed before a Live
    /// Activity button interprets the tap as pause/reset. Without this guard an
    /// expired focus became a paused 00:00 timer and never completed its plan.
    @discardableResult
    static func settleExpiredPhaseIfNeeded(
        at date: Date = Date(),
        defaults: UserDefaults = SharedPersistence.sharedDefaults
    ) -> PomodoroSharedState? {
        let state = current(at: date, defaults: defaults)
        let targetTimestamp = defaults.double(forKey: PomodoroStorageKeys.target)
        guard
            state.isRunning,
            targetTimestamp > 0,
            date.timeIntervalSince1970 >= targetTimestamp
        else { return nil }

        let endDate = Date(timeIntervalSince1970: targetTimestamp)
        let phaseRaw = state.phaseRaw
        var completedFocusCount = defaults.integer(forKey: PomodoroStorageKeys.completed)

        if phaseRaw == PomodoroStorageKeys.focusPhase {
            let rawStoredDuration = defaults.integer(forKey: PomodoroStorageKeys.phaseDuration)
            let storedDuration = rawStoredDuration > 0
                ? rawStoredDuration
                : durationSeconds(for: phaseRaw, defaults: defaults)
            let elapsed = min(
                storedDuration,
                elapsedFocusSeconds(at: endDate, defaults: defaults)
            )
            if elapsed > 0 {
                let rawTitle = (defaults.string(forKey: PomodoroStorageKeys.title)
                    ?? "番茄专注").trimmingCharacters(in: .whitespacesAndNewlines)
                let category = RecordCategory(
                    rawValue: defaults.string(forKey: PomodoroStorageKeys.category) ?? ""
                ) ?? .study
                let linkedPlanID = UUID(
                    uuidString: defaults.string(forKey: PomodoroStorageKeys.linkedPlanID) ?? ""
                )
                let startDate = endDate.addingTimeInterval(TimeInterval(-elapsed))
                var didRecord = false
                SharedPersistence.mutate { snapshot in
                    let alreadyRecorded = snapshot.records.contains { record in
                        record.checkInItemID == linkedPlanID
                            && record.note == "由番茄钟完成"
                            && abs(record.startDate.timeIntervalSince(startDate)) < 0.5
                            && abs(record.endDate.timeIntervalSince(endDate)) < 0.5
                    }
                    guard !alreadyRecorded else { return }
                    didRecord = true
                    FocusSessionEngine.record(
                        title: rawTitle,
                        category: category,
                        startDate: startDate,
                        endDate: endDate,
                        linkedCheckInID: linkedPlanID,
                        completesPlan: true,
                        records: &snapshot.records,
                        checkInItems: &snapshot.checkInItems
                    )
                }
                if didRecord {
                    completedFocusCount += 1
                    defaults.set(completedFocusCount, forKey: PomodoroStorageKeys.completed)
                }
            }
        }

        let nextPhaseRaw: String
        if phaseRaw == PomodoroStorageKeys.focusPhase {
            nextPhaseRaw = PomodoroCyclePolicy.nextBreakPhaseRaw(
                completedFocusCount: completedFocusCount,
                longBreakInterval: PlanSettingsKeys.integer(
                    PomodoroStorageKeys.longBreakInterval,
                    fallback: 4,
                    defaults: defaults
                ),
                longBreakEnabled: PlanSettingsKeys.bool(
                    PomodoroStorageKeys.longBreakEnabled,
                    fallback: true,
                    defaults: defaults
                )
            )
        } else {
            nextPhaseRaw = PomodoroStorageKeys.focusPhase
        }

        defaults.set(nextPhaseRaw, forKey: PomodoroStorageKeys.phase)
        defaults.set(false, forKey: PomodoroStorageKeys.running)
        defaults.set(0.0, forKey: PomodoroStorageKeys.target)
        defaults.set(0.0, forKey: PomodoroStorageKeys.segmentStart)
        defaults.set(0, forKey: PomodoroStorageKeys.accumulated)
        defaults.set("", forKey: PomodoroStorageKeys.linkedPlanID)
        defaults.set(false, forKey: PomodoroStorageKeys.liveActivityVisible)
        defaults.set(false, forKey: PomodoroStorageKeys.phaseDurationIsPlanOwned)
        let nextDuration = durationSeconds(for: nextPhaseRaw, defaults: defaults)
        defaults.set(nextDuration, forKey: PomodoroStorageKeys.phaseDuration)
        defaults.set(nextDuration, forKey: PomodoroStorageKeys.remaining)
        return current(at: date, defaults: defaults)
    }

    @discardableResult
    static func toggle(
        at date: Date = Date(),
        defaults: UserDefaults = SharedPersistence.sharedDefaults
    ) -> PomodoroSharedState {
        let previousState = current(at: date, defaults: defaults)
        defaults.set(true, forKey: PomodoroStorageKeys.liveActivityVisible)
        if previousState.isRunning {
            let segmentStart = defaults.double(forKey: PomodoroStorageKeys.segmentStart)
            let elapsed = segmentStart > 0
                ? max(0, Int(date.timeIntervalSince1970 - segmentStart))
                : 0
            defaults.set(
                defaults.integer(forKey: PomodoroStorageKeys.accumulated) + elapsed,
                forKey: PomodoroStorageKeys.accumulated
            )
            defaults.set(previousState.remainingSeconds, forKey: PomodoroStorageKeys.remaining)
            defaults.set(false, forKey: PomodoroStorageKeys.running)
            defaults.set(0.0, forKey: PomodoroStorageKeys.target)
            defaults.set(0.0, forKey: PomodoroStorageKeys.segmentStart)
        } else {
            let seconds = previousState.remainingSeconds > 0
                ? previousState.remainingSeconds
                : durationSeconds(for: previousState.phaseRaw, defaults: defaults)
            defaults.set(seconds, forKey: PomodoroStorageKeys.remaining)
            defaults.set(true, forKey: PomodoroStorageKeys.running)
            defaults.set(date.timeIntervalSince1970, forKey: PomodoroStorageKeys.segmentStart)
            defaults.set(
                date.addingTimeInterval(TimeInterval(seconds)).timeIntervalSince1970,
                forKey: PomodoroStorageKeys.target
            )
        }
        return Self.current(at: date, defaults: defaults)
    }

    /// Focus seconds banked in the current phase, including the running segment.
    static func elapsedFocusSeconds(
        at date: Date,
        defaults: UserDefaults
    ) -> Int {
        var elapsed = defaults.integer(forKey: PomodoroStorageKeys.accumulated)
        let segmentStart = defaults.double(forKey: PomodoroStorageKeys.segmentStart)
        if defaults.bool(forKey: PomodoroStorageKeys.running), segmentStart > 0 {
            elapsed += max(0, Int(date.timeIntervalSince1970 - segmentStart))
        }
        return max(0, elapsed)
    }

    /// Ending a focus from the lock screen used to throw the elapsed minutes
    /// away. Persist them as a real record first — the phase is abandoned, but
    /// the time was genuinely spent, so the plan is only released, not completed.
    static func bankPartialFocusIfNeeded(
        at date: Date,
        defaults: UserDefaults = SharedPersistence.sharedDefaults
    ) {
        let phaseRaw = defaults.string(forKey: PomodoroStorageKeys.phase)
            ?? PomodoroStorageKeys.focusPhase
        guard phaseRaw == PomodoroStorageKeys.focusPhase else { return }
        let elapsed = elapsedFocusSeconds(at: date, defaults: defaults)
        guard elapsed >= 60 else { return }

        let rawTitle = (defaults.string(forKey: PomodoroStorageKeys.title) ?? "番茄专注")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let category = RecordCategory(
            rawValue: defaults.string(forKey: PomodoroStorageKeys.category) ?? ""
        ) ?? .study
        let linkedPlanID = UUID(
            uuidString: defaults.string(forKey: PomodoroStorageKeys.linkedPlanID) ?? ""
        )

        SharedPersistence.mutate { snapshot in
            FocusSessionEngine.record(
                title: rawTitle,
                category: category,
                startDate: date.addingTimeInterval(TimeInterval(-elapsed)),
                endDate: date,
                linkedCheckInID: linkedPlanID,
                completesPlan: false,
                records: &snapshot.records,
                checkInItems: &snapshot.checkInItems
            )
        }
        defaults.set("", forKey: PomodoroStorageKeys.linkedPlanID)
    }

    /// Hands a linked plan back to the checklist without writing a record.
    /// `bankPartialFocusIfNeeded` only fires past a minute, so without this a
    /// focus abandoned in its first seconds left its plan stuck at 进行中.
    static func releaseLinkedPlanIfNeeded(
        defaults: UserDefaults = SharedPersistence.sharedDefaults
    ) {
        guard
            let linkedPlanID = UUID(
                uuidString: defaults.string(forKey: PomodoroStorageKeys.linkedPlanID) ?? ""
            )
        else { return }

        SharedPersistence.mutate { snapshot in
            guard
                let index = snapshot.checkInItems.firstIndex(where: { $0.id == linkedPlanID }),
                snapshot.checkInItems[index].status == .inProgress
            else { return }
            snapshot.checkInItems[index].status = .planned
            snapshot.checkInItems[index].actualStartDate = nil
            snapshot.checkInItems[index].actualEndDate = nil
        }
        defaults.set("", forKey: PomodoroStorageKeys.linkedPlanID)
    }

    @discardableResult
    static func reset(
        at date: Date = Date(),
        defaults: UserDefaults = SharedPersistence.sharedDefaults
    ) -> PomodoroSharedState {
        let phaseRaw = defaults.string(forKey: PomodoroStorageKeys.phase)
            ?? PomodoroStorageKeys.focusPhase
        defaults.set(false, forKey: PomodoroStorageKeys.running)
        defaults.set(0.0, forKey: PomodoroStorageKeys.target)
        defaults.set(0.0, forKey: PomodoroStorageKeys.segmentStart)
        defaults.set(0, forKey: PomodoroStorageKeys.accumulated)
        defaults.set(false, forKey: PomodoroStorageKeys.liveActivityVisible)
        defaults.set(false, forKey: PomodoroStorageKeys.phaseDurationIsPlanOwned)
        let duration = durationSeconds(for: phaseRaw, defaults: defaults)
        defaults.set(duration, forKey: PomodoroStorageKeys.phaseDuration)
        defaults.set(
            duration,
            forKey: PomodoroStorageKeys.remaining
        )
        return Self.current(at: date, defaults: defaults)
    }

    static func setLiveActivityVisible(
        _ isVisible: Bool,
        defaults: UserDefaults = SharedPersistence.sharedDefaults
    ) {
        defaults.set(
            isVisible,
            forKey: PomodoroStorageKeys.liveActivityVisible
        )
    }

    private static func durationSeconds(for phaseRaw: String, defaults: UserDefaults) -> Int {
        let key: String
        let fallback: Int
        switch phaseRaw {
        case PomodoroStorageKeys.shortBreakPhase:
            key = PomodoroStorageKeys.shortBreakMinutes
            fallback = 5
        case PomodoroStorageKeys.longBreakPhase:
            key = PomodoroStorageKeys.longBreakMinutes
            fallback = 15
        default:
            key = PomodoroStorageKeys.focusMinutes
            fallback = 25
        }
        let value = defaults.object(forKey: key) == nil ? fallback : defaults.integer(forKey: key)
        return max(1, value) * 60
    }
}

enum PomodoroLiveActivityController {
    /// A termination-only gate. Merely putting Moji in the background must not
    /// dismiss or suppress the timer; the user still needs it on the lock
    /// screen. The gate only closes during an explicit process-termination
    /// callback, and is reopened on the next foreground reconciliation.
    private static let terminationSuppressionKey =
        "moji.pomodoro.liveActivity.suppressedForTermination"

    static func startOrUpdate(using state: PomodoroSharedState) async {
        guard !SharedPersistence.sharedDefaults.bool(
            forKey: terminationSuppressionKey
        ) else {
            await end(using: state)
            return
        }
        guard PlanSettingsKeys.bool(
            PlanSettingsKeys.liveActivitiesEnabled,
            fallback: true
        ) else {
            await end(using: state)
            return
        }
        guard state.shouldShowLiveActivity, state.remainingSeconds > 0 else {
            await end(using: state)
            return
        }

        // A suspended app cannot push an update when the phase ends, so the
        // card would otherwise sit frozen at 00:00 for the rest of the lock.
        // The stale date is the one redraw the system performs on its own:
        // put it a second past the end so the view is re-evaluated once the
        // countdown has visibly reached zero, and it can swap itself to the
        // 已结束 state.
        let content = ActivityContent(
            state: state.activityContentState,
            staleDate: state.targetDate?.addingTimeInterval(1)
        )
        let activities = Activity<PomodoroActivityAttributes>.activities
        if let activity = activities.first {
            await activity.update(content)
            for duplicate in activities.dropFirst() {
                await duplicate.end(content, dismissalPolicy: .immediate)
            }
            return
        }
        guard state.isRunning, ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        do {
            _ = try Activity.request(
                attributes: PomodoroActivityAttributes(sessionID: UUID()),
                content: content,
                pushType: nil
            )
        } catch {
            // The timer and local notification remain available if Live Activities are disabled.
        }
    }

    static func end(using state: PomodoroSharedState) async {
        PomodoroSharedState.setLiveActivityVisible(false)
        let content = ActivityContent(
            state: state.activityContentState,
            staleDate: nil
        )
        for activity in Activity<PomodoroActivityAttributes>.activities {
            await activity.end(
                content,
                dismissalPolicy: .immediate
            )
        }
    }

    static func reconcile(at date: Date = Date()) async {
        // A new/foregrounded process may publish the timer again. This clears
        // the best-effort termination gate from the previous process.
        SharedPersistence.sharedDefaults.set(
            false,
            forKey: terminationSuppressionKey
        )
        SharedPersistence.sharedDefaults.synchronize()
        let state = PomodoroSharedState.current(at: date)
        if state.shouldShowLiveActivity, state.remainingSeconds > 0 {
            await startOrUpdate(using: state)
        } else {
            // Opening the app also repairs expired or orphaned activities.
            await end(using: state)
        }
    }

    static func appDidEnterBackground(at date: Date = Date()) async {
        // Backgrounding is normal timer use: keep the current running or paused
        // activity visible and merely make sure its content is up to date.
        let state = PomodoroSharedState.current(at: date)
        if state.shouldShowLiveActivity, state.remainingSeconds > 0 {
            await startOrUpdate(using: state)
        } else {
            await end(using: state)
        }
    }

    /// UIKit does not guarantee meaningful asynchronous work after a process
    /// has been killed, so persist the suppression synchronously first, then
    /// make a best-effort ActivityKit dismissal from `applicationWillTerminate`.
    static func prepareForTermination() {
        SharedPersistence.sharedDefaults.set(
            true,
            forKey: terminationSuppressionKey
        )
        PomodoroSharedState.setLiveActivityVisible(false)
        SharedPersistence.sharedDefaults.synchronize()
    }

    static func appWillTerminate(at date: Date = Date()) async {
        prepareForTermination()
        let state = PomodoroSharedState.current(at: date)
        await end(using: state)
    }
}

private enum PomodoroIntentNotification {
    static let identifier = "minuteplan.pomodoro.phase"

    static func sync(with state: PomodoroSharedState) async {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [identifier])
        guard
            state.isRunning,
            let targetDate = state.targetDate,
            targetDate > Date()
        else { return }

        let status = await center.notificationSettings().authorizationStatus
        guard [.authorized, .provisional, .ephemeral].contains(status) else { return }
        let content = UNMutableNotificationContent()
        content.title = "\(state.phaseName)结束"
        content.body = state.phaseName == "专注"
            ? "\(state.title) 已完成，休息一下。"
            : "休息结束，可以开始下一轮专注。"
        content.sound = .default
        let trigger = UNTimeIntervalNotificationTrigger(
            timeInterval: max(1, targetDate.timeIntervalSinceNow),
            repeats: false
        )
        try? await center.add(
            UNNotificationRequest(identifier: identifier, content: content, trigger: trigger)
        )
    }

    static func cancel() {
        UNUserNotificationCenter.current().removePendingNotificationRequests(
            withIdentifiers: [identifier]
        )
    }
}

struct TogglePomodoroLiveIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "暂停或继续番茄钟"
    static var description = IntentDescription("直接从锁屏或灵动岛暂停、继续番茄钟。")
    static var openAppWhenRun: Bool = false

    func perform() async throws -> some IntentResult {
        if let settled = PomodoroSharedState.settleExpiredPhaseIfNeeded() {
            await PomodoroLiveActivityController.end(using: settled)
            PomodoroIntentNotification.cancel()
            WidgetCenter.shared.reloadAllTimelines()
            return .result()
        }
        let state = PomodoroSharedState.toggle()
        await PomodoroLiveActivityController.startOrUpdate(using: state)
        await PomodoroIntentNotification.sync(with: state)
        WidgetCenter.shared.reloadAllTimelines()
        return .result()
    }
}

struct ResetPomodoroLiveIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "结束本轮番茄钟"
    static var description = IntentDescription("打开墨记，选择是否保留本轮记录后结束。")
    static var openAppWhenRun: Bool = true

    func perform() async throws -> some IntentResult {
        if let settled = PomodoroSharedState.settleExpiredPhaseIfNeeded() {
            await PomodoroLiveActivityController.end(using: settled)
            PomodoroIntentNotification.cancel()
            WidgetCenter.shared.reloadAllTimelines()
            return .result()
        }
        // A Live Activity cannot present a multi-choice confirmation in place.
        // Hand the choice to the app instead of silently keeping or discarding
        // time on the user's behalf.
        SharedPersistence.sharedDefaults.set(
            true,
            forKey: PomodoroStorageKeys.terminationConfirmationRequested
        )
        SharedPersistence.sharedDefaults.synchronize()
        return .result()
    }
}

/// The Apple Watch counterpart of `ResetPomodoroLiveIntent`.
///
/// The lock screen can hand the keep/discard choice to the app because tapping
/// there can open it. A Smart Stack tap on the watch cannot — Moji has no watch
/// app — so the button did nothing at all. Ending here therefore resolves on the
/// spot with the safe half of that choice: focus time already spent is written
/// as a real record, and the linked plan goes back to the checklist instead of
/// being marked complete.
struct EndPomodoroLiveIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "结束本轮番茄钟并保留记录"
    static var description = IntentDescription("在手表上直接结束番茄钟，已专注的时间会写入记录。")
    static var openAppWhenRun: Bool = false

    func perform() async throws -> some IntentResult {
        if let settled = PomodoroSharedState.settleExpiredPhaseIfNeeded() {
            await PomodoroLiveActivityController.end(using: settled)
            PomodoroIntentNotification.cancel()
            WidgetCenter.shared.reloadAllTimelines()
            return .result()
        }

        let now = Date()
        PomodoroSharedState.bankPartialFocusIfNeeded(at: now)
        PomodoroSharedState.releaseLinkedPlanIfNeeded()
        let state = PomodoroSharedState.reset(at: now)
        await PomodoroLiveActivityController.end(using: state)
        PomodoroIntentNotification.cancel()
        WidgetCenter.shared.reloadAllTimelines()
        return .result()
    }
}

struct ToggleFocusIntent: AppIntent {
    static var title: LocalizedStringResource = "开始或结束专注"
    static var description = IntentDescription("在桌面小组件中快速开始或完成一条学习记录。")
    static var openAppWhenRun: Bool = false

    func perform() async throws -> some IntentResult {
        // A pomodoro focus already claims the user's attention and will write
        // its own record. Starting a second parallel session from the widget
        // used to double-count the same work.
        guard !PomodoroSharedState.hasActiveFocusClaim() else {
            return .result()
        }

        var completedNotificationIdentifier: String?
        var generatedSuccessor: CheckInItem?
        SharedPersistence.mutate { snapshot in
            if let active = snapshot.activeSession {
                let endDate = Date()
                let safeEndDate = max(endDate, active.startedAt.addingTimeInterval(1))
                snapshot.records.append(
                    TimeRecord(
                        title: active.title,
                        category: active.category,
                        startDate: active.startedAt,
                        endDate: safeEndDate,
                        note: "由桌面小组件记录",
                        checkInItemID: active.checkInItemID
                    )
                )
                if
                    let itemID = active.checkInItemID,
                    let index = snapshot.checkInItems.firstIndex(where: { $0.id == itemID })
                {
                    snapshot.checkInItems[index].status = .completed
                    snapshot.checkInItems[index].actualStartDate = active.startedAt
                    snapshot.checkInItems[index].actualEndDate = safeEndDate
                    snapshot.checkInItems[index].completedAt = endDate
                    let completedItem = snapshot.checkInItems[index]
                    completedNotificationIdentifier = completedItem.notificationIdentifier
                    generatedSuccessor = PlanRecurrenceEngine.appendSuccessorIfNeeded(
                        after: completedItem,
                        to: &snapshot.checkInItems
                    )
                }
                snapshot.activeSession = nil
            } else {
                let now = Date()
                // Whatever the checklist counts as today's work is what this
                // button should start — including a plan carried over from an
                // earlier day, which the old same-day filter used to skip.
                let nextItem = WidgetSelectionEngine
                    .todayPlans(in: snapshot, now: now)
                    .filter { $0.status == .planned || $0.status == .inProgress }
                    .sorted { $0.scheduledStart < $1.scheduledStart }
                    .first

                if let nextItem {
                    snapshot.activeSession = ActiveSession(
                        title: nextItem.title,
                        category: nextItem.category,
                        startedAt: now,
                        checkInItemID: nextItem.id,
                        plannedDurationMinutes: nextItem.plannedDurationMinutes
                    )
                    if let index = snapshot.checkInItems.firstIndex(where: { $0.id == nextItem.id }) {
                        snapshot.checkInItems[index].status = .inProgress
                        snapshot.checkInItems[index].actualStartDate = now
                    }
                } else {
                    snapshot.activeSession = ActiveSession(
                        title: "快速专注",
                        category: .study,
                        startedAt: now
                    )
                }
            }
        }

        if let completedNotificationIdentifier {
            UNUserNotificationCenter.current().removePendingNotificationRequests(
                withIdentifiers: [completedNotificationIdentifier]
            )
        }
        if let generatedSuccessor {
            await WidgetPlanNotification.schedule(generatedSuccessor)
        }
        WidgetCenter.shared.reloadAllTimelines()
        return .result()
    }
}

/// What the three configurable widgets actually show.
///
/// The picking rules live here, outside the widget extension, so they are the
/// same rules the app tests run against — a widget that quietly disagrees with
/// the list it mirrors is the kind of bug nobody reports and everybody notices.
enum WidgetSelectionEngine {
    /// Every board is three rows tall.
    static let maximumCount = 3

    /// Dates for one of the two countdown boards, in the user's chosen slot
    /// order. Deleted selections fall out, and an empty selection means "keep
    /// showing whatever is nearest", which is what a freshly placed widget
    /// should do before anyone has configured it.
    static func countdowns(
        selecting identifiers: [UUID],
        from events: [CountdownEvent],
        past: Bool,
        now: Date = Date()
    ) -> [CountdownEvent] {
        let available = CountdownOrdering.sorted(
            events.filter { $0.hasPassed(relativeTo: now) == past },
            mode: .date,
            relativeTo: now
        )
        let selected = identifiers.compactMap { id in
            available.first(where: { $0.id == id })
        }
        return selected.isEmpty
            ? Array(available.prefix(maximumCount))
            : Array(selected.prefix(maximumCount))
    }

    /// Today's plans, in the order the checklist shows them — same membership
    /// rule as the app, so the board and 今日计划 can never disagree about what
    /// today holds. Finished plans stay only on the day they were finished.
    static func todayPlans(
        in snapshot: PlanSnapshot,
        now: Date = Date(),
        carriesOverUnfinished: Bool = PlanSettingsKeys.carriesOverUnfinishedPlans,
        calendar: Calendar = .current
    ) -> [CheckInItem] {
        snapshot.checkInItems
            .filter { item in
                guard item.kind == .planned, item.status != .skipped else { return false }
                return item.isVisibleInChecklist(
                    on: now,
                    carriesOverUnfinished: carriesOverUnfinished,
                    calendar: calendar
                )
            }
            .sorted {
                if $0.isCompleted != $1.isCompleted {
                    return !$0.isCompleted
                }
                if $0.effectiveScheduleKind.sortOrder != $1.effectiveScheduleKind.sortOrder {
                    return $0.effectiveScheduleKind.sortOrder
                        < $1.effectiveScheduleKind.sortOrder
                }
                return $0.scheduledStart < $1.scheduledStart
            }
    }

    /// Pinned plans first, in slot order, then today's list fills what is left.
    /// Without the filling a board of finished plans would stay finished all
    /// day; with `fillsWithToday` off it stays exactly as pinned.
    static func plans(
        selecting identifiers: [UUID],
        fillsWithToday: Bool,
        in snapshot: PlanSnapshot,
        now: Date = Date()
    ) -> [CheckInItem] {
        let selected = identifiers.compactMap { id in
            snapshot.checkInItems.first(where: { $0.id == id })
        }
        guard fillsWithToday || selected.isEmpty else {
            return Array(selected.prefix(maximumCount))
        }
        var combined = selected
        for plan in todayPlans(in: snapshot, now: now)
        where !combined.contains(where: { $0.id == plan.id }) {
            guard combined.count < maximumCount else { break }
            combined.append(plan)
        }
        return Array(combined.prefix(maximumCount))
    }

    /// Day counts only change at midnight, so that is the only moment a board
    /// has to be rebuilt.
    static func refreshDate(after now: Date, calendar: Calendar = .current) -> Date {
        let tomorrow = calendar.date(
            byAdding: .day,
            value: 1,
            to: calendar.startOfDay(for: now)
        )
        return tomorrow?.addingTimeInterval(60) ?? now.addingTimeInterval(6 * 60 * 60)
    }
}

/// The `moji://` links the widgets open. Built and parsed in one place so a
/// widget can never hand the app a shape it does not understand.
enum MojiDeepLink {
    static var checklist: URL? { URL(string: "moji://checklist") }
    static var countdowns: URL? { URL(string: "moji://countdowns") }

    static func planEditor(_ id: UUID) -> URL? {
        URL(string: "moji://plan?id=\(id.uuidString)")
    }

    static func planID(from url: URL) -> UUID? {
        guard url.host == "plan" else { return nil }
        let value = URLComponents(url: url, resolvingAgainstBaseURL: false)?
            .queryItems?
            .first(where: { $0.name == "id" })?
            .value
        return value.flatMap(UUID.init(uuidString:))
    }
}

struct QuickPlanPreset: Codable, Identifiable, Equatable {
    let id: String
    var buttonTitle: String
    var planTitle: String
    var category: RecordCategory?

    var symbolName: String {
        category?.symbolName ?? "plus"
    }
}

enum QuickPlanPresetStore {
    static let maximumCount = 3
    static let defaults = [
        QuickPlanPreset(
            id: "general",
            buttonTitle: "待办",
            planTitle: "新计划",
            category: nil
        ),
        QuickPlanPreset(
            id: "study",
            buttonTitle: "学习",
            planTitle: "学习计划",
            category: .study
        ),
        QuickPlanPreset(
            id: "work",
            buttonTitle: "工作",
            planTitle: "工作计划",
            category: .work
        )
    ]

    static var defaultJSON: String {
        json(for: defaults)
    }

    static func load(
        defaults userDefaults: UserDefaults = SharedPersistence.sharedDefaults
    ) -> [QuickPlanPreset] {
        presets(from: userDefaults.string(forKey: PlanSettingsKeys.quickPlanPresets))
    }

    static func presets(from rawJSON: String?) -> [QuickPlanPreset] {
        guard
            let rawJSON,
            let data = rawJSON.data(using: .utf8),
            let decoded = try? JSONDecoder().decode([QuickPlanPreset].self, from: data)
        else { return defaults }

        var seen = Set<String>()
        var result = decoded.compactMap { preset -> QuickPlanPreset? in
            guard
                !preset.id.isEmpty,
                seen.insert(preset.id).inserted,
                let buttonTitle = normalized(preset.buttonTitle, limit: 8),
                let planTitle = normalized(preset.planTitle, limit: 24),
                preset.category != .customSummary
            else { return nil }
            return QuickPlanPreset(
                id: preset.id,
                buttonTitle: buttonTitle,
                planTitle: planTitle,
                category: preset.category
            )
        }

        for preset in defaults where result.count < maximumCount && !seen.contains(preset.id) {
            result.append(preset)
            seen.insert(preset.id)
        }
        return Array(result.prefix(maximumCount))
    }

    static func json(for presets: [QuickPlanPreset]) -> String {
        let normalizedPresets = Self.presets(from: encodedJSON(presets))
        return encodedJSON(normalizedPresets)
    }

    static func save(
        _ presets: [QuickPlanPreset],
        defaults: UserDefaults = SharedPersistence.sharedDefaults
    ) {
        defaults.set(json(for: presets), forKey: PlanSettingsKeys.quickPlanPresets)
    }

    static func normalized(_ value: String, limit: Int) -> String? {
        let result = String(
            value
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .prefix(limit)
        )
        return result.isEmpty ? nil : result
    }

    private static func encodedJSON(_ presets: [QuickPlanPreset]) -> String {
        guard
            let data = try? JSONEncoder().encode(Array(presets.prefix(maximumCount))),
            let string = String(data: data, encoding: .utf8)
        else { return "[]" }
        return string
    }
}

enum QuickPlanKind: String, AppEnum {
    case general
    case study
    case work

    static var typeDisplayRepresentation = TypeDisplayRepresentation(name: "快速计划类型")
    static var caseDisplayRepresentations: [QuickPlanKind: DisplayRepresentation] = [
        .general: "待办",
        .study: "学习",
        .work: "工作"
    ]

    var baseTitle: String {
        switch self {
        case .general: return "新计划"
        case .study: return "学习计划"
        case .work: return "工作计划"
        }
    }

    var caseDisplayTitle: String {
        switch self {
        case .general: return "待办"
        case .study: return "学习"
        case .work: return "工作"
        }
    }

    func category(defaultCategory: RecordCategory) -> RecordCategory {
        switch self {
        case .study: return .study
        case .work: return .work
        case .general: return defaultCategory
        }
    }
}

enum QuickPlanMutationEngine {
    @discardableResult
    static func append(
        kind: QuickPlanKind,
        at date: Date,
        plannedMinutes: Int,
        defaultCategory: RecordCategory,
        calendar: Calendar = .current,
        to snapshot: inout PlanSnapshot
    ) -> CheckInItem {
        let preset = QuickPlanPreset(
            id: kind.rawValue,
            buttonTitle: kind.caseDisplayTitle,
            planTitle: kind.baseTitle,
            category: kind == .general ? nil : kind.category(defaultCategory: defaultCategory)
        )
        return append(
            preset: preset,
            at: date,
            plannedMinutes: plannedMinutes,
            defaultCategory: defaultCategory,
            calendar: calendar,
            to: &snapshot
        )
    }

    @discardableResult
    static func append(
        preset: QuickPlanPreset,
        at date: Date,
        plannedMinutes: Int,
        defaultCategory: RecordCategory,
        calendar: Calendar = .current,
        to snapshot: inout PlanSnapshot
    ) -> CheckInItem {
        let day = calendar.startOfDay(for: date)
        let baseTitle = preset.planTitle
        let matchingCount = snapshot.checkInItems.filter {
            calendar.isDate($0.scheduledStart, inSameDayAs: day)
                && ($0.title == baseTitle || $0.title.hasPrefix("\(baseTitle) "))
        }.count
        let title = matchingCount == 0
            ? baseTitle
            : "\(baseTitle) \(matchingCount + 1)"
        let item = CheckInItem(
            title: title,
            category: preset.category ?? defaultCategory,
            scheduledStart: day,
            plannedMinutes: max(1, plannedMinutes),
            scheduleKind: .allDay,
            detailsConfigured: false,
            plannedDurationEnabled: false
        )
        snapshot.checkInItems.append(item)
        return item
    }

    struct ToggleResult {
        var notificationIdentifierToRemove: String?
        var notificationItemToSchedule: CheckInItem?
    }

    @discardableResult
    static func toggle(
        planID: UUID,
        at date: Date,
        in snapshot: inout PlanSnapshot
    ) -> ToggleResult {
        var result = ToggleResult()
        guard let index = snapshot.checkInItems.firstIndex(where: { $0.id == planID }) else {
            return result
        }

        if snapshot.checkInItems[index].isCompleted {
            let completedItem = snapshot.checkInItems[index]
            let removedSuccessor = PlanRecurrenceEngine.removePendingSuccessorIfNeeded(
                after: completedItem,
                from: &snapshot.checkInItems
            )
            result.notificationIdentifierToRemove = removedSuccessor?.notificationIdentifier
            guard
                let restoredIndex = snapshot.checkInItems.firstIndex(where: { $0.id == planID })
            else { return result }
            snapshot.checkInItems[restoredIndex].status = .planned
            snapshot.checkInItems[restoredIndex].completedAt = nil
            snapshot.checkInItems[restoredIndex].actualStartDate = nil
            snapshot.checkInItems[restoredIndex].actualEndDate = nil
            PlanCompletionReversion.removeCompletionRecord(
                for: completedItem,
                from: &snapshot.records
            )
            result.notificationItemToSchedule = snapshot.checkInItems[restoredIndex]
            return result
        }

        if snapshot.checkInItems[index].status == .skipped {
            let skippedItem = snapshot.checkInItems[index]
            let removedSuccessor = PlanRecurrenceEngine.removePendingSuccessorIfNeeded(
                after: skippedItem,
                from: &snapshot.checkInItems
            )
            result.notificationIdentifierToRemove = removedSuccessor?.notificationIdentifier
            guard
                let restoredIndex = snapshot.checkInItems.firstIndex(where: { $0.id == planID })
            else { return result }
            snapshot.checkInItems[restoredIndex].status = .planned
            snapshot.checkInItems[restoredIndex].completedAt = nil
            result.notificationItemToSchedule = snapshot.checkInItems[restoredIndex]
            return result
        }

        if snapshot.activeSession?.checkInItemID == planID, let active = snapshot.activeSession {
            let safeEndDate = max(date, active.startedAt.addingTimeInterval(1))
            snapshot.records.append(
                TimeRecord(
                    title: active.title,
                    category: active.category,
                    startDate: active.startedAt,
                    endDate: safeEndDate,
                    checkInItemID: planID
                )
            )
            snapshot.checkInItems[index].actualStartDate = active.startedAt
            snapshot.checkInItems[index].actualEndDate = safeEndDate
            snapshot.activeSession = nil
        }

        snapshot.checkInItems[index].status = .completed
        snapshot.checkInItems[index].completedAt = date
        let completedItem = snapshot.checkInItems[index]
        result.notificationIdentifierToRemove = completedItem.notificationIdentifier
        result.notificationItemToSchedule = PlanRecurrenceEngine.appendSuccessorIfNeeded(
            after: completedItem,
            to: &snapshot.checkInItems
        )
        return result
    }
}

struct AddQuickPlanIntent: AppIntent {
    static var title: LocalizedStringResource = "添加快速计划"
    static var description = IntentDescription("从桌面小组件一键添加今天的待办、学习或工作计划。")
    static var openAppWhenRun: Bool = false

    @Parameter(title: "预设标识")
    var presetID: String

    init() {
        presetID = QuickPlanPresetStore.defaults[0].id
    }

    init(presetID: String) {
        self.presetID = presetID
    }

    func perform() async throws -> some IntentResult {
        let now = Date()
        let defaults = SharedPersistence.sharedDefaults
        let defaultCategory = RecordCategory(
            rawValue: defaults.string(forKey: PlanSettingsKeys.defaultCategory) ?? ""
        ) ?? .study
        let plannedMinutes = max(
            1,
            PlanSettingsKeys.integer(
                PlanSettingsKeys.defaultPlannedMinutes,
                fallback: 25,
                defaults: defaults
            )
        )
        let presets = QuickPlanPresetStore.load(defaults: defaults)
        let preset = presets.first(where: { $0.id == presetID }) ?? presets[0]

        SharedPersistence.mutate { snapshot in
            QuickPlanMutationEngine.append(
                preset: preset,
                at: now,
                plannedMinutes: plannedMinutes,
                defaultCategory: defaultCategory,
                to: &snapshot
            )
        }

        WidgetCenter.shared.reloadAllTimelines()
        return .result()
    }
}

struct ToggleQuickPlanIntent: AppIntent {
    static var title: LocalizedStringResource = "完成或恢复计划"
    static var description = IntentDescription("直接在桌面小组件中完成或恢复今日计划。")
    static var openAppWhenRun: Bool = false

    @Parameter(title: "计划标识")
    var planID: String

    init() {
        planID = ""
    }

    init(planID: UUID) {
        self.planID = planID.uuidString
    }

    func perform() async throws -> some IntentResult {
        guard let id = UUID(uuidString: planID) else { return .result() }
        let now = Date()
        var toggleResult = QuickPlanMutationEngine.ToggleResult()
        SharedPersistence.mutate { snapshot in
            toggleResult = QuickPlanMutationEngine.toggle(
                planID: id,
                at: now,
                in: &snapshot
            )
        }

        if let identifier = toggleResult.notificationIdentifierToRemove {
            UNUserNotificationCenter.current().removePendingNotificationRequests(
                withIdentifiers: [identifier]
            )
        }
        if let item = toggleResult.notificationItemToSchedule {
            await WidgetPlanNotification.schedule(item)
        }
        WidgetCenter.shared.reloadAllTimelines()
        return .result()
    }
}

private enum WidgetPlanNotification {
    static func schedule(_ item: CheckInItem) async {
        guard PlanSettingsKeys.bool(
            PlanSettingsKeys.planNotificationsEnabled,
            fallback: true
        ) else { return }
        guard let reminderMinutes = item.reminderMinutesBefore else { return }
        let center = UNUserNotificationCenter.current()
        let status = await center.notificationSettings().authorizationStatus
        guard [.authorized, .provisional, .ephemeral].contains(status) else { return }

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
        guard fireDate > Date() else { return }

        let content = UNMutableNotificationContent()
        content.title = item.title
        content.body = item.timingSummaryText
        content.sound = PlanSettingsKeys.bool(
            PlanSettingsKeys.notificationSoundEnabled,
            fallback: true
        ) ? .default : nil
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
