import Combine
import Foundation
import SwiftUI
import UIKit

enum PomodoroPhase: String, Identifiable, CaseIterable {
    case focus
    case shortBreak
    case longBreak

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .focus: return "专注"
        case .shortBreak: return "短休息"
        case .longBreak: return "长休息"
        }
    }

    var symbolName: String {
        switch self {
        case .focus: return "timer"
        case .shortBreak: return "cup.and.saucer.fill"
        case .longBreak: return "leaf.fill"
        }
    }

    var isBreak: Bool {
        self != .focus
    }

    var durationRange: ClosedRange<Int> {
        switch self {
        case .focus: return 1...120
        case .shortBreak: return 1...30
        case .longBreak: return 1...60
        }
    }

    /// Deliberately short. The quick menu is for the three or four lengths
    /// people actually use; anything else goes through 自定义时长.
    var durationPresets: [Int] {
        switch self {
        case .focus: return [25, 30, 45, 60]
        case .shortBreak: return [5, 10, 15]
        case .longBreak: return [15, 20, 30]
        }
    }

    var settingsKey: String {
        switch self {
        case .focus: return PomodoroStorageKeys.focusMinutes
        case .shortBreak: return PomodoroStorageKeys.shortBreakMinutes
        case .longBreak: return PomodoroStorageKeys.longBreakMinutes
        }
    }

    var defaultMinutes: Int {
        switch self {
        case .focus: return 25
        case .shortBreak: return 5
        case .longBreak: return 15
        }
    }
}

/// A visual milestone the ink layer animates. The engine only announces what
/// happened; how much motion is drawn stays a view concern.
enum PomodoroMotionEvent: Equatable {
    case idle
    case started(PomodoroPhase)
    case paused(PomodoroPhase)
    /// A phase ran to its natural end and handed over to `next`.
    case phaseFinished(from: PomodoroPhase, to: PomodoroPhase)
    /// The user skipped or reset, so the stroke is washed off the paper.
    case washedAway(PomodoroPhase)
}

/// The single owner of pomodoro state.
///
/// Before v1.16 the timer lived inside `PomodoroView`. That meant it only
/// advanced while the 番茄钟 tab was on screen, and it ran completely
/// independently of `PlanStore.activeSession` — both could complete the same
/// plan and each wrote its own `TimeRecord`. The engine now owns the clock for
/// the whole app and routes every write through `PlanStore`, so “正在进行”
/// has exactly one meaning no matter where the user started it.
///
/// State still lives in the App Group defaults under the original keys, so the
/// widget, the Live Activity intents and existing JSON backups keep working.
@MainActor
final class PomodoroEngine: ObservableObject {
    private unowned let store: PlanStore
    private let defaults: UserDefaults
    private var ticker: AnyCancellable?

    @Published private(set) var phase: PomodoroPhase = .focus
    @Published private(set) var isRunning = false
    @Published private(set) var title = "番茄专注"
    @Published private(set) var category: RecordCategory = .study
    @Published private(set) var linkedPlanID: UUID?
    @Published private(set) var completedFocusCount = 0
    /// Preference values are mirrored here rather than read straight from
    /// `UserDefaults` in the view body. Reading them live meant a change had to
    /// be announced with a manual `objectWillChange` from inside `onAppear` —
    /// i.e. from within a view update — which SwiftUI is free to ignore, and
    /// the screen would keep showing the values it came in with.
    @Published private(set) var focusMinutes = 25
    @Published private(set) var shortBreakMinutes = 5
    @Published private(set) var longBreakMinutes = 15
    @Published private(set) var longBreakEnabled = true
    /// Set while a finished phase is being held on screen. The ring stays at a
    /// full circle so the completed stroke can actually be seen — previously the
    /// phase advanced the instant it ended and the finished ring never showed.
    @Published private(set) var completedPhase: PomodoroPhase?
    @Published private(set) var pendingNextPhase: PomodoroPhase?
    @Published private(set) var motionEvent: PomodoroMotionEvent = .idle
    /// Incremented on every milestone so `.sensoryFeedback` and one-shot ink
    /// animations have a value to observe even when the event repeats.
    @Published private(set) var motionToken = 0

