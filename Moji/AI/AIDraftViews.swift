import SwiftUI

// MARK: - Draft look

/// A draft is lighter than what is saved: thinner paper, a dashed edge.
private struct InkDraftSurfaceModifier: ViewModifier {
    var cornerRadius: CGFloat

    func body(content: Content) -> some View {
        content
            .background(
                Color.planCard.opacity(0.46),
                in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
            )
            .overlay {
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .strokeBorder(
                        Color.planPrimary.opacity(0.30),
                        style: StrokeStyle(lineWidth: 0.9, lineCap: .round, dash: [5, 4])
                    )
            }
    }
}

extension View {
    func inkDraftSurface(cornerRadius: CGFloat = 14) -> some View {
        modifier(InkDraftSurfaceModifier(cornerRadius: cornerRadius))
    }
}

/// The small 朱印 that marks something as not yet saved.
struct DraftSeal: View {
    var size: CGFloat = 20
    var seed: Int = 0

    var body: some View {
        InkSealMark(character: "稿", size: size, style: .square, seed: seed)
            .accessibilityLabel("草稿")
    }
}

/// Three blots soaking into the paper while a draft is being written.
struct InkDraftWaiting: View {
    var text = "正在起草"

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @AppStorage(PlanSettingsKeys.inkMotionLevel, store: SharedPersistence.sharedDefaults)
    private var inkMotionLevelRaw = InkMotionLevel.full.rawValue
    @State private var isSoaking = false

    private var moves: Bool {
        !reduceMotion && (InkMotionLevel(rawValue: inkMotionLevelRaw) ?? .full) != .off
    }

    var body: some View {
        HStack(spacing: 9) {
            HStack(spacing: 5) {
                ForEach(0..<3, id: \.self) { index in
                    InkStatusBlotView(tint: .planPrimary, opacity: 0.78, seed: 31 + index * 7)
                        .frame(width: 10, height: 10)
                        .opacity(moves ? (isSoaking ? 0.95 : 0.20) : 0.62)
                        .scaleEffect(moves ? (isSoaking ? 1 : 0.70) : 1)
                        .animation(
                            moves
                                ? .easeInOut(duration: 0.85).repeatForever(autoreverses: true).delay(Double(index) * 0.24)
                                : nil,
                            value: isSoaking
                        )
                }
            }
            Text(text)
                .font(.footnote)
                .foregroundStyle(Color.planSecondary)
        }
        .onAppear { isSoaking = true }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(text)
    }
}

/// What went wrong, and a way to try again. Never a blocking alert: the page
/// underneath still works as it does without a model.
struct DraftFailureView: View {
    let message: String
    var retry: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(message)
                .font(.footnote)
                .foregroundStyle(Color.planSecondary)
                .fixedSize(horizontal: false, vertical: true)
            if let retry {
                Button("再试一次", action: retry)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Color.planPrimary)
                    .buttonStyle(.plain)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .inkDraftSurface(cornerRadius: 12)
    }
}

// MARK: - Plan drafts

/// Proposed plans, shown before any of them exists. Used wherever a draft ends
/// up as plan rows: steps of a plan, next week, the days before a moment,
/// plans found in pasted text.
struct PlanDraftSheet: View {
    @ObservedObject var store: PlanStore
    let title: String
    /// One line saying what the drafts are for.
    let subject: String
    var waitingText = "正在起草"
    /// Saved into every accepted plan's note, e.g. where it came from.
    var planNote = ""
    var showsDates = true
    /// The plan the drafts replace, when the sheet offers to archive it.
    var source: CheckInItem?
    var archivesSourceByDefault = false
    /// Written into every accepted plan as 墨记's mark, e.g. 拆自「准备季度汇报」.
    var origin: String?
    /// The line kept in 今天的整理: plans added, and whether the source was archived.
    var summary: ((_ count: Int, _ archivedSource: Bool) -> String)?
    /// 拆成步骤: the steps can also go into the plan's note or a checklist memo.
    var offersDestinations = false
    /// Called after the drafts were written, with the plans added.
    var onAccept: (([CheckInItem]) -> Void)?
    let load: @MainActor () async throws -> [PlanDraft]

    @Environment(\.dismiss) private var dismiss
    @State private var detent = PresentationDetent.medium

