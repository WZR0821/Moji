import Combine
import SwiftUI
import UIKit

/// 倒数日 (dates still ahead) and 纪念日 (dates already passed) now each own a
/// page. They used to share one list split into two sections, which made
/// ordering ambiguous — a drag or a sort had to mean something different in
/// each half — and pushed the anniversaries below a long countdown list.
private enum CountdownMode: String, CaseIterable, Identifiable {
    case countdown
    case anniversary
    case pomodoro

    static var initialMode: CountdownMode {
#if DEBUG
        switch ProcessInfo.processInfo.environment["MOJI_QA_COUNTDOWN_MODE"] {
        case "pomodoro": return .pomodoro
        case "anniversary": return .anniversary
        case "countdown", "moments": return .countdown
        default: break
        }
#endif
        return .countdown
    }

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .countdown: return "倒数日"
        case .anniversary: return "纪念日"
        case .pomodoro: return "专注"
        }
    }

    var scope: CountdownScope? {
        switch self {
        case .countdown: return .upcoming
        case .anniversary: return .past
        case .pomodoro: return nil
        }
    }

    var editorContext: CountdownEditorContext? {
        switch self {
        case .countdown: return .countdown
        case .anniversary: return .anniversary
        case .pomodoro: return nil
        }
    }
}

/// Which half of the stored events a page shows, plus everything that differs
/// between the two lists.
enum CountdownScope {
    case upcoming
    case past

    var sortModeKey: String {
        switch self {
        case .upcoming: return PlanSettingsKeys.countdownSortMode
        case .past: return PlanSettingsKeys.anniversarySortMode
        }
    }

    var emptyTitle: String {
        switch self {
        case .upcoming: return "还没有要倒数的日期"
        case .past: return "还没有纪念的日期"
        }
    }

    var emptyMessage: String {
        switch self {
        case .upcoming: return "未来的日期会出现在这里，不足三天时以朱红标记。"
        case .past: return "日期过去之后会自动移到这里，改为正数计日。"
        }
    }

    var emptySymbol: String {
        switch self {
        case .upcoming: return "hourglass"
        case .past: return "seal"
        }
    }
}

struct CountdownsView: View {
    @ObservedObject var store: PlanStore
    @ObservedObject var pomodoro: PomodoroEngine

    @State private var mode: CountdownMode = .initialMode
    @State private var isPresentingEditor = false
    @State private var editingEvent: CountdownEvent?
    @State private var editorContext: CountdownEditorContext = .countdown
    @State private var isReordering = false
    @Namespace private var modeIndicator

    @AppStorage(PlanSettingsKeys.countdownSortMode, store: SharedPersistence.sharedDefaults)
    private var countdownSortModeRaw = CountdownSortMode.manual.rawValue
    @AppStorage(PlanSettingsKeys.anniversarySortMode, store: SharedPersistence.sharedDefaults)
    private var anniversarySortModeRaw = CountdownSortMode.manual.rawValue

    var body: some View {
        NavigationStack {
            ZStack {
                InkWashBackground()

                VStack(spacing: 0) {
                    modeCards

                    Group {
                        if let scope = mode.scope {
                            CountdownEventListView(
                                store: store,
                                scope: scope,
                                sortMode: sortMode(for: scope),
                                editingEvent: $editingEvent,
                                isReordering: $isReordering
                            )
                        } else {
                            PomodoroView(store: store, engine: pomodoro)
                        }
                    }
                    .id(mode)
                    .transition(.opacity)
                }
            }
            .navigationTitle("时刻")
            .toolbar {
                if let scope = mode.scope {
                    ToolbarItem(placement: .topBarTrailing) {
                        if isReordering {
                            Button("完成") {
                                withAnimation(.easeInOut(duration: 0.18)) {
                                    isReordering = false
                                }
                            }
                        } else {
                            listMenu(for: scope)
                        }
                    }

                    ToolbarItem(placement: .topBarTrailing) {
                        Button {
                            guard let context = mode.editorContext else { return }
                            editorContext = context
                            isPresentingEditor = true
                        } label: {
                            Image(systemName: "plus")
                        }
                        .disabled(isReordering)
                        .accessibilityLabel(mode.editorContext?.addTitle ?? "添加")
                    }
                }
            }
            .sheet(isPresented: $isPresentingEditor) {
                CountdownEditorView(store: store, context: editorContext)
            }
            .sheet(item: $editingEvent) { event in
                CountdownEditorView(store: store, event: event)
            }
            .onChange(of: mode) { _, _ in
                isReordering = false
            }
            .onReceive(
                NotificationCenter.default.publisher(for: .mojiOpenPomodoro)
            ) { _ in
                // Bringing a plan into focus has to land on the 番茄钟 segment.
                // Switching only the tab used to leave the user staring at the
                // 倒数日 list wondering where their timer went.
                guard mode != .pomodoro else { return }
                withAnimation(.easeInOut(duration: 0.24)) {
                    mode = .pomodoro
                }
            }
        }
    }