    /// Seconds already spent in this phase across finished (paused) segments.
    private var accumulatedSeconds: Int {
        get { defaults.integer(forKey: PomodoroStorageKeys.accumulated) }
        set { defaults.set(max(0, newValue), forKey: PomodoroStorageKeys.accumulated) }
    }

    private var segmentStartTimestamp: Double {
        get { defaults.double(forKey: PomodoroStorageKeys.segmentStart) }
        set { defaults.set(newValue, forKey: PomodoroStorageKeys.segmentStart) }
    }

    private var targetTimestamp: Double {
        get { defaults.double(forKey: PomodoroStorageKeys.target) }
        set { defaults.set(newValue, forKey: PomodoroStorageKeys.target) }
    }

    private var storedRemainingSeconds: Int {
        get { defaults.integer(forKey: PomodoroStorageKeys.remaining) }
        set { defaults.set(max(0, newValue), forKey: PomodoroStorageKeys.remaining) }
    }

    private var storedPhaseDurationSeconds: Int {
        get { defaults.integer(forKey: PomodoroStorageKeys.phaseDuration) }
        set { defaults.set(max(1, newValue), forKey: PomodoroStorageKeys.phaseDuration) }
    }

    /// True while the current phase length came from a linked plan's estimate
    /// rather than from 设置. Only settings-owned durations follow later
    /// preference edits, so bringing a 45 分钟 plan into focus is not silently
    /// rewritten back to the default 25 分钟.
    private var phaseDurationIsPlanOwned: Bool {
        get { defaults.bool(forKey: PomodoroStorageKeys.phaseDurationIsPlanOwned) }
        set { defaults.set(newValue, forKey: PomodoroStorageKeys.phaseDurationIsPlanOwned) }
    }

    init(store: PlanStore, defaults: UserDefaults = SharedPersistence.sharedDefaults) {
        self.store = store
        self.defaults = defaults
        syncFromStorage()
        repairStateIfNeeded()
        settleExpiredPhaseIfNeeded(at: Date())
        updateTicker()
    }

    // MARK: - Derived state

    var phaseDurationSeconds: Int {
        let stored = storedPhaseDurationSeconds
        return stored > 0 ? stored : defaultDurationSeconds(for: phase)
    }

    var targetDate: Date? {
        guard isRunning, targetTimestamp > 0 else { return nil }
        return Date(timeIntervalSince1970: targetTimestamp)
    }

    func remainingSeconds(at date: Date = Date()) -> Int {
        guard isRunning, targetTimestamp > 0 else {
            return max(0, storedRemainingSeconds)
        }
        return max(0, Int(ceil(targetTimestamp - date.timeIntervalSince1970)))
    }

    func progress(at date: Date) -> Double {
        ContinuousTimerProgress.elapsedFraction(
            durationSeconds: phaseDurationSeconds,
            storedRemainingSeconds: storedRemainingSeconds,
            targetTimestamp: targetTimestamp,
            isRunning: isRunning,
            at: date
        )
    }

    func clockText(at date: Date = Date()) -> String {
        let seconds = remainingSeconds(at: date)
        return String(format: "%02d:%02d", seconds / 60, seconds % 60)
    }

    func minutes(for phase: PomodoroPhase) -> Int {
        switch phase {
        case .focus: return focusMinutes
        case .shortBreak: return shortBreakMinutes
        case .longBreak: return longBreakMinutes
        }
    }

    private func storedMinutes(for phase: PomodoroPhase) -> Int {
        let stored = PlanSettingsKeys.integer(
            phase.settingsKey,
            fallback: phase.defaultMinutes,
            defaults: defaults
        )
        return min(phase.durationRange.upperBound, max(phase.durationRange.lowerBound, stored))
    }

