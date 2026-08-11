import ActivityKit
import SwiftUI
import WidgetKit

@main
struct MojiWidgetBundle: WidgetBundle {
    var body: some Widget {
        // Three widgets, one per thing the app is about. The old 打卡与计时
        // widget is gone: everything it did is either on the 计划 board or in
        // the Live Activity below.
        PlanBoardWidget()
        CountdownBoardWidget()
        AnniversaryBoardWidget()
        // The Live Activity is gated on iOS 18 because that is where
        // `supplementalActivityFamilies` lives, and without it the Apple Watch
        // Smart Stack falls back to the Dynamic Island layout — whose buttons
        // are drawn but never fire, which is exactly the "按键没反应" report.
        // `WidgetBundleBuilder` has no `buildEither`, so this cannot be an
        // if/else against an iOS 17 variant; the timer, the completion
        // notification and the home screen widgets are unaffected on iOS 17.
        if #available(iOS 18.0, *) {
            PomodoroLiveActivity()
        }
    }
}

@available(iOS 18.0, *)
struct PomodoroLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: PomodoroActivityAttributes.self) { context in
            PomodoroLiveActivityLockScreenView(
                state: context.state,
                isStale: context.isStale
            )
            .activityBackgroundTint(Color.planBackground)
            .activitySystemActionForegroundColor(Color.planPrimary)
            .widgetURL(URL(string: "moji://countdowns"))
        } dynamicIsland: { context in
            let phase = PomodoroLivePhase(state: context.state, isStale: context.isStale)
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    HStack(spacing: 6) {
                        InkPhaseMark(state: context.state)
                            .frame(width: 24, height: 24)
                        Text(context.state.phaseName)
                            .font(.caption.weight(.semibold))
                    }
                    .foregroundStyle(Color.planPrimary)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    PomodoroLiveTimerText(state: context.state, phase: phase)
                        .monospacedDigit()
                        .font(.headline)
                        .invalidatableContent()
                }
                DynamicIslandExpandedRegion(.center) {
                    VStack(spacing: 2) {
                        Text(phase.isFinished ? phase.finishedHeadline : context.state.title)
                            .font(.headline)
                            .lineLimit(1)
                        WidgetInkStroke(accent: .white)
                            .frame(width: 108, height: 4)
                    }
                }
                DynamicIslandExpandedRegion(.bottom) {
                    HStack(spacing: 18) {
                        Button(intent: TogglePomodoroLiveIntent()) {
                            Label(
                                phase.primaryActionTitle,
                                systemImage: phase.primaryActionSymbol
                            )
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.black)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 7)
                            .background {
                                LiveActivityBrushButton(accent: .white, filled: true)
                            }
                        }
                        .buttonStyle(.plain)

                        if !phase.isFinished {
                            Button(intent: ResetPomodoroLiveIntent()) {
                                Label("结束", systemImage: "stop.fill")
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(.white)
                                    .padding(.horizontal, 14)
                                    .padding(.vertical, 7)
                                    .background {
                                        LiveActivityBrushButton(accent: .white)
                                    }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .background {
                        WidgetInkStroke(accent: .white)
                            .frame(height: 5)
                            .offset(y: 22)
                    }
                }
            } compactLeading: {
                InkPhaseMark(state: context.state)
                    .frame(width: 22, height: 22)
            } compactTrailing: {
                PomodoroLiveTimerText(state: context.state, phase: phase)
                    .monospacedDigit()
                    .frame(maxWidth: 48)
                    .invalidatableContent()
            } minimal: {
                InkPhaseMark(state: context.state)
                    .padding(2)
            }
            .widgetURL(URL(string: "moji://countdowns"))
            .keylineTint(Color.planPrimary)
        }
        // Renders a purpose-built card in the Apple Watch Smart Stack. Controls
        // only respond there when the Live Activity declares this family.
        .supplementalActivityFamilies([.small])
    }
}

/// What the card should say right now.
///
/// A Live Activity is only redrawn when its content changes or when its stale
/// date passes, and a suspended app can do neither. A long focus therefore used
/// to sit frozen at 00:00 with a 暂停 button for as long as the phone stayed
/// locked. The stale date is now set just past the phase end, so the system
/// redraws exactly once at that moment and this type turns the card into an
/// explicit 已结束 state instead of a stopped clock.
@available(iOS 18.0, *)
struct PomodoroLivePhase {
    let isFinished: Bool
    let phaseName: String

