import SwiftUI

// The pieces that put Moji's help where the plans are: chips under the
// quick-entry field, 今日提要, 顺延旁批, and the introduction shown in place of
// any of them while AI 整理 is not set up. Vermilion and the 「稿」 seal mark
// what Moji wrote; nothing here writes a plan by itself.

// MARK: - 录入标签

/// One chip: dashed and light until adopted, solid ink once adopted.
struct QuickEntryChip: View {
    let text: String
    let isOn: Bool

    var body: some View {
        HStack(spacing: 3) {
            if isOn {
                Image(systemName: "checkmark")
                    .font(.system(size: 9, weight: .bold))
            }
            Text(text)
                .font(.footnote.weight(.semibold))
                .lineLimit(1)
        }
        .foregroundStyle(isOn ? Color.planBackground : Color.planPrimary)
        .padding(.horizontal, 10)
        .frame(minHeight: 30)
        .background(isOn ? Color.planPrimary : Color.planPrimary.opacity(0.06), in: Capsule())
        .overlay {
            if !isOn {
                Capsule().strokeBorder(
                    Color.planPrimary.opacity(0.28),
                    style: StrokeStyle(lineWidth: 0.9, lineCap: .round, dash: [4, 3])
                )
            }
        }
    }
}

/// What the words in the quick-entry field already say, read on the device.
/// A chip changes nothing until it is tapped, and only takes effect on adding.
struct QuickEntryChipsRow: View {
    let tokens: [QuickEntryToken]
    @Binding var adopted: Set<String>

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(tokens) { token in
                    let isOn = adopted.contains(token.id)
                    Button {
                        withAnimation(.easeOut(duration: 0.16)) {
                            if isOn { adopted.remove(token.id) } else { adopted.insert(token.id) }
                        }
                    } label: {
                        QuickEntryChip(text: token.text, isOn: isOn)
                            .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(isOn ? "已采用「\(token.text)」，再点取消" : "采用「\(token.text)」")
                    .accessibilityIdentifier("entry.chip.\(token.kind.rawValue)")
                }
            }
            .padding(.vertical, 1)
        }
        .scrollBounceBehavior(.basedOnSize, axes: .horizontal)
        .accessibilityIdentifier("entry.chips")
    }
}

/// The microphone inside the quick-entry field.
struct QuickEntryMicButton: View {
    let isListening: Bool
    let action: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @AppStorage(PlanSettingsKeys.inkMotionLevel, store: SharedPersistence.sharedDefaults)
    private var inkMotionLevelRaw = InkMotionLevel.full.rawValue
    @State private var breath = false

    private var moves: Bool {
        !reduceMotion && (InkMotionLevel(rawValue: inkMotionLevelRaw) ?? .full) != .off
    }

    var body: some View {
        Button(action: action) {
            ZStack {
                if isListening {
                    Circle()
                        .fill(Color.planVermilion.opacity(breath ? 0.17 : 0.09))
                        .frame(width: 30, height: 30)
                        .scaleEffect(breath ? 1.1 : 0.86)
                }
                Image(systemName: isListening ? "mic.fill" : "mic")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(isListening ? Color.planVermilion : Color.planSecondary)
            }
            .frame(width: 36, height: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(isListening ? "正在听，点一下结束" : "说一句话")
        .accessibilityIdentifier("plan.quickMic")
        .onAppear { breathe(isListening) }
        .onChange(of: isListening) { _, listening in breathe(listening) }
    }

    private func breathe(_ listening: Bool) {
        guard listening, moves else {
            breath = false
            return
        }
        withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) { breath = true }
    }
}

// MARK: - 今日提要

/// The button style shared with the suggestion strip.
struct DraftCapsuleButton: View {
    let title: String
    let identifier: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Color.planPrimary)
                .padding(.horizontal, 10)
                .frame(minHeight: 30)
                .background(Color.planPrimary.opacity(0.07), in: Capsule())
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(identifier)
    }
}