    /// Focus seconds banked in this phase, including the running segment.
    func elapsedFocusSeconds(at date: Date) -> Int {
        var elapsed = accumulatedSeconds
        if isRunning, segmentStartTimestamp > 0 {
            elapsed += max(0, Int(date.timeIntervalSince1970 - segmentStartTimestamp))
        }
        return max(0, elapsed)
    }

    // MARK: - Transport

    var isAwaitingHandoff: Bool {
        completedPhase != nil
    }

    /// Running, paused, finished-but-not-dismissed, or merely linked to a plan.
    /// The dashboard uses this broader definition so a paused focus never
    /// disappears while it is still blocking another focus session.
    var hasActiveSession: Bool {
        isRunning
            || isAwaitingHandoff
            || defaults.bool(forKey: PomodoroStorageKeys.liveActivityVisible)
            || accumulatedSeconds > 0
            || remainingSeconds() < phaseDurationSeconds
            || linkedPlanID != nil
    }

    func canKeepCurrentRecord(at date: Date = Date()) -> Bool {
        phase == .focus
            && !isAwaitingHandoff
            && elapsedFocusSeconds(at: date) > 0
    }

    var canChangeLinkedPlan: Bool {
        phase == .focus
            && !isRunning
            && !isAwaitingHandoff
            && !defaults.bool(forKey: PomodoroStorageKeys.liveActivityVisible)
            && accumulatedSeconds == 0
            && remainingSeconds() >= phaseDurationSeconds
    }

    /// Leaves the finished-phase screen and moves on. Called by the 继续 button,
    /// or automatically after a short hold when 自动衔接 is on.
    func continueToNextPhase(startImmediately: Bool = false) {
        guard let next = pendingNextPhase, let finished = completedPhase else { return }
        applyPhase(next)
        // The full circle washing off the paper belongs here, not at the moment
        // the phase ended — that is when the user actually leaves it behind.
        announce(.washedAway(finished))
        if startImmediately {
            start()
        }
    }

    func start(at date: Date = Date()) {
        if isAwaitingHandoff {
            // Pressing play on the finished screen means "go on to the next one".
            continueToNextPhase(startImmediately: true)
            return
        }
        guard !isRunning else { return }
        // A widget-started session and a pomodoro focus are the same claim on
        // the user's attention. The older session is banked as real time and
        // stands down rather than running alongside and double-counting.
        if phase == .focus, store.activeSession != nil {
            store.yieldActiveSession(at: date)
        }

        let seconds = storedRemainingSeconds > 0 ? storedRemainingSeconds : phaseDurationSeconds
        if storedPhaseDurationSeconds <= 0 {
            storedPhaseDurationSeconds = max(seconds, defaultDurationSeconds(for: phase))
        }
        storedRemainingSeconds = seconds
        segmentStartTimestamp = date.timeIntervalSince1970
        targetTimestamp = date.addingTimeInterval(TimeInterval(seconds)).timeIntervalSince1970
        isRunning = true
        defaults.set(true, forKey: PomodoroStorageKeys.running)
        defaults.set(liveActivitiesEnabled, forKey: PomodoroStorageKeys.liveActivityVisible)

        if phase == .focus, let planID = linkedPlanID {
            store.beginPomodoroFocus(id: planID, at: date)
        }

        announce(.started(phase))
        updateTicker()
        publishRunningState()
    }

    func pause(at date: Date = Date()) {
        guard isRunning else { return }
        accumulatedSeconds = elapsedFocusSeconds(at: date)
        storedRemainingSeconds = remainingSeconds(at: date)
        isRunning = false
        targetTimestamp = 0
        segmentStartTimestamp = 0
        defaults.set(false, forKey: PomodoroStorageKeys.running)
        defaults.set(liveActivitiesEnabled, forKey: PomodoroStorageKeys.liveActivityVisible)

        PlanNotificationService.shared.cancelPomodoroCompletion()
        announce(.paused(phase))
        updateTicker()

        let state = PomodoroSharedState.current(at: date)
        Task { await PomodoroLiveActivityController.startOrUpdate(using: state) }
    }

