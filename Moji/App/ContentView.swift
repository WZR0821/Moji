import SwiftUI

extension Notification.Name {
    static let mojiOpenChecklist = Notification.Name("moji.openChecklist")
    static let mojiOpenPomodoro = Notification.Name("moji.openPomodoro")
    static let mojiConfirmPomodoroTermination = Notification.Name(
        "moji.confirmPomodoroTermination"
    )
    /// Carries the plan's `UUID` as the notification object.
    static let mojiOpenPlanEditor = Notification.Name("moji.openPlanEditor")
    /// 说一句添加: the home page focuses its field and turns the microphone on.
    static let mojiStartSaying = Notification.Name("moji.startSaying")
}

enum RootTab: Hashable {
    case checklist
    case memos
    case countdowns
    case profile

    static var initialTab: RootTab {
#if DEBUG
        switch ProcessInfo.processInfo.environment["MOJI_QA_TAB"] {
        case "memos": return .memos
        case "countdowns": return .countdowns
        case "profile": return .profile
        default: break
        }
#endif
        return .checklist
    }
}

struct ContentView: View {
    @ObservedObject var store: PlanStore
    @ObservedObject var pomodoro: PomodoroEngine
    @State private var selectedTab: RootTab = .initialTab
    @State private var didRunDebugScenario = false
    @Environment(\.scenePhase) private var scenePhase
#if DEBUG
    @State private var showsQAShareSheet = false
#endif
    @AppStorage(PlanSettingsKeys.hapticsEnabled, store: SharedPersistence.sharedDefaults)
    private var hapticsEnabled = true

    var body: some View {
        TabView(selection: $selectedTab) {
            DashboardView(store: store, pomodoro: pomodoro)
                .tabItem { Label("计划", systemImage: "checklist") }
                .tag(RootTab.checklist)

            MemosView(store: store)
                .tabItem { Label("备忘", systemImage: "note.text") }
                .tag(RootTab.memos)

            CountdownsView(store: store, pomodoro: pomodoro)
                .tabItem { Label("时刻", systemImage: "timer") }
                .tag(RootTab.countdowns)

            PersonalView(store: store)
                .tabItem { Label("回顾", systemImage: "chart.bar.xaxis") }
                .tag(RootTab.profile)
        }
        .onOpenURL { url in
            if url.host == "say" {
                startSaying()
                return
            }
            switch url.host {
            case "memos", "notes": selectedTab = .memos
            case "countdowns": selectedTab = .countdowns
            case "profile", "records": selectedTab = .profile
            default: selectedTab = .checklist
            }
            store.reload()

            // moji://plan?id=<uuid> — the 计划 widget's edit shortcut. The tab
            // has to settle before the editor sheet is asked for, otherwise the
            // sheet is presented by a view that is on its way off screen.
            if let id = MojiDeepLink.planID(from: url) {
                DispatchQueue.main.async {
                    NotificationCenter.default.post(
                        name: .mojiOpenPlanEditor,
                        object: id
                    )
                }
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .mojiOpenPomodoro)) { _ in
            selectedTab = .countdowns
            store.reload()
        }
        .onReceive(NotificationCenter.default.publisher(for: .mojiOpenChecklist)) { _ in
            selectedTab = .checklist
            store.reload()
        }
        .onReceive(NotificationCenter.default.publisher(for: .mojiSayRequested)) { _ in
            if MojiSayRequest.take() { startSaying() }
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active:
                if MojiSayRequest.take() { startSaying() }
                AIWeeklyLetterState.shared.prepareIfNeeded(snapshot: store.snapshot, now: AIClock.now(), inBackground: false)
                AIWeeklyLetterNotifier.reschedule()
            case .background:
                AIWeeklyLetterState.shared.prepareIfNeeded(snapshot: store.snapshot, now: AIClock.now(), inBackground: true)
                AIWeeklyLetterNotifier.reschedule()
            default:
                break
            }
        }
        .overlay(alignment: .bottom) {
            DraftUndoToast(store: store)
        }
        .task {
            // A cold launch from the 说一句 widget or Shortcut arrives here first.
            if MojiSayRequest.take() { startSaying() }
            AIWeeklyLetterNotifier.reschedule()
#if DEBUG
            runCompletionRevertScenarioIfNeeded()
            if AIQAFixture.scenario == "ai-share-sheet" {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { showsQAShareSheet = true }
            }
#endif
        }
#if DEBUG
        .sheet(isPresented: $showsQAShareSheet) {
            ShareCaptureView(
                model: ShareCaptureModel(text: "周五前交季度报告，周三下午和小王对一下数据，明天上午订会议室", isLoading: false),
                keep: { true },
                cancel: { showsQAShareSheet = false },
                done: { showsQAShareSheet = false }
            )
        }
#endif
        .sensoryFeedback(.selection, trigger: selectedTab) { oldValue, newValue in
            hapticsEnabled && oldValue != newValue
        }
    }

    /// 说一句添加: today's list with the field focused and the microphone on.
    private func startSaying() {
        let wasOnChecklist = selectedTab == .checklist
        selectedTab = .checklist
        store.reload()
        DispatchQueue.main.asyncAfter(deadline: .now() + (wasOnChecklist ? 0.05 : 0.35)) {
            NotificationCenter.default.post(name: .mojiStartSaying, object: nil)
        }
    }

#if DEBUG
    private func runCompletionRevertScenarioIfNeeded() {
        guard
            !didRunDebugScenario,
            ProcessInfo.processInfo.environment["MOJI_QA_SCENARIO"] == "completion-revert",
            let fixtureID = UUID(uuidString: "11111111-1111-1111-1111-111111111111")
        else { return }

        didRunDebugScenario = true
        let start = Calendar.current.date(
            bySettingHour: 9,
            minute: 0,
            second: 0,
            of: Date()
        ) ?? Date()
        let fixture = CheckInItem(
            id: fixtureID,
            title: "误触完成后已恢复",
            category: .study,
            scheduledStart: start,
            plannedMinutes: 25,
            repeatRule: .daily,
            seriesID: fixtureID
        )
        store.saveCheckIn(fixture)
        store.toggleCheckIn(id: fixtureID)
        store.toggleCheckIn(id: fixtureID)

        // A yearly birthday whose origin is years back: the card should count
        // down to the next one *and* say how long it has been.
        if
            let birthdayID = UUID(uuidString: "55555555-5555-5555-5555-555555555555"),
            let birthday = Calendar.current.date(byAdding: .day, value: -2_240, to: start)
        {
            store.saveCountdown(
                CountdownEvent(
                    id: birthdayID,
                    title: "母亲生日",
                    targetDate: birthday,
                    symbolName: "gift.fill",
                    repeatRule: .yearly
                )
            )
        }

        let countdownFixtures: [(String, String, Int, Bool)] = [
            ("项目交付", "22222222-2222-2222-2222-222222222222", 2, true),
            ("远行", "33333333-3333-3333-3333-333333333333", 30, false),
            ("初次相遇", "44444444-4444-4444-4444-444444444444", -365, false)
        ]
        for (title, rawID, dayOffset, isPinned) in countdownFixtures {
            guard
                let id = UUID(uuidString: rawID),
                let targetDate = Calendar.current.date(
                    byAdding: .day,
                    value: dayOffset,
                    to: start
                )
            else { continue }
            store.saveCountdown(
                CountdownEvent(
                    id: id,
                    title: title,
                    targetDate: targetDate,
                    symbolName: isPinned ? "flag.fill" : "bookmark.fill",
                    isPinned: isPinned
                )
            )
        }
    }
#endif
}