    private func sortMode(for scope: CountdownScope) -> CountdownSortMode {
        let raw = scope == .upcoming ? countdownSortModeRaw : anniversarySortModeRaw
        return CountdownSortMode(rawValue: raw) ?? .manual
    }

    private func applySortMode(_ newMode: CountdownSortMode, to scope: CountdownScope) {
        switch scope {
        case .upcoming: countdownSortModeRaw = newMode.rawValue
        case .past: anniversarySortModeRaw = newMode.rawValue
        }
        // Dragging rows while the list orders itself by date would write an
        // order nothing ever reads, so reordering closes with the switch.
        if newMode == .date {
            isReordering = false
        }
    }

    private func listMenu(for scope: CountdownScope) -> some View {
        let current = sortMode(for: scope)
        return Menu {
            Picker(
                selection: Binding(
                    get: { current },
                    set: { newMode in
                        withAnimation(.easeInOut(duration: 0.22)) {
                            applySortMode(newMode, to: scope)
                        }
                    }
                )
            ) {
                Label(CountdownSortMode.date.displayName, systemImage: CountdownSortMode.date.symbolName)
                    .tag(CountdownSortMode.date)
                Label(CountdownSortMode.manual.displayName, systemImage: CountdownSortMode.manual.symbolName)
                    .tag(CountdownSortMode.manual)
            } label: {
                Text("排序方式")
            }
            .pickerStyle(.inline)

            Button {
                withAnimation(.easeInOut(duration: 0.18)) {
                    isReordering = true
                }
            } label: {
                Label("调整顺序", systemImage: "arrow.up.arrow.down")
            }
            .disabled(current == .date)
        } label: {
            Image(systemName: "ellipsis")
        }
        .accessibilityLabel("排序与调整顺序")
    }

    private var modeCards: some View {
        HStack(spacing: 0) {
            ForEach(CountdownMode.allCases) { option in
                let isSelected = mode == option
                Button {
                    withAnimation(.easeInOut(duration: 0.24)) {
                        mode = option
                    }
                } label: {
                    VStack(spacing: 3) {
                        Text(option.displayName)
                            .font(.subheadline.weight(isSelected ? .semibold : .regular))
                        ZStack {
                            Color.clear.frame(height: 5)
                            if isSelected {
                                InkBrushDivider(
                                    tint: .planPrimary,
                                    animated: false
                                )
                                .matchedGeometryEffect(
                                    id: "countdown-mode-ink",
                                    in: modeIndicator
                                )
                            }
                        }
                        .frame(width: 42)
                    }
                    .foregroundStyle(
                        isSelected ? Color.planPrimary : Color.secondary
                    )
                    .frame(maxWidth: .infinity)
                    .frame(height: 38)
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(isSelected ? .isSelected : [])
            }
        }
        .padding(.top, 5)
        .padding(.horizontal, 22)
    }
}

private struct PomodoroView: View {
    @ObservedObject var store: PlanStore
    @ObservedObject var engine: PomodoroEngine
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @AppStorage(PlanSettingsKeys.inkMotionLevel, store: SharedPersistence.sharedDefaults)
    private var inkMotionLevelRaw = InkMotionLevel.full.rawValue
    @AppStorage(PlanSettingsKeys.hapticsEnabled, store: SharedPersistence.sharedDefaults)
    private var hapticsEnabled = true

    @State private var customDurationPhase: PomodoroPhase?
    @State private var customDurationMinutes = 25
    @State private var showsStopConfirmation = false
    @State private var pendingPhaseSwitch: PomodoroPhase?
    /// Drives the one-shot ink wash that replaces the old instant phase swap.
    @State private var washProgress = 0.0
    /// Ink settling into the paper as the stroke closes its circle.
    @State private var landingBloom = 0.0
    @State private var showsCompletionSeal = false
#if DEBUG
    @State private var didConfigureQATimer = false
#endif