    func toggle(at date: Date = Date()) {
        isRunning ? pause(at: date) : start(at: date)
    }

    /// Ends the current phase without forcing a cycle handoff. When the user
    /// explicitly chooses to keep a focus record, even a very short session is
    /// retained; choosing not to keep it truly discards the elapsed interval.
    func terminate(keepRecord: Bool, at date: Date = Date()) {
        let previous = completedPhase ?? phase
        concludeCurrentSession(keepRecord: keepRecord, at: date)
        completedPhase = nil
        pendingNextPhase = nil
        phaseDurationIsPlanOwned = false
        storedPhaseDurationSeconds = defaultDurationSeconds(for: phase)
        storedRemainingSeconds = storedPhaseDurationSeconds
        defaults.set(false, forKey: PomodoroStorageKeys.liveActivityVisible)

        PlanNotificationService.shared.cancelPomodoroCompletion()
        announce(.washedAway(previous))
        updateTicker()

        let state = PomodoroSharedState.current(at: date)
        Task { await PomodoroLiveActivityController.end(using: state) }
    }

    /// Direct phase selection is independent of the automatic Pomodoro cycle.
    /// This lets the user start focus twice in a row, take a break first, or
    /// move between short and long rest whenever they choose.
    func selectPhase(
        _ newPhase: PomodoroPhase,
        keepCurrentRecord: Bool = false,
        at date: Date = Date()
    ) {
        guard newPhase != phase || isAwaitingHandoff else { return }
        let previous = completedPhase ?? phase
        concludeCurrentSession(keepRecord: keepCurrentRecord, at: date)
        applyPhase(newPhase)
        defaults.set(false, forKey: PomodoroStorageKeys.liveActivityVisible)
        PlanNotificationService.shared.cancelPomodoroCompletion()
        announce(.washedAway(previous))
        updateTicker()

        let state = PomodoroSharedState.current(at: date)
        Task { await PomodoroLiveActivityController.end(using: state) }
    }

    /// Clears the current phase back to its full length. Focus time already
    /// spent is banked as a real record first — v1.15 silently discarded it.
    func reset(at date: Date = Date()) {
        if isAwaitingHandoff {
            // Nothing to clear on the finished screen — just move on, idle.
            continueToNextPhase()
            return
        }
        let finished = phase
        bankPartialFocusIfNeeded(at: date, releasingPlan: true)
        clearTimerState()
        // A linked plan may have temporarily owned this phase's duration.
        // Resetting releases that plan, so the idle timer must return to the
        // user's configured duration instead of keeping the orphaned override.
        phaseDurationIsPlanOwned = false
        storedPhaseDurationSeconds = defaultDurationSeconds(for: phase)
        storedRemainingSeconds = storedPhaseDurationSeconds
        defaults.set(false, forKey: PomodoroStorageKeys.liveActivityVisible)

        PlanNotificationService.shared.cancelPomodoroCompletion()
        announce(.washedAway(finished))
        updateTicker()

        let state = PomodoroSharedState.current(at: date)
        Task { await PomodoroLiveActivityController.end(using: state) }
    }

    /// Abandons the current phase and moves to the next one without counting it
    /// as a completed pomodoro. Any focus time already spent is still recorded.
    func skip(at date: Date = Date()) {
        if isAwaitingHandoff {
            continueToNextPhase()
            return
        }
        let previous = phase
        bankPartialFocusIfNeeded(at: date, releasingPlan: true)
        clearTimerState()
        defaults.set(false, forKey: PomodoroStorageKeys.liveActivityVisible)
        PlanNotificationService.shared.cancelPomodoroCompletion()

        let state = PomodoroSharedState.current(at: date)
        Task { await PomodoroLiveActivityController.end(using: state) }

        let next = nextPhase(after: previous)
        applyPhase(next)
        announce(.phaseFinished(from: previous, to: next))
        updateTicker()
    }

    // MARK: - Plan linking