    var body: some View {
        NavigationStack {
            PlanDraftPage(store: store, title: title, subject: subject, waitingText: waitingText,
                          planNote: planNote, showsDates: showsDates, source: source,
                          archivesSourceByDefault: archivesSourceByDefault, origin: origin,
                          summary: summary, offersDestinations: offersDestinations, onAccept: onAccept, load: load,
                          needsRoom: { detent = .large }) { dismiss() }
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("取消") { dismiss() }
                    }
                }
        }
        .presentationDetents([.medium, .large], selection: $detent)
    }
}

/// The draft list itself, for a sheet of its own or as the second page of one.
struct PlanDraftPage: View {
    @ObservedObject var store: PlanStore
    let title: String
    let subject: String
    var waitingText = "正在起草"
    var planNote = ""
    var showsDates = true
    var source: CheckInItem?
    var archivesSourceByDefault = false
    var origin: String?
    var summary: ((_ count: Int, _ archivedSource: Bool) -> String)?
    var offersDestinations = false
    var onAccept: (([CheckInItem]) -> Void)?
    let load: @MainActor () async throws -> [PlanDraft]
    /// Called once when the drafts do not fit the height the page was given,
    /// so a half-height sheet can open up instead of hiding its last rows.
    var needsRoom: (() -> Void)?
    /// Called after the selected drafts were saved.
    let close: () -> Void

    @State private var drafts: [PlanDraft] = []
    @State private var failure: String?
    @State private var isLoading = true
    @State private var attempt = 0
    @State private var archivesSource = false
    @State private var destination = StepsDestination.plans
    /// Set on the first tap, so a second tap cannot write the drafts twice.
    @State private var isAccepting = false
    @State private var contentHeight: CGFloat = 0
    @State private var pageHeight: CGFloat = 0
    @State private var askedForRoom = false