    private var inkMotionLevel: InkMotionLevel {
        InkMotionLevel(rawValue: inkMotionLevelRaw) ?? .full
    }

    private var motionIsEnabled: Bool {
        !reduceMotion && inkMotionLevel != .off
    }

    private var inkAnimationInterval: TimeInterval {
        if reduceMotion { return 1.0 }
        switch inkMotionLevel {
        case .full: return 1.0 / 30.0
        case .reduced: return 1.0 / 15.0
        case .off: return 1.0
        }
    }

    private var availablePlans: [CheckInItem] {
        store.checkInItems
            .filter {
                // Always offer overdue plans here, whatever the checklist's
                // carry-over setting says: picking one to focus on is exactly
                // how an old plan gets finished.
                $0.kind == .planned
                    && $0.status == .planned
                    && $0.isVisibleInChecklist(carriesOverUnfinished: true)
            }
            .sorted { $0.scheduledStart < $1.scheduledStart }
    }

    var body: some View {
        GeometryReader { proxy in
            ScrollView {
                VStack(spacing: 20) {
                    Spacer(minLength: 20)
                    timerRing
                    phaseSelector
                    Group {
                        if engine.isAwaitingHandoff {
                            handoffHint
                        } else if engine.isRunning {
                            Text(runningStatusText)
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        } else {
                            durationQuickSettings
                        }
                    }
                    .frame(height: 60)
                    controls
                    Spacer(minLength: 30)
                }
                .frame(maxWidth: .infinity, minHeight: proxy.size.height)
            }
            .scrollIndicators(.hidden)
            .scrollBounceBehavior(.basedOnSize)
        }
        .padding(.horizontal, 30)
        .onAppear {
#if DEBUG
            configureQATimerIfNeeded()
#endif
            engine.refreshFromPreferences()
        }
        .onDisappear {
            engine.releaseIdleTimer()
        }
        .onChange(of: engine.motionToken) { _, _ in
            respondToMotionEvent()
        }
        .onReceive(
            NotificationCenter.default.publisher(for: .mojiConfirmPomodoroTermination)
        ) { _ in
            guard engine.hasActiveSession else { return }
            showsStopConfirmation = true
        }
        .sensoryFeedback(.success, trigger: engine.completedFocusCount) { _, _ in
            hapticsEnabled
        }
        .sheet(item: $customDurationPhase) { timerPhase in
            PomodoroDurationEditor(
                timerPhase: timerPhase,
                initialMinutes: customDurationMinutes
            ) { minutes in
                engine.applyDuration(minutes, to: timerPhase)
            }
            .presentationDetents([.medium])
            .presentationDragIndicator(.visible)
        }
        .confirmationDialog(
            "结束当前\(engine.phase.displayName)？",
            isPresented: $showsStopConfirmation,
            titleVisibility: .visible
        ) {
            if engine.canKeepCurrentRecord() {
                Button("结束并保留记录") {
                    engine.terminate(keepRecord: true)
                }
                Button("结束且不保留", role: .destructive) {
                    engine.terminate(keepRecord: false)
                }
            } else {
                Button("结束本轮", role: .destructive) {
                    engine.terminate(keepRecord: false)
                }
            }
            Button("取消", role: .cancel) {}
        } message: {
            if engine.canKeepCurrentRecord() {
                Text("你可以把已经专注的时间写入记录，也可以直接丢弃。关联计划不会被标记为完成。")
            } else {
                Text("结束后会停止计时并清除锁屏与灵动岛显示。")
            }
        }
        .confirmationDialog(
            phaseSwitchTitle,
            isPresented: phaseSwitchConfirmationBinding,
            titleVisibility: .visible
        ) {
            if let target = pendingPhaseSwitch {
                if engine.canKeepCurrentRecord() {
                    Button("保留本轮记录并切换") {
                        engine.selectPhase(target, keepCurrentRecord: true)
                        pendingPhaseSwitch = nil
                    }
                    Button("不保留记录并切换", role: .destructive) {
                        engine.selectPhase(target, keepCurrentRecord: false)
                        pendingPhaseSwitch = nil
                    }
                } else {
                    Button("结束当前阶段并切换", role: .destructive) {
                        engine.selectPhase(target, keepCurrentRecord: false)
                        pendingPhaseSwitch = nil
                    }
                }
            }
            Button("取消", role: .cancel) {
                pendingPhaseSwitch = nil
            }
        } message: {
            Text("切换后新阶段会从完整时长开始，不再受固定番茄循环限制。")
        }
    }