    /// Brings a plan into the next focus. The plan is not marked 进行中 until
    /// the timer actually starts, so preparing and walking away changes nothing.
    func link(to item: CheckInItem) {
        guard canChangeLinkedPlan else { return }
        linkedPlanID = item.id
        title = item.title
        category = item.category
        defaults.set(item.title, forKey: PomodoroStorageKeys.title)
        defaults.set(item.category.rawValue, forKey: PomodoroStorageKeys.category)
        defaults.set(item.id.uuidString, forKey: PomodoroStorageKeys.linkedPlanID)

        applyPhase(.focus)
        if let plannedMinutes = item.plannedDurationMinutes {
            let bounded = min(
                PomodoroPhase.focus.durationRange.upperBound,
                max(PomodoroPhase.focus.durationRange.lowerBound, plannedMinutes)
            )
            storedPhaseDurationSeconds = bounded * 60
            storedRemainingSeconds = storedPhaseDurationSeconds
            phaseDurationIsPlanOwned = true
        }
        announce(.idle)
    }

    func unlink() {
        guard canChangeLinkedPlan else { return }
        releaseLinkedPlan()
        title = "番茄专注"
        category = .study
        defaults.set(title, forKey: PomodoroStorageKeys.title)
        defaults.set(category.rawValue, forKey: PomodoroStorageKeys.category)
        if phaseDurationIsPlanOwned {
            phaseDurationIsPlanOwned = false
            storedPhaseDurationSeconds = resolvedDurationSeconds(for: phase)
            storedRemainingSeconds = storedPhaseDurationSeconds
        }
    }

    /// Drops a link whose plan was deleted, completed or skipped elsewhere.
    /// A running focus keeps running — the user is still working, they just
    /// lose the plan attribution — so no real time is thrown away.
    private func reconcileLinkedPlan() {
        guard let planID = linkedPlanID else { return }
        let stillLinkable = store.checkInItems.contains {
            $0.id == planID
                && $0.kind == .planned
                && ($0.status == .planned || $0.status == .inProgress)
        }
        guard !stillLinkable else { return }
        linkedPlanID = nil
        defaults.set("", forKey: PomodoroStorageKeys.linkedPlanID)
        if !isRunning, phaseDurationIsPlanOwned {
            phaseDurationIsPlanOwned = false
            storedPhaseDurationSeconds = defaultDurationSeconds(for: phase)
            storedRemainingSeconds = storedPhaseDurationSeconds
        }
    }

    private func releaseLinkedPlan() {
        if let planID = linkedPlanID {
            store.releasePomodoroFocus(id: planID)
        }
        linkedPlanID = nil
        defaults.set("", forKey: PomodoroStorageKeys.linkedPlanID)
        resetFocusMetadata()
    }

    // MARK: - Durations

    func applyDuration(_ minutes: Int, to targetPhase: PomodoroPhase) {
        let bounded = min(
            targetPhase.durationRange.upperBound,
            max(targetPhase.durationRange.lowerBound, minutes)
        )
        defaults.set(bounded, forKey: targetPhase.settingsKey)
        switch targetPhase {
        case .focus: focusMinutes = bounded
        case .shortBreak: shortBreakMinutes = bounded
        case .longBreak: longBreakMinutes = bounded
        }

        guard targetPhase == phase, !isRunning else { return }
        // Editing the length mid-phase keeps whatever has already been spent,
        // so a paused 25 分钟 focus extended to 30 分钟 gains 5 minutes rather
        // than restarting.
        phaseDurationIsPlanOwned = false
        storedPhaseDurationSeconds = bounded * 60
        storedRemainingSeconds = max(1, storedPhaseDurationSeconds - accumulatedSeconds)

        if defaults.bool(forKey: PomodoroStorageKeys.liveActivityVisible) {
            let state = PomodoroSharedState.current()
            Task { await PomodoroLiveActivityController.startOrUpdate(using: state) }
        }
    }