/// 今日提要: a line worked out on the device from today's plans, and below it
/// the order the model suggests, kept for the day.
struct DailyBriefCard: View {
    let factsLine: String
    /// 宜先做… — nil while there is no answer for today.
    let advice: String?
    var isLoading = false
    /// False while AI 整理 is not set up: the second line becomes a link to the introduction.
    let isReady: Bool
    var isOrdered = false
    var canBreakDown = false
    var showsClose = true
    var sort: () -> Void = {}
    var restore: () -> Void = {}
    var breakDown: () -> Void = {}
    var openIntro: () -> Void = {}
    var dismiss: () -> Void = {}

    private var showsSort: Bool { isOrdered || (isReady && advice != nil) }

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(spacing: 6) {
                DraftSeal(size: 18, seed: 14)
                Text("今日提要")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Color.planVermilion)
                Spacer(minLength: 0)
                if showsClose {
                    Button(action: dismiss) {
                        Image(systemName: "xmark")
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(Color.planSecondary.opacity(0.7))
                            .frame(width: 32, height: 30)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("今天不再显示提要")
                    .accessibilityIdentifier("brief.dismiss")
                }
            }

            // The card keeps only 2 pt on the right for the close button; the text keeps 12 like the left.
            Text(factsLine)
                .font(.footnote)
                .foregroundStyle(Color.planPrimary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.trailing, showsClose ? 10 : 0)
                .accessibilityIdentifier("brief.facts")

            if isReady {
                if let advice {
                    Text(advice)
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(Color.planPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.trailing, showsClose ? 10 : 0)
                        .transition(.opacity)
                        .accessibilityIdentifier("brief.advice")
                } else if isLoading {
                    InkDraftWaiting(text: "在排今天的先后")
                        .transition(.opacity)
                }
            } else {
                Button(action: openIntro) {
                    Text("开启后，墨记会替你排先后 ›")
                        .font(.footnote)
                        .foregroundStyle(Color.planSecondary)
                        .frame(minHeight: 28)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("brief.intro")
            }

            if showsSort || canBreakDown {
                HStack(spacing: 8) {
                    if isOrdered {
                        DraftCapsuleButton(title: "恢复原顺序", identifier: "brief.restore", action: restore)
                    } else if showsSort {
                        DraftCapsuleButton(title: "按建议排序", identifier: "brief.sort", action: sort)
                    }
                    if canBreakDown {
                        DraftCapsuleButton(title: "拆小顺延那件", identifier: "brief.breakdown", action: breakDown)
                    }
                }
                .padding(.top, 2)
            }
        }
        .padding(.leading, 12)
        .padding(.trailing, showsClose ? 2 : 12)
        .padding(.top, showsClose ? 4 : 10)
        .padding(.bottom, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .inkDraftSurface(cornerRadius: 14)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("brief.card")
    }
}

// MARK: - 顺延旁批

/// A note in the margin under a plan carried over for days. Nothing is asked
/// of the model until it is tapped.
struct CarryOverNoteLine: View {
    let daysLate: Int
    var open: () -> Void = {}
    var dismiss: () -> Void = {}