    /// Plays the ink response for whatever the engine just did. The engine
    /// stays unaware of animation; it only reports the milestone.
    private func respondToMotionEvent() {
        guard motionIsEnabled else {
            washProgress = 0
            landingBloom = 0
            // The seal is a state marker, not decoration, so it still shows.
            showsCompletionSeal = engine.completedPhase == .focus
            return
        }

        switch engine.motionEvent {
        case let .phaseFinished(from, _):
            // The finished stroke bleeds outward and lifts off the paper
            // before the next phase lands, instead of snapping to empty.
            // The stroke has just closed its circle. Let the ink settle into
            // the paper and stamp it, rather than wiping it away unseen.
            landingBloom = 0.001
            withAnimation(.easeOut(duration: 0.85)) {
                landingBloom = 1
            }
            if from == .focus {
                withAnimation(.spring(response: 0.46, dampingFraction: 0.64).delay(0.22)) {
                    showsCompletionSeal = true
                }
            }
        case .washedAway:
            showsCompletionSeal = false
            landingBloom = 0
            washProgress = 0.001
            withAnimation(.easeOut(duration: 0.55)) {
                washProgress = 1
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.57) {
                washProgress = 0
            }
        case .started, .paused, .idle:
            break
        }
    }

    private var timerRing: some View {
        ZStack {
            TimelineView(
                .animation(
                    minimumInterval: inkAnimationInterval,
                    paused: !engine.isRunning
                )
            ) { context in
                ZStack {
                    // Ink soaking outward as the circle closes.
                    if landingBloom > 0 {
                        InkFocusBloomView(opacity: 0.13 * (1 - landingBloom * 0.55))
                            .frame(width: timerRingSize - 24, height: timerRingSize - 24)
                            .scaleEffect(0.72 + landingBloom * 0.34)
                            .blur(radius: 2 + landingBloom * 5)
                            .allowsHitTesting(false)
                    }

                    InkBrushRing(
                        progress: engine.progress(at: context.date),
                        accent: .planPrimary,
                        lineWidth: 10,
                        displaysRemaining: false,
                        showsTrack: true,
                        trackOpacity: engine.hasActiveSession ? 0.13 : 0.08,
                        showsBrushTip: engine.isRunning && motionIsEnabled
                    )

                    VStack(spacing: 10) {
                        Text(centerCaption)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .contentTransition(.opacity)
                        Text(
                            engine.isAwaitingHandoff
                                ? "00:00"
                                : engine.clockText(at: context.date)
                        )
                        .font(.system(size: 50, weight: .light, design: .rounded))
                        .monospacedDigit()
                        focusPlanMenu
                    }
                }
            }

            if washProgress > 0 {
                InkBrushRing(
                    progress: 1,
                    accent: .planPrimary,
                    lineWidth: 10,
                    showsTrack: false,
                    showsBrushTip: false
                )
                .scaleEffect(1 + washProgress * 0.09)
                .opacity((1 - washProgress) * 0.5)
                .blur(radius: washProgress * 8)
                .allowsHitTesting(false)
            }

            if showsCompletionSeal {
                InkSealMark(
                    character: "专",
                    size: 32,
                    style: .tall,
                    seed: engine.completedFocusCount
                )
                    .offset(x: 84, y: 84)
                    .transition(.scale.combined(with: .opacity))
                    .allowsHitTesting(false)
            }
        }
        .frame(width: timerRingSize, height: timerRingSize)
        .animation(.easeInOut(duration: 0.30), value: engine.phase)
    }

    private var timerRingSize: CGFloat {
        // Keep the composition stable when a session starts. Changing the
        // ring's frame at the same moment as the timer state makes the first
        // tick feel like a zoom instead of a state change.
        252
    }

    private var centerCaption: String {
        if let completed = engine.completedPhase {
            return "\(completed.displayName)完成"
        }
        return engine.phase.displayName
    }

    private var runningStatusText: String {
        guard let targetDate = engine.targetDate else {
            return "进行中 · 结束后可调整时长"
        }
        return "进行中 · 预计 \(targetDate.formatted(date: .omitted, time: .shortened)) 结束"
    }