    private var selected: [PlanDraft] {
        drafts.filter { $0.isSelected && !$0.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    }

    private var showsDestinations: Bool { offersDestinations && source != nil }

    /// The source is being timed right now; a draft does not rewrite it.
    private var sourceIsFocused: Bool {
        guard let source else { return false }
        return store.isBeingFocused(source.id)
    }

    /// The line above the drafts follows where the checked steps will go.
    private var draftHint: String {
        switch showsDestinations ? destination : .plans {
        case .plans: return "这些是草稿，勾选的才会加入计划。"
        case .note: return "这些是草稿，勾选的才会写进说明。"
        case .checklist: return "这些是草稿，勾选的才会存进清单。"
        }
    }

    private var acceptTitle: String {
        switch showsDestinations ? destination : .plans {
        case .plans: return selected.isEmpty ? "加入" : "加入 \(selected.count) 条"
        case .note: return "写进说明"
        case .checklist: return "存成清单"
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(subject)
                    .font(.subheadline)
                    .foregroundStyle(Color.planSecondary)
                    .lineLimit(2)
                    .padding(.horizontal, 2)

                if isLoading {
                    InkDraftWaiting(text: waitingText)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(14)
                        .inkDraftSurface()
                } else if let failure {
                    DraftFailureView(message: failure) { attempt += 1 }
                } else {
                    HStack(spacing: 7) {
                        DraftSeal(size: 20, seed: 3)
                        Text(draftHint)
                            .font(.footnote)
                            .foregroundStyle(Color.planSecondary)
                    }
                    .padding(.horizontal, 2)

                    ForEach($drafts) { $draft in
                        PlanDraftRow(draft: $draft, showsDate: showsDates)
                    }

                    if showsDestinations {
                        Picker("去向", selection: $destination) {
                            ForEach(StepsDestination.allCases) { option in
                                Text(option.title).tag(option)
                                    .disabled(option == .note && sourceIsFocused)
                            }
                        }
                        .pickerStyle(.segmented)
                        .accessibilityIdentifier("draft.destination")
                        // A plan being timed cannot be rewritten, so its note cannot take the steps.
                        .onChange(of: destination) { previous, chosen in
                            if chosen == .note, sourceIsFocused { destination = previous }
                        }
                        if sourceIsFocused {
                            Text("这条计划正在专注，结束后再写进说明。")
                                .font(.footnote)
                                .foregroundStyle(Color.planSecondary)
                                .fixedSize(horizontal: false, vertical: true)
                                .accessibilityIdentifier("draft.destination.focused")
                        }
                    }

                    if source != nil, !showsDestinations || destination == .plans {
                        Toggle("加入后把原计划归档", isOn: $archivesSource)
                            .font(.subheadline)
                            .tint(Color.planPrimary)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 10)
                            .inkPaperSurface(cornerRadius: 14)
                    }
                }
            }
            .padding(.horizontal, PlanLayout.pageHorizontalPadding)
            .padding(.vertical, 14)
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height in
                contentHeight = height
                askForRoomIfNeeded()
            }
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height in
            pageHeight = height
            askForRoomIfNeeded()
        }
        .background { InkWashBackground() }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(acceptTitle) { accept() }
                    .fontWeight(.semibold)
                    .disabled(isLoading || selected.isEmpty || isAccepting
                              || (showsDestinations && destination == .note && sourceIsFocused))
                    .accessibilityIdentifier("draft.accept")
            }
        }
        .task(id: attempt) { await run() }
        .onAppear {
            archivesSource = archivesSourceByDefault
#if DEBUG
            if showsDestinations, !sourceIsFocused, AIQAFixture.scenario == "ai-breakdown-note" { destination = .note }
#endif
        }
    }

    private func askForRoomIfNeeded() {
        guard !askedForRoom, !isLoading, failure == nil, pageHeight > 0, contentHeight > pageHeight + 4 else { return }
        askedForRoom = true
        needsRoom?()
    }

    private func run() async {
        isLoading = true
        failure = nil
        do {
            let result = try await load()
            guard !Task.isCancelled else { return }
            drafts = result
        } catch {
            guard !Task.isCancelled else { return }
            failure = error.draftMessage
        }
        isLoading = false
    }

    private func accept() {
        guard !isAccepting else { return }
        isAccepting = true
        switch showsDestinations ? destination : .plans {
        case .plans: acceptAsPlans()
        case .note where sourceIsFocused:
            isAccepting = false
            return
        case .note: acceptIntoNote()
        case .checklist: acceptAsChecklist()
        }
        close()
    }

    private func acceptAsPlans() {
        // No length from the model: the usual length of the type, when there is one.
        let snapshot = store.snapshot
        let typical: [RecordCategory: Int?] = Dictionary(uniqueKeysWithValues: Set(selected.map(\.category)).map {
            ($0, MojiLocalRules.typicalMinutes(for: $0, snapshot: snapshot))
        })
        let plans = selected.map { draft -> CheckInItem in
            var clean = draft
            clean.title = draft.title.trimmingCharacters(in: .whitespacesAndNewlines)
            var plan = clean.plan(note: planNote, fallbackMinutes: typical[draft.category] ?? nil)
            plan.aiOrigin = origin.map { String($0.prefix(40)) }
            return plan
        }
        var changed: [CheckInItem] = []
        // A plan being timed is never archived; the words then say so.
        if archivesSource, let source, var original = store.checkInItems.first(where: { $0.id == source.id }),
           original.status != .inProgress, !store.isBeingFocused(original.id) {
            original.isArchived = true
            changed = [original]
        }
        store.commitDrafts(
            adding: plans,
            changing: changed,
            notice: changed.isEmpty ? "已加入 \(plans.count) 条计划" : "已加入 \(plans.count) 条计划，原计划已归档",
            summary: summary?(plans.count, !changed.isEmpty)
        )
        onAccept?(plans)
    }

    private var selectedSteps: [String] {
        selected.map { $0.title.trimmingCharacters(in: .whitespacesAndNewlines) }
    }

    /// 写进说明: the steps appended to the plan's own note, undoable.
    private func acceptIntoNote() {
        guard let source, var original = store.checkInItems.first(where: { $0.id == source.id }) else { return }
        let steps = selectedSteps
        original.note = AIWriteText.stepsNote(original: original.note, steps: steps)
        store.commitDrafts(changing: [original], notice: "已写进说明，可撤销",
                           summary: AIWriteText.stepsIntoNote(count: steps.count, title: original.title))
    }

    /// 存成清单: a new checklist memo named after the plan, undoable.
    private func acceptAsChecklist() {
        guard let source else { return }
        let title = store.checkInItems.first(where: { $0.id == source.id })?.title ?? source.title
        let steps = selectedSteps
        let memo = MemoItem(title: title, content: "", mode: .checklist,
                            checklistItems: steps.map { MemoChecklistItem(text: $0) })
        store.commitDrafts(addingMemos: [memo], notice: "已存成清单备忘，可撤销",
                           summary: AIWriteText.stepsAsChecklist(count: steps.count, title: title))
    }
}