    var body: some View {
        HStack(spacing: 6) {
            Button(action: open) {
                Text(AIDailyBrief.carryNoteText(daysLate: daysLate))
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Color.planVermilion)
                    .frame(minHeight: 30)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("carry.note.open")

            Spacer(minLength: 0)

            Button(action: dismiss) {
                Text("不用")
                    .font(.caption)
                    .foregroundStyle(Color.planSecondary)
                    .padding(.horizontal, 10)
                    .frame(minHeight: 30)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("不再提醒这一条")
            .accessibilityIdentifier("carry.note.dismiss")
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("carry.note")
    }
}

// MARK: - 介绍半屏

extension View {
    /// The introduction, opened from an entry point while AI 整理 is not set up.
    func aiIntroSheet(isPresented: Binding<Bool>, startsWithDemo: Bool = false, forShare: Bool = false) -> some View {
        sheet(isPresented: isPresented) { AIIntroSheet(startsWithDemo: startsWithDemo, forShare: forShare) }
    }
}

/// 让墨记替你打下手: what the entry points would do, with the way to turn them
/// on and the way to hide them. Opening it sends nothing.
struct AIIntroSheet: View {
    enum Page: Hashable { case settings, demo }

    private struct Row {
        let symbol: String
        let title: String
        let place: String
    }

    private static let rows = [
        Row(symbol: "mic", title: "说一句话，拆成几条计划", place: "首页输入框"),
        Row(symbol: "list.bullet.indent", title: "大事拆成马上能开始的小步", place: "计划展开处、顺延印"),
        Row(symbol: "sunrise", title: "早上提要，晚上收尾", place: "今日计划上方"),
        Row(symbol: "envelope", title: "周日写好一封本周来信", place: "回顾页"),
        Row(symbol: "text.viewfinder", title: "截图、聊天记录直接变计划", place: "首页 ＋ 菜单、分享")
    ]

    /// Navigation bar, grabber and the sheet's own top inset above the content.
    private static let chrome: CGFloat = 64

    @ObservedObject private var ai = AISettingsStore.shared
    @Environment(\.dismiss) private var dismiss
    @State private var path: [Page]
    @State private var fittedHeight: CGFloat = 640
    @State private var detent: PresentationDetent
    @State private var notice: String?
    /// Opened for something shared in: a line first saying it is kept.
    private let forShare: Bool

    init(startsWithDemo: Bool = false, forShare: Bool = false) {
        self.forShare = forShare
        _path = State(initialValue: startsWithDemo ? [.demo] : [])
        _detent = State(initialValue: startsWithDemo ? .large : .height(640))
    }

    var body: some View {
        NavigationStack(path: $path) {
            ScrollView {
                content
                    .background {
                        GeometryReader { proxy in
                            Color.clear
                                .onAppear { fit(proxy.size.height) }
                                .onChange(of: proxy.size.height) { _, height in fit(height) }
                        }
                    }
            }
            .scrollBounceBehavior(.basedOnSize)
            .background { InkWashBackground() }
            .navigationTitle("让墨记替你打下手")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(Color.planSecondary)
                    }
                    .accessibilityLabel("关闭")
                    .accessibilityIdentifier("ai.intro.close")
                }
            }
            .navigationDestination(for: Page.self) { page in
                switch page {
                case .settings: AIModelSettingsView()
                case .demo: AIDemoView { path.append(.settings) }
                }
            }
        }
        .tint(Color.planPrimary)
        .onChange(of: path) { _, pages in
            if !pages.isEmpty { withAnimation(.easeOut(duration: 0.25)) { detent = .large } }
        }
        .presentationDetents([.height(fittedHeight), .large], selection: $detent)
        .presentationDragIndicator(.visible)
        .accessibilityIdentifier("ai.intro")
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 18) {
            if forShare {
                Text("分享来的内容先收着，开启 AI 整理后会整理成计划草稿。")
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(Color.planPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.bottom, -8)
                    .accessibilityIdentifier("ai.intro.share")
            }
            Text(ai.settings.isEnabled
                 ? "AI 整理已打开，还差填一个供应商的 API Key。填好后："
                 : "AI 整理现在是关着的。打开后：")
                .font(.subheadline)
                .foregroundStyle(Color.planSecondary)
                .fixedSize(horizontal: false, vertical: true)

            VStack(alignment: .leading, spacing: 12) {
                ForEach(Array(Self.rows.enumerated()), id: \.offset) { index, row in
                    HStack(spacing: 12) {
                        InkBrushMedallion(symbol: row.symbol, size: 38, seed: index + 3)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(row.title)
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(Color.planPrimary)
                            Text(row.place)
                                .font(.caption)
                                .foregroundStyle(Color.planSecondary)
                        }
                        .fixedSize(horizontal: false, vertical: true)
                    }
                    .accessibilityElement(children: .combine)
                }
            }

            VStack(spacing: 7) {
                Button {
                    path.append(.settings)
                } label: {
                    Text("去开启")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(Color.planBackground)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .background(Color.planPrimary, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("ai.intro.enable")

                Text("两步：打开「使用 AI 整理」，再填一个供应商的 API Key。")
                    .font(.caption)
                    .foregroundStyle(Color.planSecondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }

            VStack(spacing: 7) {
                Button {
                    path.append(.demo)
                } label: {
                    Text("先看演示")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(Color.planPrimary)
                        .frame(maxWidth: .infinity, minHeight: 46)
                        .overlay {
                            RoundedRectangle(cornerRadius: 14, style: .continuous)
                                .strokeBorder(Color.planPrimary.opacity(0.45), lineWidth: 1)
                        }
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("ai.intro.demo")

                Text("结果都先以草稿出现，你确认后才写入。")
                    .font(.caption)
                    .foregroundStyle(Color.planSecondary)
                    .multilineTextAlignment(.center)
            }

            VStack(spacing: 3) {
                Button("不需要？隐藏这些入口") {
                    notice = ai.tryChange { $0.showsEntriesWhenOff = false }
                    if notice == nil { dismiss() }
                }
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Color.planPrimary)
                .frame(minHeight: 36)
                .accessibilityIdentifier("ai.intro.hide")

                Text("隐藏后界面和原来一样，可在设置的「AI 模型」里重新打开。")
                    .font(.caption2)
                    .foregroundStyle(Color.planSecondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)

                if let notice {
                    Text(notice)
                        .font(.caption)
                        .foregroundStyle(Color.planVermilion)
                }
            }
            .frame(maxWidth: .infinity)
        }
        .padding(.horizontal, 22)
        .padding(.top, 6)
        .padding(.bottom, 18)
    }

    /// Sizes the half sheet to its content; taller content scrolls or pulls up.
    private func fit(_ height: CGFloat) {
        guard height > 0 else { return }
        let fitted = (height + Self.chrome).rounded()
        guard abs(fitted - fittedHeight) > 1 else { return }
        let wasFitted = detent == .height(fittedHeight)
        fittedHeight = fitted
        if wasFitted { detent = .height(fitted) }
    }
}