    private var controls: some View {
        HStack(spacing: 24) {
            Button {
                showsStopConfirmation = true
            } label: {
                VStack(spacing: 4) {
                    InkBrushMedallion(
                        symbol: "stop.fill",
                        tint: .planVermilion,
                        size: 44,
                        seed: 2
                    )
                    Text("结束")
                        .font(.caption2)
                }
                .frame(minWidth: 64, minHeight: 64)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(!engine.hasActiveSession)
            .opacity(engine.hasActiveSession ? 1 : 0.34)
            .accessibilityLabel("结束本轮")

            Button {
                engine.isAwaitingHandoff
                    ? engine.continueToNextPhase(startImmediately: true)
                    : engine.toggle()
            } label: {
                VStack(spacing: 4) {
                    InkBrushMedallion(
                        symbol: engine.isRunning ? "pause.fill" : "play.fill",
                        tint: .planPrimary,
                        size: 56,
                        seed: 0
                    )
                    Text(nextActionLabel)
                        .font(.caption2)
                        .lineLimit(1)
                }
                .frame(minWidth: 88, minHeight: 76)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(nextActionLabel)
        }
    }

    private var phaseSelector: some View {
        HStack(spacing: 8) {
            ForEach(selectablePhases) { timerPhase in
                let isSelected = engine.phase == timerPhase && !engine.isAwaitingHandoff
                Button {
                    requestPhaseSelection(timerPhase)
                } label: {
                    HStack(spacing: 5) {
                        Image(systemName: timerPhase.symbolName)
                            .font(.caption.weight(.semibold))
                        Text(timerPhase.displayName)
                            .font(.caption.weight(isSelected ? .semibold : .regular))
                    }
                    .foregroundStyle(isSelected ? Color.planBackground : Color.planPrimary)
                    .frame(maxWidth: .infinity)
                    .frame(height: 44)
                    .background {
                        Capsule(style: .continuous)
                            .fill(isSelected ? Color.planPrimary : Color.planPrimary.opacity(0.055))
                    }
                    .overlay {
                        Capsule(style: .continuous)
                            .stroke(Color.planPrimary.opacity(isSelected ? 0 : 0.14), lineWidth: 0.8)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(isSelected ? .isSelected : [])
            }
        }
        .animation(.easeInOut(duration: 0.18), value: engine.phase)
    }

    private var selectablePhases: [PomodoroPhase] {
        engine.longBreakEnabled ? PomodoroPhase.allCases : [.focus, .shortBreak]
    }

    private func requestPhaseSelection(_ timerPhase: PomodoroPhase) {
        guard timerPhase != engine.phase || engine.isAwaitingHandoff else { return }
        if engine.hasActiveSession {
            pendingPhaseSwitch = timerPhase
        } else {
            engine.selectPhase(timerPhase)
        }
    }

    private var phaseSwitchTitle: String {
        guard let pendingPhaseSwitch else { return "切换阶段" }
        return "切换到\(pendingPhaseSwitch.displayName)？"
    }

    private var phaseSwitchConfirmationBinding: Binding<Bool> {
        Binding(
            get: { pendingPhaseSwitch != nil },
            set: { isPresented in
                if !isPresented { pendingPhaseSwitch = nil }
            }
        )
    }

    private var nextActionLabel: String {
        if let next = engine.pendingNextPhase {
            return "开始\(next.displayName)"
        }
        return engine.isRunning ? "暂停" : "开始"
    }

    /// A one-line prompt under the controls so the finished state says what
    /// happens next instead of leaving the user guessing.
    @ViewBuilder
    private var handoffHint: some View {
        if let next = engine.pendingNextPhase {
            Text("轻触中间开始\(next.displayName)")
                .font(.footnote)
                .foregroundStyle(Color.planPrimary.opacity(0.85))
                .transition(.opacity)
        }
    }

    private var durationQuickSettings: some View {
        VStack(spacing: 5) {
            HStack(spacing: 0) {
                durationMenu(title: "专注", timerPhase: .focus)

                Divider()
                    .frame(height: 28)

                durationMenu(title: "短休", timerPhase: .shortBreak)

                // A disabled long break is not part of the cycle, so it has no
                // business occupying a third of this row. It is re-enabled in
                // 设置 → 番茄钟.
                if engine.longBreakEnabled {
                    Divider()
                        .frame(height: 28)

                    durationMenu(title: "长休", timerPhase: .longBreak)
                }
            }
            .animation(.easeInOut(duration: 0.22), value: engine.longBreakEnabled)

            Text(engine.isRunning ? "计时中暂不调整" : "点击时长调整")
                .font(.caption2)
                .foregroundStyle(Color.planSecondary.opacity(0.92))
        }
        .disabled(engine.isRunning)
        .opacity(engine.isRunning ? 0.55 : 1)
        .animation(.easeOut(duration: 0.18), value: engine.isRunning)
    }

    private func durationMenu(
        title: String,
        timerPhase: PomodoroPhase
    ) -> some View {
        let value = engine.minutes(for: timerPhase)

        // Three or four common lengths plus 自定义. The old menu also carried
        // ±1 分钟 steppers and a longer preset list, which made a routine
        // adjustment feel like a settings screen.
        return Menu {
            ForEach(timerPhase.durationPresets, id: \.self) { minutes in
                Button {
                    engine.applyDuration(minutes, to: timerPhase)
                } label: {
                    if minutes == value {
                        Label("\(minutes) 分钟", systemImage: "checkmark")
                    } else {
                        Text("\(minutes) 分钟")
                    }
                }
            }

            Divider()

            Button {
                customDurationMinutes = value
                DispatchQueue.main.async {
                    customDurationPhase = timerPhase
                }
            } label: {
                Label("自定义…", systemImage: "slider.horizontal.3")
            }
        } label: {
            VStack(spacing: 2) {
                Text(title)
                    .font(.caption2)
                    .foregroundStyle(engine.phase == timerPhase ? Color.planPrimary : .secondary)
                HStack(spacing: 2) {
                    Text("\(value) 分")
                        .font(.subheadline.weight(.medium))
                        .monospacedDigit()
                    Image(systemName: "chevron.down")
                        .font(.system(size: 7, weight: .bold))
                }
                .foregroundStyle(Color.planPrimary)
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(title)时长，当前 \(value) 分钟")
    }

    /// A plan can only be attached to a focus that has not started yet.
    private var canChoosePlan: Bool {
        engine.canChangeLinkedPlan
    }

    private var planMenuLabel: String {
        if engine.isAwaitingHandoff {
            return engine.completedPhase == .focus ? engine.title : "休息结束"
        }
        if engine.phase == .focus {
            return engine.title
        }
        return engine.isRunning ? "休息中" : "轻触开始休息"
    }

    private var focusPlanMenu: some View {
        Menu {
            Button {
                engine.unlink()
            } label: {
                Label("不关联计划", systemImage: "timer")
            }
            ForEach(availablePlans) { item in
                Button {
                    engine.link(to: item)
                } label: {
                    Label(item.title, systemImage: item.category.symbolName)
                }
            }
        } label: {
            HStack(spacing: 4) {
                Text(planMenuLabel)
                    .lineLimit(1)
                if canChoosePlan {
                    Image(systemName: "chevron.up.chevron.down")
                        .font(.system(size: 8, weight: .bold))
                }
            }
            .font(.caption)
            .foregroundStyle(.secondary)
            .frame(maxWidth: 160)
        }
        .disabled(!canChoosePlan)
    }

#if DEBUG
    private func configureQATimerIfNeeded() {
        guard
            !didConfigureQATimer,
            let scenario = ProcessInfo.processInfo.environment["MOJI_QA_SCENARIO"],
            [
                "pomodoro-motion",
                "pomodoro-end-stroke",
                "pomodoro-tail-static",
                "pomodoro-duration-editor"
            ].contains(scenario)
        else { return }

        didConfigureQATimer = true
        let defaults = SharedPersistence.sharedDefaults
        let date = Date()
        defaults.set(PomodoroStorageKeys.focusPhase, forKey: PomodoroStorageKeys.phase)
        defaults.set(false, forKey: PomodoroStorageKeys.liveActivityVisible)

        func seedRunningTimer(duration: Int, remaining: Int) {
            defaults.set(duration, forKey: PomodoroStorageKeys.phaseDuration)
            defaults.set(remaining, forKey: PomodoroStorageKeys.remaining)
            defaults.set(duration - remaining, forKey: PomodoroStorageKeys.accumulated)
            defaults.set(date.timeIntervalSince1970, forKey: PomodoroStorageKeys.segmentStart)
            defaults.set(
                date.addingTimeInterval(TimeInterval(remaining)).timeIntervalSince1970,
                forKey: PomodoroStorageKeys.target
            )
            defaults.set(true, forKey: PomodoroStorageKeys.running)
        }

        switch scenario {
        case "pomodoro-motion":
            seedRunningTimer(duration: 60, remaining: 40)
        case "pomodoro-end-stroke":
            defaults.set(false, forKey: PomodoroStorageKeys.longBreakEnabled)
            seedRunningTimer(duration: 1_000, remaining: 15)
        case "pomodoro-tail-static":
            let requested = Double(
                ProcessInfo.processInfo.environment["MOJI_QA_POMODORO_PROGRESS"] ?? ""
            ) ?? 0.97
            let bounded = min(0.999, max(0.02, requested))
            let duration = 1_000_000
            let remaining = max(1, Int((Double(duration) * (1 - bounded)).rounded()))
            defaults.set(false, forKey: PomodoroStorageKeys.longBreakEnabled)
            seedRunningTimer(duration: duration, remaining: remaining)
        default:
            customDurationMinutes = engine.minutes(for: .focus)
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) {
                customDurationPhase = .focus
            }
        }
        engine.refreshFromPreferences()
    }
#endif
}

private struct PomodoroDurationEditor: View {
    let timerPhase: PomodoroPhase
    let onSave: (Int) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var minutes: Int
    @State private var minuteText: String
    @FocusState private var isMinuteFieldFocused: Bool

    init(
        timerPhase: PomodoroPhase,
        initialMinutes: Int,
        onSave: @escaping (Int) -> Void
    ) {
        self.timerPhase = timerPhase
        self.onSave = onSave
        let boundedMinutes = min(
            timerPhase.durationRange.upperBound,
            max(timerPhase.durationRange.lowerBound, initialMinutes)
        )
        _minutes = State(initialValue: boundedMinutes)
        _minuteText = State(initialValue: String(boundedMinutes))
    }

    var body: some View {
        NavigationStack {
            ZStack {
                InkWashBackground()

                Form {
                    Section {
                        Stepper(
                            value: Binding(
                                get: { minutes },
                                set: { newValue in
                                    minutes = newValue
                                    minuteText = String(newValue)
                                }
                            ),
                            in: timerPhase.durationRange
                        ) {
                            HStack {
                                Text(timerPhase.displayName)
                                Spacer()
                                Text("\(minutes) 分钟")
                                    .font(.title3.weight(.medium))
                                    .monospacedDigit()
                            }
                        }

                        TextField(
                            "分钟",
                            text: Binding(
                                get: { minuteText },
                                set: updateMinuteText
                            )
                        )
                        .keyboardType(.numberPad)
                        .focused($isMinuteFieldFocused)
                        .onChange(of: isMinuteFieldFocused) { _, isFocused in
                            if !isFocused {
                                normalizeMinuteText()
                            }
                        }
                    } header: {
                        Text("自定义分钟数")
                    } footer: {
                        Text(
                            "可设置 \(timerPhase.durationRange.lowerBound)–\(timerPhase.durationRange.upperBound) 分钟。"
                        )
                    }

                    Section("常用时长") {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(timerPhase.durationPresets, id: \.self) { preset in
                                    Button("\(preset) 分") {
                                        minutes = preset
                                        minuteText = String(preset)
                                    }
                                    .buttonStyle(.bordered)
                                    .buttonBorderShape(.capsule)
                                    .tint(.planPrimary)
                                }
                            }
                        }
                    }
                }
                .inkFormStyle()
            }
            .navigationTitle("\(timerPhase.displayName)时长")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        let bounded = resolvedMinutes
                        onSave(bounded)
                        dismiss()
                    }
                    .fontWeight(.semibold)
                }
            }
        }
    }