/// Where the steps of 拆成步骤 go.
enum StepsDestination: String, CaseIterable, Identifiable {
    case plans, note, checklist

    var id: String { rawValue }

    var title: String {
        switch self {
        case .plans: return "加成计划"
        case .note: return "写进说明"
        case .checklist: return "存成清单"
        }
    }
}

private struct PlanDraftRow: View {
    @Binding var draft: PlanDraft
    let showsDate: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Button {
                draft.isSelected.toggle()
            } label: {
                Image(systemName: draft.isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 21))
                    .foregroundStyle(draft.isSelected ? Color.planPrimary : Color.planSecondary.opacity(0.6))
                    .frame(width: 30, height: 30)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(draft.isSelected ? "不加入这一条" : "加入这一条")

            VStack(alignment: .leading, spacing: 5) {
                TextField("计划内容", text: $draft.title, axis: .vertical)
                    .font(.body)
                    .lineLimit(1...3)
                    .foregroundStyle(Color.planPrimary)

                HStack(spacing: 8) {
                    if showsDate {
                        DatePicker("日期", selection: $draft.day, displayedComponents: .date)
                            .labelsHidden()
                            .datePickerStyle(.compact)
                            .scaleEffect(0.86, anchor: .leading)
                            .frame(height: 26)
                    }
                    Text(detail)
                        .font(.caption)
                        .foregroundStyle(Color.planSecondary)
                        .lineLimit(1)
                }

                if !draft.reason.isEmpty {
                    Text(draft.reason)
                        .font(.caption)
                        .foregroundStyle(Color.planSecondary.opacity(0.78))
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .inkDraftSurface()
        .opacity(draft.isSelected ? 1 : 0.55)
    }

    /// Everything but the day when the date picker already shows it.
    private var detail: String {
        var parts: [String] = []
        if !showsDate { parts.append(AIDrafting.dayText(draft.day)) }
        if draft.scheduleKind != .allDay { parts.append(draft.scheduleText) }
        if let minutes = draft.minutes { parts.append(DurationText.full(minutes: minutes)) }
        parts.append(draft.category.displayName)
        return parts.joined(separator: " · ")
    }
}

// MARK: - Undo

/// Says what an accepted draft changed and offers to take it back. Sits above
/// the tab bar so it is reachable from every page a draft can be accepted on.
struct DraftUndoToast: View {
    @ObservedObject var store: PlanStore

    private var lifetime: Double {
#if DEBUG
        // Stays up long enough to be photographed in a QA run.
        if AIQAFixture.isActive { return 60 }
#endif
        return store.draftUndo == nil ? 3 : 8
    }

    var body: some View {
        ZStack {
            if let notice = store.draftNotice {
                HStack(spacing: 10) {
                    Text(notice)
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(Color.planPrimary)
                        .lineLimit(2)
                    Spacer(minLength: 6)
                    if store.draftUndo != nil {
                        Button("撤销") { store.undoDrafts() }
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(Color.planVermilion)
                            .buttonStyle(.plain)
                            .frame(minWidth: 44, minHeight: 32)
                            .contentShape(Rectangle())
                            .accessibilityIdentifier("draft.undo")
                    }
                }
                .padding(.leading, 16)
                .padding(.trailing, 10)
                .padding(.vertical, 8)
                .inkPaperSurface(cornerRadius: 18, borderColor: Color.planPrimary.opacity(0.18))
                .padding(.horizontal, PlanLayout.pageHorizontalPadding)
                .padding(.bottom, 58)
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .task(id: store.draftNoticeID) {
                    do {
                        try await Task.sleep(for: .seconds(lifetime))
                        store.clearDraftNotice()
                    } catch {}
                }
            }
        }
        .animation(.easeOut(duration: 0.24), value: store.draftNotice)
    }
}