// MARK: - 演示

/// The four places with example content. Nothing is sent and no plan is touched.
struct AIDemoView: View {
    var enable: () -> Void

    private static let steps: [(title: String, minutes: Int)] = [
        ("列出汇报的三个要点", 15), ("找齐三个月的数据", 30), ("做第一版图表", 45), ("请同事看一遍", 15)
    ]

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @AppStorage(PlanSettingsKeys.inkMotionLevel, store: SharedPersistence.sharedDefaults)
    private var inkMotionLevelRaw = InkMotionLevel.full.rawValue
    @State private var shown = 0

    private var moves: Bool {
        !reduceMotion && (InkMotionLevel(rawValue: inkMotionLevelRaw) ?? .full) != .off
    }

    var body: some View {
        ScrollViewReader { proxy in
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Text("用示例内容走一遍，不联网，也不改动你的计划。")
                    .font(.subheadline)
                    .foregroundStyle(Color.planSecondary)
                    .fixedSize(horizontal: false, vertical: true)

                section(1, "写一句话") { entryDemo }
                section(2, "拆成步骤") { breakdownDemo }
                section(3, "今日提要") { briefDemo }
                section(4, "顺延旁批") { carryDemo }
                section(5, "晚上收尾") { wrapDemo }
                section(6, "本周来信") { letterDemo }

                Button(action: enable) {
                    Text("去开启")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(Color.planBackground)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .background(Color.planPrimary, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("ai.demo.enable")
                .id("demo.bottom")
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 14)
        }
#if DEBUG
        .onChange(of: shown) { _, step in
            guard step == 6, AIQAFixture.scenario == "ai-demo-bottom" else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                withAnimation(.easeOut(duration: 0.3)) { proxy.scrollTo("demo.bottom", anchor: .bottom) }
            }
        }
#endif
        }
        .background { InkWashBackground() }
        .navigationTitle("演示")
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("ai.demo")
        .task {
            guard shown == 0 else { return }
            guard moves else {
                shown = 6
                return
            }
            for step in 1...6 {
                try? await Task.sleep(for: .milliseconds(700))
                guard !Task.isCancelled else { return }
                withAnimation(.easeOut(duration: 0.3)) { shown = step }
            }
        }
    }

    @ViewBuilder
    private func section<Content: View>(_ index: Int, _ title: String,
                                        @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title)
                .font(.headline)
                .foregroundStyle(Color.planPrimary)
            if shown >= index {
                content()
                    .transition(.opacity)
                    .allowsHitTesting(false)
            } else if shown == index - 1 {
                InkDraftWaiting()
            }
        }
    }

    private func planLine(_ title: String, detail: String? = nil, seal: Int? = nil) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "circle")
                .font(.system(size: 19))
                .foregroundStyle(Color.planSecondary.opacity(0.6))
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(Color.planPrimary)
                if let detail {
                    Text(detail)
                        .font(.caption)
                        .foregroundStyle(Color.planSecondary)
                }
            }
            Spacer(minLength: 0)
            if let seal { CarryOverDaySeal(daysLate: seal, size: 22, animated: false) }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .inkPaperSurface(cornerRadius: 14)
        .accessibilityElement(children: .combine)
    }

    private var entryDemo: some View {
        VStack(alignment: .leading, spacing: 10) {
            VStack(alignment: .leading, spacing: 8) {
                Text("周三下午三点交周报 45分钟")
                    .font(.body)
                    .foregroundStyle(Color.planPrimary)
                HStack(spacing: 6) {
                    QuickEntryChip(text: "周三", isOn: true)
                    QuickEntryChip(text: "15:00", isOn: true)
                    QuickEntryChip(text: "45 分钟", isOn: true)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .inkPaperSurface(cornerRadius: 22)
            .accessibilityElement(children: .combine)

            planLine("交周报", detail: "周三 · 15:00 · 45 分钟 · 工作")
        }
    }

    private var breakdownDemo: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("准备季度汇报")
                .font(.caption)
                .foregroundStyle(Color.planSecondary)
            ForEach(Self.steps.indices, id: \.self) { index in
                let step = Self.steps[index]
                HStack(spacing: 10) {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 19))
                        .foregroundStyle(Color.planPrimary)
                    Text(step.title)
                        .font(.body)
                        .foregroundStyle(Color.planPrimary)
                    Spacer(minLength: 6)
                    Text(DurationText.full(minutes: step.minutes))
                        .font(.caption)
                        .foregroundStyle(Color.planSecondary)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 9)
                .inkDraftSurface()
                .accessibilityElement(children: .combine)
            }
        }
    }

    private var briefDemo: some View {
        DailyBriefCard(
            factsLine: "今天 4 件，约 2 小时 30 分钟；离「项目交付」还有 2 天；「归纳上周待办」已顺延 3 天。",
            advice: "宜先做「整理发布说明」，交付前要先定稿。",
            isReady: true,
            canBreakDown: true,
            showsClose: false
        )
        .accessibilityElement(children: .combine)
    }

    private var wrapDemo: some View {
        EveningWrapCard(
            factsLine: "今天完成 3 件，专注 1 小时 40 分钟，比这周平均多 20 分钟。",
            secondLine: "剩下 2 件这样安排：",
            rows: [
                EveningWrapRow(id: UUID(uuidString: "00000000-0000-0000-0000-0000000000d5") ?? UUID(),
                               title: "阅读产品设计章节", action: .tomorrow),
                EveningWrapRow(id: UUID(uuidString: "00000000-0000-0000-0000-0000000000d6") ?? UUID(),
                               title: "归纳上周待办", action: .split, note: "已经顺延 3 天")
            ],
            isReady: true,
            showsClose: false
        )
        .accessibilityElement(children: .combine)
    }

    private var letterDemo: some View {
        WeeklyLetterCard(
            range: "11月16日 – 22日",
            content: .letter(
                WeeklyLetter(week: "2026-11-16", title: "卡在第一步的一周",
                             body: "前半周很稳；周四起两件大事一直顺延，都卡在没拆开的第一步。",
                             nextFocus: "", facts: "",
                             evidence: [WeeklyLetter.Evidence(point: "「归纳上周待办」顺延 4 次", plans: []),
                                        WeeklyLetter.Evidence(point: "「准备季度汇报」还没开始", plans: [])],
                             createdAt: 0),
                facts: "这周完成 9/14，专注 6 小时 20 分钟，比上周多 1 小时。"
            ),
            showsClose: false
        )
        .accessibilityElement(children: .combine)
    }

    private var carryDemo: some View {
        VStack(alignment: .leading, spacing: 2) {
            planLine("归纳上周待办", seal: 3)
            CarryOverNoteLine(daysLate: 3)
                .padding(.leading, 46)
                .padding(.trailing, 4)
                .accessibilityElement(children: .combine)
        }
    }
}