    /// 长休息 is toggled over in 设置, which writes the shared key directly.
    /// If it is switched off while an idle long break is sitting on screen,
    /// that phase no longer exists in the cycle and has to give way — otherwise
    /// the page would show a 长休息 whose control has just been hidden.
    private func retireLongBreakIfDisabled() {
        guard !longBreakEnabled, phase == .longBreak, !isRunning, !isAwaitingHandoff else {
            return
        }
        applyPhase(.shortBreak)
    }

    // MARK: - Lifecycle

    func applicationDidBecomeActive(at date: Date = Date()) {
        syncFromStorage()
        repairStateIfNeeded()
        settleExpiredPhaseIfNeeded(at: date)
        retireLongBreakIfDisabled()
        adoptPreferenceDurationIfIdle()
        reconcileLinkedPlan()
        updateTicker()
    }

    /// Called when the 番茄钟 screen appears so a duration edited over in 设置
    /// is reflected immediately instead of waiting for the next phase change.
    func refreshFromPreferences() {
        syncFromStorage()
        retireLongBreakIfDisabled()
        adoptPreferenceDurationIfIdle()
        reconcileLinkedPlan()
        // Storage may have been changed out from under us, so the ticker has to
        // be re-evaluated against the state we just read.
        updateTicker()
    }

    private func updateTicker() {
        guard isRunning else {
            ticker = nil
            updateIdleTimer()
            return
        }
        guard ticker == nil else {
            updateIdleTimer()
            return
        }
        ticker = Timer.publish(every: 0.5, on: .main, in: .common)
            .autoconnect()
            .sink { [weak self] date in
                guard let self else { return }
                // The lock screen and Dynamic Island write straight to the
                // shared defaults. Notice when they have paused us so the
                // in-app controls do not keep claiming to be running.
                if self.defaults.bool(forKey: PomodoroStorageKeys.running) != self.isRunning {
                    self.syncFromStorage()
                    self.updateTicker()
                    return
                }
                self.settleExpiredPhaseIfNeeded(at: date)
                self.reconcileLinkedPlan()
            }
        updateIdleTimer()
    }

    private func updateIdleTimer() {
        let keepAwake = PlanSettingsKeys.bool(
            PlanSettingsKeys.keepScreenAwake,
            fallback: false,
            defaults: defaults
        )
        UIApplication.shared.isIdleTimerDisabled = keepAwake && isRunning
    }

    func releaseIdleTimer() {
        UIApplication.shared.isIdleTimerDisabled = false
    }

    // MARK: - Completion