    private var resolvedMinutes: Int {
        let entered = Int(minuteText) ?? minutes
        return min(
            timerPhase.durationRange.upperBound,
            max(timerPhase.durationRange.lowerBound, entered)
        )
    }

    private func updateMinuteText(_ rawValue: String) {
        let filtered = rawValue.filter(\.isNumber)
        minuteText = String(filtered.prefix(3))
        guard let entered = Int(minuteText) else { return }
        minutes = min(
            timerPhase.durationRange.upperBound,
            max(timerPhase.durationRange.lowerBound, entered)
        )
    }

    private func normalizeMinuteText() {
        minutes = resolvedMinutes
        minuteText = String(minutes)
    }
}

private struct CountdownEventListView: View {
    @ObservedObject var store: PlanStore
    let scope: CountdownScope
    let sortMode: CountdownSortMode
    @Binding var editingEvent: CountdownEvent?
    @Binding var isReordering: Bool

    /// One reference instant for the whole render. Recomputing `Date()` per
    /// event let a midnight rollover split the list against itself — an event
    /// could be filtered in as upcoming and then sorted as past.
    private var events: [CountdownEvent] {
        let now = Date()
        let matching = store.countdowns.filter { event in
            event.hasPassed(relativeTo: now) == (scope == .past)
        }
        return CountdownOrdering.sorted(matching, mode: sortMode, relativeTo: now)
    }