    init(state: PomodoroActivityAttributes.ContentState, isStale: Bool, now: Date = Date()) {
        phaseName = state.phaseName
        if let targetDate = state.targetDate, state.isRunning {
            isFinished = isStale || targetDate <= now
        } else {
            // A paused timer never goes stale; only a real zero counts.
            isFinished = state.remainingSeconds <= 0
        }
    }

    var finishedHeadline: String {
        phaseName == "专注" ? "本轮专注已结束" : "\(phaseName)已结束"
    }

    var primaryActionTitle: String {
        isFinished ? "收笔" : "暂停"
    }

    var primaryActionSymbol: String {
        isFinished ? "checkmark" : "pause.fill"
    }
}

@available(iOS 18.0, *)
private struct PomodoroLiveActivityLockScreenView: View {
    let state: PomodoroActivityAttributes.ContentState
    let isStale: Bool

    @Environment(\.activityFamily) private var activityFamily

    var body: some View {
        let phase = PomodoroLivePhase(state: state, isStale: isStale)
        switch activityFamily {
        case .small:
            watchCard(phase: phase)
        default:
            lockScreenCard(phase: phase)
        }
    }

    // MARK: - iPhone lock screen

    private func lockScreenCard(phase: PomodoroLivePhase) -> some View {
        VStack(spacing: 11) {
            HStack(spacing: 12) {
                InkPhaseMark(state: state)
                    .frame(width: 42, height: 42)

                VStack(alignment: .leading, spacing: 3) {
                    Text(phase.isFinished ? phase.finishedHeadline : state.phaseName)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Color.planPrimary)
                    Text(state.title)
                        .font(.headline)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                PomodoroLiveTimerText(state: state, phase: phase)
                    .font(.title3.weight(.semibold))
                    .monospacedDigit()
                    .invalidatableContent()
            }

            if phase.isFinished {
                Button(intent: TogglePomodoroLiveIntent()) {
                    Label("结束并记录本轮", systemImage: "checkmark")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .frame(height: 30)
                        .background {
                            LiveActivityBrushButton(accent: .planPrimary, filled: true)
                        }
                }
                .buttonStyle(.plain)
            } else {
                HStack(spacing: 10) {
                    Button(intent: TogglePomodoroLiveIntent()) {
                        Label(
                            state.isRunning ? "暂停" : "继续",
                            systemImage: state.isRunning ? "pause.fill" : "play.fill"
                        )
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .frame(height: 30)
                        .background {
                            LiveActivityBrushButton(accent: .planPrimary, filled: true)
                        }
                    }
                    .buttonStyle(.plain)

                    Button(intent: ResetPomodoroLiveIntent()) {
                        Label("结束", systemImage: "stop.fill")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Color.planPrimary)
                            .frame(maxWidth: .infinity)
                            .frame(height: 30)
                            .background {
                                LiveActivityBrushButton(accent: .planPrimary)
                            }
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background { LiveActivityInkWash() }
    }

    // MARK: - Apple Watch Smart Stack

    /// Apple asks for at most one control here, so 暂停/继续 is the button and
    /// 结束 is a plain-text second action rather than a matching pair. Ending
    /// resolves on the watch itself: a watch tap cannot hand the choice to the
    /// iPhone app the way the lock screen does, so elapsed focus is kept as a
    /// record and the plan is released rather than completed.
    private func watchCard(phase: PomodoroLivePhase) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                InkPhaseMark(state: state)
                    .frame(width: 18, height: 18)
                Text(phase.isFinished ? phase.finishedHeadline : state.phaseName)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(Color.planPrimary)
                    .lineLimit(1)
                Spacer(minLength: 2)
            }

            HStack(alignment: .firstTextBaseline, spacing: 6) {
                PomodoroLiveTimerText(state: state, phase: phase)
                    .font(.title2.weight(.semibold))
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                Spacer(minLength: 2)
                Text(state.title)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            HStack(spacing: 6) {
                Button(intent: TogglePomodoroLiveIntent()) {
                    Label(
                        phase.isFinished
                            ? "收笔"
                            : (state.isRunning ? "暂停" : "继续"),
                        systemImage: phase.isFinished
                            ? "checkmark"
                            : (state.isRunning ? "pause.fill" : "play.fill")
                    )
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 26)
                    .background {
                        LiveActivityBrushButton(accent: .planPrimary, filled: true)
                    }
                }
                .buttonStyle(.plain)

                if !phase.isFinished {
                    Button(intent: EndPomodoroLiveIntent()) {
                        Label("结束", systemImage: "stop.fill")
                            .font(.caption2.weight(.semibold))
                            .labelStyle(.iconOnly)
                            .foregroundStyle(Color.planPrimary)
                            .frame(width: 34, height: 26)
                            .background {
                                LiveActivityBrushButton(accent: .planPrimary)
                            }
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("结束本轮")
                }
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
    }
}

/// A running phase gets the system's self-updating countdown; anything else is
/// a fixed number, so a finished card reads 00:00 instead of counting into
/// negative time.
@available(iOS 18.0, *)
private struct PomodoroLiveTimerText: View {
    let state: PomodoroActivityAttributes.ContentState
    let phase: PomodoroLivePhase

    var body: some View {
        if
            !phase.isFinished,
            state.isRunning,
            let targetDate = state.targetDate,
            targetDate > Date()
        {
            Text(timerInterval: Date()...targetDate, countsDown: true)
        } else {
            Text(clockText(seconds: phase.isFinished ? 0 : state.remainingSeconds))
        }
    }

    private func clockText(seconds: Int) -> String {
        String(format: "%02d:%02d", max(0, seconds) / 60, max(0, seconds) % 60)
    }
}

@available(iOS 18.0, *)
private struct InkPhaseMark: View {
    let state: PomodoroActivityAttributes.ContentState

    var body: some View {
        GeometryReader { proxy in
            let side = min(proxy.size.width, proxy.size.height)
            ZStack {
                WidgetInkRing(accent: .planPrimary)
                    .rotationEffect(.degrees(phaseRingTilt))
                Circle()
                    .fill(Color.planPrimary.opacity(0.10))
                    .padding(side * 0.20)
                SealScriptText(
                    text: phaseCharacter,
                    size: max(8, side * 0.40)
                )
                .foregroundStyle(Color.planPrimary)
                Circle()
                    .fill(Color.planVermilion.opacity(0.92))
                    .frame(width: max(2.5, side * 0.10), height: max(2.5, side * 0.10))
                    .offset(x: side * 0.31, y: -side * 0.27)
            }
            .frame(width: proxy.size.width, height: proxy.size.height)
        }
        .accessibilityHidden(true)
    }

    private var phaseCharacter: String {
        switch state.phaseName {
        case "专注": return "专"
        case "长休息": return "养"
        default: return "休"
        }
    }

    private var phaseRingTilt: Double {
        switch state.phaseName {
        case "专注": return -4
        case "长休息": return 7
        default: return 2
        }
    }
}

private struct LiveActivityInkWash: View {
    var body: some View {
        GeometryReader { proxy in
            Canvas { context, size in
                context.drawLayer { wash in
                    wash.addFilter(.blur(radius: 14))
                    wash.fill(
                        Path(
                            ellipseIn: CGRect(
                                x: size.width * 0.60,
                                y: -size.height * 0.55,
                                width: size.width * 0.58,
                                height: size.height * 1.20
                            )
                        ),
                        with: .color(Color.planPrimary.opacity(0.08))
                    )
                    wash.fill(
                        Path(
                            ellipseIn: CGRect(
                                x: -size.width * 0.18,
                                y: size.height * 0.56,
                                width: size.width * 0.52,
                                height: size.height * 0.72
                            )
                        ),
                        with: .color(Color.planVermilion.opacity(0.05))
                    )
                }
            }
            WidgetSealMark(character: "专", style: .round, size: 20)
                .opacity(0.55)
                .position(x: proxy.size.width - 20, y: proxy.size.height - 18)
        }
        .allowsHitTesting(false)
    }
}

private struct LiveActivityBrushButton: View {
    var accent: Color
    var filled = false

    var body: some View {
        ZStack {
            UnevenRoundedRectangle(
                topLeadingRadius: 9,
                bottomLeadingRadius: 7,
                bottomTrailingRadius: 10,
                topTrailingRadius: 6,
                style: .continuous
            )
            .fill(filled ? accent : accent.opacity(0.08))
            UnevenRoundedRectangle(
                topLeadingRadius: 9,
                bottomLeadingRadius: 7,
                bottomTrailingRadius: 10,
                topTrailingRadius: 6,
                style: .continuous
            )
            .stroke(accent.opacity(filled ? 0.26 : 0.50), lineWidth: 0.8)
            WidgetInkStroke(accent: filled ? .white : accent)
                .frame(height: 4)
                .padding(.horizontal, 9)
                .frame(maxHeight: .infinity, alignment: .bottom)
                .padding(.bottom, 2)
        }
    }
}