    private func settleExpiredPhaseIfNeeded(at date: Date) {
        guard
            isRunning,
            targetTimestamp > 0,
            date.timeIntervalSince1970 >= targetTimestamp
        else { return }

        // Bank the phase at its scheduled end, not at the moment the app
        // happened to notice, so backgrounded time stays truthful.
        let endDate = Date(timeIntervalSince1970: targetTimestamp)
        let previous = phase
        // Read the elapsed time out before clearing — `clearTimerState()` zeroes
        // the accumulator, so reading it afterwards always saw 0 and the focus
        // record was silently never written.
        let focusSeconds = elapsedFocusSeconds(at: endDate)
        clearTimerState()
        // The phase is over, so the ring must read as a closed circle while the
        // finished state is held on screen.
        storedRemainingSeconds = 0
        defaults.set(false, forKey: PomodoroStorageKeys.liveActivityVisible)
        PlanNotificationService.shared.cancelPomodoroCompletion()

        let finishedState = PomodoroSharedState.current(at: endDate)
        Task { await PomodoroLiveActivityController.end(using: finishedState) }

        var didCompleteFocus = false
        if previous == .focus, focusSeconds > 0 {
            store.recordFocusSession(
                title: resolvedTitle,
                category: category,
                startDate: endDate.addingTimeInterval(TimeInterval(-focusSeconds)),
                endDate: endDate,
                linkedCheckInID: linkedPlanID,
                completesPlan: true
            )
            completedFocusCount += 1
            defaults.set(completedFocusCount, forKey: PomodoroStorageKeys.completed)
            linkedPlanID = nil
            defaults.set("", forKey: PomodoroStorageKeys.linkedPlanID)
            didCompleteFocus = true
        }

        let next = nextPhase(after: previous)
        // Hold here rather than switching phases. `storedRemainingSeconds` is
        // now 0 against an unchanged duration, so the ring reads as a complete
        // circle for as long as the finished screen is up.
        completedPhase = previous
        pendingNextPhase = next
        announce(.phaseFinished(from: previous, to: next))
        updateTicker()

        // A focus that ended without banking any time was never really running,
        // so it must not chain into an automatic break.
        let reachedNaturalEnd = didCompleteFocus || previous.isBreak
        guard reachedNaturalEnd, autoStartSetting(for: next) else { return }
        // Even when chaining automatically, let the finished stroke land first.
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.6) { [weak self] in
            guard let self, self.completedPhase == previous, !self.isRunning else { return }
            self.continueToNextPhase(startImmediately: true)
        }
    }

    /// Writes the focus seconds spent so far as a real record without marking
    /// the plan complete, then hands the plan back to the checklist.
    private func bankPartialFocusIfNeeded(at date: Date, releasingPlan: Bool) {
        let elapsed = elapsedFocusSeconds(at: date)
        // Under a minute is a mis-tap, not a session worth keeping.
        if phase == .focus, elapsed >= 60 {
            store.recordFocusSession(
                title: resolvedTitle,
                category: category,
                startDate: date.addingTimeInterval(TimeInterval(-elapsed)),
                endDate: date,
                linkedCheckInID: linkedPlanID,
                completesPlan: false
            )
        }
        if releasingPlan {
            releaseLinkedPlan()
        }
    }

    /// Finalizes a user-driven stop or phase change. This deliberately differs
    /// from natural completion: it never increments the completed-Pomodoro
    /// count and never marks an attached plan complete.
    private func concludeCurrentSession(keepRecord: Bool, at date: Date) {
        let elapsed = min(phaseDurationSeconds, elapsedFocusSeconds(at: date))
        if keepRecord, phase == .focus, !isAwaitingHandoff, elapsed > 0 {
            store.recordFocusSession(
                title: resolvedTitle,
                category: category,
                startDate: date.addingTimeInterval(TimeInterval(-elapsed)),
                endDate: date,
                linkedCheckInID: linkedPlanID,
                completesPlan: false
            )
            linkedPlanID = nil
            defaults.set("", forKey: PomodoroStorageKeys.linkedPlanID)
            resetFocusMetadata()
        } else {
            releaseLinkedPlan()
        }
        clearTimerState()
    }

    private func nextPhase(after previous: PomodoroPhase) -> PomodoroPhase {
        guard previous == .focus else { return .focus }
        let raw = PomodoroCyclePolicy.nextBreakPhaseRaw(
            completedFocusCount: completedFocusCount,
            longBreakInterval: PlanSettingsKeys.integer(
                PomodoroStorageKeys.longBreakInterval,
                fallback: 4,
                defaults: defaults
            ),
            longBreakEnabled: longBreakEnabled
        )
        return PomodoroPhase(rawValue: raw) ?? .shortBreak
    }

    private func autoStartSetting(for next: PomodoroPhase) -> Bool {
        PlanSettingsKeys.bool(
            next.isBreak ? PlanSettingsKeys.autoStartBreaks : PlanSettingsKeys.autoStartFocus,
            fallback: false,
            defaults: defaults
        )
    }

    // MARK: - State plumbing

    private func applyPhase(_ newPhase: PomodoroPhase) {
        completedPhase = nil
        pendingNextPhase = nil
        phase = newPhase
        defaults.set(newPhase.rawValue, forKey: PomodoroStorageKeys.phase)
        clearTimerState()
        phaseDurationIsPlanOwned = false
        storedPhaseDurationSeconds = defaultDurationSeconds(for: newPhase)
        storedRemainingSeconds = storedPhaseDurationSeconds
        defaults.set(false, forKey: PomodoroStorageKeys.liveActivityVisible)
        if newPhase == .focus, linkedPlanID == nil {
            resetFocusMetadata()
        }
    }

    private func clearTimerState() {
        isRunning = false
        defaults.set(false, forKey: PomodoroStorageKeys.running)
        targetTimestamp = 0
        segmentStartTimestamp = 0
        accumulatedSeconds = 0
    }

    private func syncFromStorage() {
        focusMinutes = storedMinutes(for: .focus)
        shortBreakMinutes = storedMinutes(for: .shortBreak)
        longBreakMinutes = storedMinutes(for: .longBreak)
        longBreakEnabled = PlanSettingsKeys.bool(
            PomodoroStorageKeys.longBreakEnabled,
            fallback: true,
            defaults: defaults
        )
        phase = PomodoroPhase(
            rawValue: defaults.string(forKey: PomodoroStorageKeys.phase) ?? ""
        ) ?? .focus
        isRunning = defaults.bool(forKey: PomodoroStorageKeys.running)
        title = defaults.string(forKey: PomodoroStorageKeys.title) ?? "番茄专注"
        category = RecordCategory(
            rawValue: defaults.string(forKey: PomodoroStorageKeys.category) ?? ""
        ) ?? .study
        linkedPlanID = UUID(
            uuidString: defaults.string(forKey: PomodoroStorageKeys.linkedPlanID) ?? ""
        )
        completedFocusCount = defaults.integer(forKey: PomodoroStorageKeys.completed)
    }

    private func repairStateIfNeeded() {
        if storedPhaseDurationSeconds <= 0 {
            storedPhaseDurationSeconds = defaultDurationSeconds(for: phase)
        }
        if storedRemainingSeconds <= 0, !isRunning {
            storedRemainingSeconds = phaseDurationSeconds
        }
        if isRunning, targetTimestamp <= 0 || segmentStartTimestamp <= 0 {
            // A half-written running state can never be resumed truthfully.
            clearTimerState()
            storedRemainingSeconds = phaseDurationSeconds
            defaults.set(false, forKey: PomodoroStorageKeys.liveActivityVisible)
        }
    }

    /// Picks up a duration the user changed in 设置 while this phase sat idle.
    private func adoptPreferenceDurationIfIdle() {
        guard !isRunning, accumulatedSeconds == 0, !phaseDurationIsPlanOwned else { return }
        let preferred = defaultDurationSeconds(for: phase)
        guard storedPhaseDurationSeconds != preferred else { return }
        storedPhaseDurationSeconds = preferred
        storedRemainingSeconds = preferred
    }

    private func defaultDurationSeconds(for phase: PomodoroPhase) -> Int {
        minutes(for: phase) * 60
    }

    private func resolvedDurationSeconds(for phase: PomodoroPhase) -> Int {
        phaseDurationIsPlanOwned && storedPhaseDurationSeconds > 0
            ? storedPhaseDurationSeconds
            : defaultDurationSeconds(for: phase)
    }

    private var resolvedTitle: String {
        let clean = title.trimmingCharacters(in: .whitespacesAndNewlines)
        return clean.isEmpty ? "番茄专注" : clean
    }

    private func resetFocusMetadata() {
        title = "番茄专注"
        category = .study
        defaults.set(title, forKey: PomodoroStorageKeys.title)
        defaults.set(category.rawValue, forKey: PomodoroStorageKeys.category)
    }

    private var liveActivitiesEnabled: Bool {
        PlanSettingsKeys.bool(
            PlanSettingsKeys.liveActivitiesEnabled,
            fallback: true,
            defaults: defaults
        )
    }

    private func announce(_ event: PomodoroMotionEvent) {
        motionEvent = event
        motionToken += 1
    }

    private func publishRunningState() {
        let state = PomodoroSharedState.current()
        Task {
            await PomodoroLiveActivityController.startOrUpdate(using: state)
            if let targetDate = state.targetDate {
                await PlanNotificationService.shared.schedulePomodoroCompletion(
                    title: state.title,
                    phaseName: state.phaseName,
                    targetDate: targetDate
                )
            }
        }
    }
}