    private var editMode: Binding<EditMode> {
        Binding(
            get: { isReordering ? .active : .inactive },
            set: { isReordering = $0.isEditing }
        )
    }

    var body: some View {
        let events = events

        return List {
            if isReordering, !events.isEmpty {
                reorderHint
            } else if sortMode == .date, !events.isEmpty {
                sortHint
            }

            if events.isEmpty {
                emptyState
            } else {
                ForEach(events) { event in
                    eventRow(event)
                        .listRowInsets(
                            EdgeInsets(
                                top: 5,
                                leading: PlanLayout.pageHorizontalPadding,
                                bottom: 5,
                                trailing: PlanLayout.pageHorizontalPadding
                            )
                        )
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                }
                .onMove { source, destination in
                    move(events, from: source, to: destination)
                }
                .moveDisabled(sortMode == .date)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(Color.clear)
        .environment(\.editMode, editMode)
    }

    private var reorderHint: some View {
        hintRow(text: "按住右侧把手拖动排序", symbol: "line.3.horizontal")
    }

    private var sortHint: some View {
        hintRow(text: "已按时间排序 · 离今天最近的在前", symbol: "calendar.badge.clock")
    }

    private func hintRow(text: String, symbol: String) -> some View {
        Label(text, systemImage: symbol)
            .font(.caption)
            .foregroundStyle(Color.planPrimary.opacity(0.85))
            .frame(maxWidth: .infinity, alignment: .center)
            .listRowInsets(
                EdgeInsets(
                    top: 6,
                    leading: PlanLayout.pageHorizontalPadding,
                    bottom: 6,
                    trailing: PlanLayout.pageHorizontalPadding
                )
            )
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
    }

    private var emptyState: some View {
        VStack(spacing: 10) {
            InkBrushMedallion(
                symbol: scope.emptySymbol,
                tint: .planPrimary,
                size: 46,
                seed: scope == .past ? 6 : 4
            )
            Text(scope.emptyTitle)
                .font(.headline)
            Text(scope.emptyMessage)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 80)
        .padding(.horizontal, 8)
        .listRowInsets(
            EdgeInsets(
                top: 0,
                leading: PlanLayout.pageHorizontalPadding,
                bottom: 0,
                trailing: PlanLayout.pageHorizontalPadding
            )
        )
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
    }

    /// Reordering stays inside this page; the stored order still covers every
    /// event, so only the moved subset is rewritten.
    private func move(_ events: [CountdownEvent], from source: IndexSet, to destination: Int) {
        var reordered = events
        reordered.move(fromOffsets: source, toOffset: destination)
        store.reorderCountdowns(orderedIDs: reordered.map(\.id))
    }

    private func dayCount(for event: CountdownEvent, now: Date = Date()) -> CountdownDayCount {
        CountdownDayCalculator.count(
            to: event.occurrenceDate(relativeTo: now),
            from: now,
            includesToday: event.countsToday
        )
    }

    @ViewBuilder
    private func eventRow(_ event: CountdownEvent) -> some View {
        if isReordering {
            CountdownCard(event: event)
                .accessibilityLabel("\(event.title)，可拖动排序")
        } else {
            Button {
                editingEvent = event
            } label: {
                CountdownCard(event: event)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                Button {
                    store.deleteCountdown(id: event.id)
                } label: {
                    Label("删除", systemImage: "trash")
                }
                .tint(Color.planVermilion)

                Button {
                    editingEvent = event
                } label: {
                    Label("修改", systemImage: "pencil")
                }
                .tint(Color.planSecondary)
            }
        }
    }

}
