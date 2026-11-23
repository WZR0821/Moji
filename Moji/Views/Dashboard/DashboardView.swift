import SwiftUI
import UIKit

private struct PlanEditorRoute: Identifiable {
    let id = UUID()
    let item: CheckInItem?
    let defaultKind: CheckInKind
    let defaultDate: Date
}

private struct PlanBreakdownRoute: Identifiable {
    let id = UUID()
    let item: CheckInItem
    /// True when the steps are meant to take the plan's place.
    let replacesOriginal: Bool
}

struct DashboardView: View {
    @ObservedObject var store: PlanStore
    @ObservedObject var pomodoro: PomodoroEngine

    @Environment(\.scenePhase) private var scenePhase
    @State private var isQuickEntryFocused = false
    @State private var quickTitle = ""
    @State private var expandedItemID: UUID?
    @State private var editorRoute: PlanEditorRoute?
    @State private var showsCompleted = false
    @State private var showsLibrary = false
    @State private var copyingItem: CheckInItem?
    @State private var deletingItem: CheckInItem?
    @ObservedObject private var ai = AISettingsStore.shared
    /// What was read out of plans typed a moment ago, by plan. Never saved.
    @State private var entrySuggestions: [UUID: EntrySuggestion] = [:]
    @State private var readingEntryIDs: Set<UUID> = []
    @State private var breakdownRoute: PlanBreakdownRoute?
    @State private var showsImport = false
    /// Chips under the quick-entry field the user tapped; used once, on adding.
    @State private var adoptedChipIDs: Set<String> = []
    @StateObject private var speech = SpeechCapture()
    @ObservedObject private var daily = AIDailyState.shared
    @State private var showsAIIntro = false
    @State private var introStartsWithDemo = false
    /// The introduction opened for something shared in while AI 整理 is not set up.
    @State private var introForShare = false
    @ObservedObject private var wrap = AIEveningWrapState.shared
    @ObservedObject private var writes = AIWriteLog.shared
    /// 逐条改: the choices made on the card, in memory only.
    @State private var wrapEdits: [UUID: WrapAction] = [:]
    @State private var isEditingWrap = false
    @State private var showsWrites = false
    /// Something shared from another app, being turned into drafts.
    @State private var sharedItem: ShareInboxItem?
#if DEBUG
    @State private var showsCalendar = [
        "calendar-month",
        "calendar-week",
        "calendar-day",
        "calendar-record-delete"
    ].contains(ProcessInfo.processInfo.environment["MOJI_QA_SCENARIO"] ?? "")
#else
    @State private var showsCalendar = false
#endif
    @State private var checklistDate = Date()
    @State private var isPickingRestoreFile = false
    @State private var isPickingRestoreFolder = false
    @State private var didDismissRestorePrompt = false
    @State private var restoreMessage: String?
    @State private var showsWelcomeRestore = false
    @AppStorage("minuteplan.onboarding.didOfferRestore", store: SharedPersistence.sharedDefaults)
    private var didOfferRestore = false
#if DEBUG
    @State private var didRunQAScenario = false
#endif

    /// Today's open plans in time order: what 今日提要 reads and sends.
    private var schedulePendingItems: [CheckInItem] {
        store.checkInItems
            .filter {
                $0.kind == .planned
                    && ($0.status == .planned || $0.status == .inProgress)
                    && $0.isVisibleInChecklist(on: checklistDate)
            }
            .sorted {
                if $0.scheduledStart != $1.scheduledStart {
                    return $0.scheduledStart < $1.scheduledStart
                }
                return $0.createdAt < $1.createdAt
            }
    }

    /// The list as shown: in time order, or in the order 今日提要 suggested
    /// once the user tapped 按建议排序. That order is kept for the day only.
    private var pendingItems: [CheckInItem] {
        let items = schedulePendingItems
        guard ai.settings.dailyBrief, ai.showsEntries, isShowingToday,
              let order = daily.orderIDs(on: checklistDate) else { return items }
        return AIDailyBrief.ordered(items, by: order)
    }

    private var isShowingToday: Bool { Calendar.current.isDateInToday(checklistDate) }

    /// Only what was finished on the day the checklist is showing. Yesterday's
    /// finished plans belong to yesterday — they stay in the calendar and the
    /// review pages instead of piling up under 今日计划.
    private var completedItems: [CheckInItem] {
        completedItemsForChecklistDate
            .sorted { ($0.completedAt ?? $0.createdAt) > ($1.completedAt ?? $1.createdAt) }
    }

    private var completedItemsForChecklistDate: [CheckInItem] {
        let calendar = Calendar.mojiISO
        return store.checkInItems.filter {
            guard
                $0.kind == .planned,
                $0.isArchived != true,
                $0.isCompleted || $0.status == .skipped
            else { return false }

            return calendar.isDate(
                $0.completedAt ?? $0.scheduledStart,
                inSameDayAs: checklistDate
            )
        }
    }

    var body: some View {
        NavigationStack {
            ZStack {
                InkWashBackground()

                ScrollViewReader { proxy in
                List {
                    if showsRestorePrompt {
                        restorePrompt
                            .listRowInsets(
                                EdgeInsets(
                                    top: 10,
                                    leading: PlanLayout.pageHorizontalPadding,
                                    bottom: 4,
                                    trailing: PlanLayout.pageHorizontalPadding
                                )
                            )
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                    }

                    quickEntry
                        .listRowInsets(
                            EdgeInsets(
                                top: 10,
                                leading: PlanLayout.pageHorizontalPadding,
                                bottom: 7,
                                trailing: PlanLayout.pageHorizontalPadding
                            )
                        )
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)

                    todayHeading
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

                    if showsWrap {
                        eveningWrapCard
                            .listRowInsets(
                                EdgeInsets(
                                    top: 3,
                                    leading: PlanLayout.pageHorizontalPadding,
                                    bottom: 7,
                                    trailing: PlanLayout.pageHorizontalPadding
                                )
                            )
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                            .transition(.opacity)
                    } else if showsBrief {
                        dailyBriefCard
                            .listRowInsets(
                                EdgeInsets(
                                    top: 3,
                                    leading: PlanLayout.pageHorizontalPadding,
                                    bottom: 7,
                                    trailing: PlanLayout.pageHorizontalPadding
                                )
                            )
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                            .transition(.opacity)
                    }

                    if let active = store.activeSession {
                        activeStrip(active)
                            .listRowInsets(
                                EdgeInsets(
                                    top: 7,
                                    leading: PlanLayout.pageHorizontalPadding,
                                    bottom: 7,
                                    trailing: PlanLayout.pageHorizontalPadding
                                )
                            )
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                    } else if pomodoro.hasActiveSession {
                        pomodoroStrip
                            .listRowInsets(
                                EdgeInsets(
                                    top: 7,
                                    leading: PlanLayout.pageHorizontalPadding,
                                    bottom: 7,
                                    trailing: PlanLayout.pageHorizontalPadding
                                )
                            )
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                    }

                    if pendingItems.isEmpty {
                        quietEmptyState
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                    } else {
                        ForEach(pendingItems) { item in
                            planRow(item, archived: false)
                        }
                    }

                    if !completedItems.isEmpty {
                        Button {
                            withAnimation(.easeInOut(duration: 0.2)) {
                                showsCompleted.toggle()
                            }
                        } label: {
                            HStack {
                                Text("今日已结束 \(completedItems.count)")
                                Spacer()
                                Image(systemName: "chevron.down")
                                    .rotationEffect(.degrees(showsCompleted ? 180 : 0))
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                        }
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .buttonStyle(.plain)
                        .listRowInsets(EdgeInsets(top: 14, leading: 22, bottom: 8, trailing: 22))
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)

                        if showsCompleted {
                            ForEach(completedItems) { item in
                                planRow(item, archived: true)
                            }
                        }
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .scrollDismissesKeyboard(.interactively)
                .refreshable {
                    checklistDate = Date()
                    store.reload()
                }
                .animation(
                    .spring(response: 0.42, dampingFraction: 0.84),
                    value: pendingItems.map(\.id)
                )
                .onChange(of: expandedItemID) { _, id in
                    guard let id else { return }
                    let prefix = pendingItems.contains { $0.id == id } ? "pending" : "archived"
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                        withAnimation(.easeInOut(duration: 0.25)) { proxy.scrollTo("\(prefix)-\(id.uuidString)", anchor: .top) }
                    }
                }
                }
            }
            .navigationTitle("计划")
            .toolbarBackground(Color.planBackground, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        showsCalendar = true
                    } label: {
                        Image(systemName: "calendar")
                    }
                    .accessibilityLabel("打开计划日历")
                }

                ToolbarItem(placement: .topBarTrailing) {
                    Button { showsLibrary = true } label: { Image(systemName: "square.grid.2x2") }
                        .accessibilityLabel("计划库与模板")
                }
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button {
                            presentNewEditor(kind: .completedLog)
                        } label: {
                            Label("记录已做", systemImage: "clock.badge.checkmark")
                        }

                        Button {
                            presentNewEditor(kind: .planned)
                        } label: {
                            Label("详细添加计划", systemImage: "slider.horizontal.3")
                        }

                        if ai.showsEntries {
                            Button {
                                if ai.isReady { showsImport = true } else { showsAIIntro = true }
                            } label: {
                                Label("从文字或截图添加", systemImage: "text.viewfinder")
                            }
                        }
                    } label: {
                        Image(systemName: "plus")
                    }
                    .accessibilityLabel("添加计划或记录")
                }
            }
            .navigationDestination(isPresented: $showsCalendar) {
                PlanCalendarView(store: store)
            }
            .onAppear {
                checklistDate = Date()
                offerRestoreOnFirstLaunch()
#if DEBUG
                runEditorQAScenarioIfNeeded()
#endif
                refreshDailyBrief()
                refreshEveningWrap()
                takeSharedItemIfNeeded()
            }
            .onDisappear { speech.stop() }
            .onReceive(
                NotificationCenter.default.publisher(for: .mojiOpenPlanEditor)
            ) { notification in
                openPlanEditor(for: notification.object as? UUID)
            }
            .onChange(of: scenePhase) { _, newValue in
                guard newValue == .active else {
                    speech.stop()
                    return
                }
                checklistDate = Date()
                store.reload()
                refreshDailyBrief()
                refreshEveningWrap()
                takeSharedItemIfNeeded()
            }
            .onReceive(NotificationCenter.default.publisher(for: .mojiStartSaying)) { _ in
                startSaying()
            }
            .onChange(of: quickTitle) { _, _ in
                // A chip stays adopted while its words stay; changed words are a new chip.
                let ids = Set(quickTokens.map(\.id))
                if !adoptedChipIDs.isSubset(of: ids) { adoptedChipIDs.formIntersection(ids) }
            }
            .sheet(item: $editorRoute) { route in
                CheckInEditorView(
                    store: store,
                    item: route.item,
                    defaultKind: route.defaultKind,
                    defaultDate: route.defaultDate
                )
                .id(route.id)
            }
            .sheet(isPresented: $showsLibrary) { PlanLibraryView(store: store) }
            .sheet(item: $copyingItem) { PlanCopySheet(store: store, item: $0) }
            .sheet(isPresented: $showsImport, onDismiss: { sharedItem = nil }) {
                PlanImportView(store: store, shared: sharedItem)
            }
            .sheet(isPresented: $showsWrites) { AIWritesSheet(store: store) }
            .aiIntroSheet(isPresented: $showsAIIntro, startsWithDemo: introStartsWithDemo, forShare: introForShare)
            .onChange(of: showsAIIntro) { _, shown in if !shown { introForShare = false } }
            .sheet(item: $breakdownRoute) { route in
                PlanDraftSheet(
                    store: store,
                    title: "拆成步骤",
                    subject: route.item.title,
                    waitingText: "正在拆步骤",
                    planNote: "拆自：\(route.item.title)",
                    showsDates: false,
                    source: route.item,
                    archivesSourceByDefault: route.replacesOriginal,
                    origin: AIWriteText.splitOrigin(route.item.title),
                    summary: { count, archived in
                        AIWriteText.breakdown(count: count, title: route.item.title, archived: archived)
                    },
                    load: { try await AIAssistant.shared.breakDown(route.item) }
                )
            }
            .alert("删除这个计划？", isPresented: Binding(get: { deletingItem != nil }, set: { if !$0 { deletingItem = nil } })) {
                Button("取消", role: .cancel) { deletingItem = nil }
                Button("删除", role: .destructive) {
                    if let item = deletingItem { store.deleteCheckIn(id: item.id) }
                    deletingItem = nil
                }
            } message: { Text(deletingItem?.title ?? "") }
            .overlay(alignment: .bottom) {
                if let notice = store.workflowNotice, !showsLibrary {
                    Text(notice).font(.footnote).padding(12)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                        .padding(.horizontal, 18).padding(.bottom, 8)
                        .allowsHitTesting(false)
                }
            }
            .task(id: store.workflowNotice) {
                guard let notice = store.workflowNotice else { return }
                try? await Task.sleep(for: .seconds(4))
                if !Task.isCancelled, store.workflowNotice == notice { store.workflowNotice = nil }
            }
            .sheet(isPresented: $showsWelcomeRestore) {
                RestoreWelcomeSheet(store: store) { message in
                    restoreMessage = message
                }
            }
            .alert(
                "从备份恢复",
                isPresented: Binding(
                    get: { restoreMessage != nil },
                    set: { if !$0 { restoreMessage = nil } }
                )
            ) {
                Button("好", role: .cancel) {}
            } message: {
                Text(restoreMessage ?? "")
            }
        }
    }

    /// A re-signed install starts empty and the user's first question is always
    /// "我的数据呢". Asking once, up front, is the answer.
    private func offerRestoreOnFirstLaunch() {
        guard
            !didOfferRestore,
            store.isEmpty,
            !ExternalBackupService.shared.isConfigured
        else { return }
        showsWelcomeRestore = true
    }

    private func openPlanEditor(for id: UUID?) {
        guard
            let id,
            let item = store.checkInItems.first(where: { $0.id == id })
        else { return }
        editorRoute = PlanEditorRoute(
            item: item,
            defaultKind: item.kind,
            defaultDate: item.scheduledStart
        )
    }

    private var quickEntry: some View {
        let tokens = quickTokens
        return VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                IMESafeTextField(
                    placeholder: "写下一件要做的事",
                    text: $quickTitle,
                    isFocused: $isQuickEntryFocused,
                    keepsFocusOnSubmit: true,
                    accessibilityIdentifier: "plan.quickTitle",
                    onSubmit: addQuickPlan
                )

                // 说一句添加 turns the microphone on even with the entry points hidden.
                if (ai.showsEntries && speech.isAvailable) || speech.isListening {
                    QuickEntryMicButton(isListening: speech.isListening, action: toggleSpeech)
                        .padding(.trailing, -8)
                }

                Button {
                    IMETextInput.commit(addQuickPlan)
                } label: {
                    InkBrushMedallion(
                        symbol: "plus",
                        tint: .planPrimary,
                        size: 36
                    )
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(cleanQuickTitle.isEmpty)
                .opacity(cleanQuickTitle.isEmpty ? 0.35 : 1)
                .accessibilityLabel("添加文字计划")
            }
            .frame(height: 52)

            if !tokens.isEmpty {
                QuickEntryChipsRow(tokens: tokens, adopted: $adoptedChipIDs)
                    .padding(.trailing, 8)
                    .padding(.bottom, 11)
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .padding(.leading, 16)
        .padding(.trailing, 8)
        .inkPaperSurface(
            cornerRadius: 25,
            showsBrushMark: false,
            borderColor: Color.planPrimary.opacity(isQuickEntryFocused ? 0.30 : 0.095),
            borderWidth: isQuickEntryFocused ? 1.1 : 0.75
        )
        .animation(.easeOut(duration: 0.22), value: isQuickEntryFocused)
        .animation(.easeOut(duration: 0.22), value: tokens.isEmpty)
    }

    /// What the words in the field already say, read on the device. Nothing is
    /// sent for this; the chips only take effect if tapped before adding.
    private var quickTokens: [QuickEntryToken] {
        guard ai.settings.readsQuickEntry, ai.showsEntries, !cleanQuickTitle.isEmpty else { return [] }
        return QuickEntryParser.tokens(
            in: quickTitle,
            categories: AIAssistant.categories(),
            defaultCategory: quickEntryCategory
        )
    }

    private func toggleSpeech() {
        speech.toggle(appendingTo: quickTitle, write: { quickTitle = $0 }) {
            store.workflowNotice = "没有麦克风或语音识别权限，可以在系统设置里打开。"
        }
    }

    // MARK: 今日提要

    private var briefFacts: DailyBriefFacts {
        AIDailyBrief.facts(pending: schedulePendingItems, countdowns: store.countdowns, today: checklistDate)
    }

    private var showsBrief: Bool {
        ai.settings.dailyBrief && ai.showsEntries && isShowingToday
            && !daily.isDismissed(on: checklistDate)
            && AIDailyBrief.shouldShow(briefFacts)
    }

    private var dailyBriefCard: some View {
        let facts = briefFacts
        let pending = pendingItems
        let first = ai.isReady
            ? AIDailyBrief.firstPlan(order: daily.briefOrder(on: checklistDate), pending: pending)
            : nil
        let advice = first.map {
            AIDailyBrief.adviceLine(title: $0.item.title,
                                    reason: $0.isModelFirst ? daily.brief(on: checklistDate)?.reason : nil)
        }
        let longest = facts.carried.first
        return DailyBriefCard(
            factsLine: AIDailyBrief.factsLine(facts),
            advice: advice,
            isLoading: daily.isLoading,
            isReady: ai.isReady,
            isOrdered: daily.orderIDs(on: checklistDate) != nil,
            canBreakDown: longest?.isPlanned == true,
            sort: {
                withAnimation(.spring(response: 0.42, dampingFraction: 0.84)) {
                    daily.applyOrder(daily.briefOrder(on: checklistDate), on: checklistDate)
                }
            },
            restore: {
                withAnimation(.spring(response: 0.42, dampingFraction: 0.84)) { daily.clearOrder() }
            },
            breakDown: {
                guard let id = longest?.id, let item = pending.first(where: { $0.id == id }) else { return }
                openBreakdown(item, replacesOriginal: true)
            },
            openIntro: { showsAIIntro = true },
            dismiss: {
                withAnimation(.easeOut(duration: 0.2)) { daily.dismiss(on: checklistDate) }
            }
        )
        .animation(.easeOut(duration: 0.25), value: advice)
        .animation(.easeOut(duration: 0.25), value: daily.isLoading)
    }

    /// Asked for once a day while the card is on screen; see AIDailyState.
    private func refreshDailyBrief() {
        guard showsBrief, !showsWrap else { return }
        daily.refreshIfNeeded(pending: schedulePendingItems, countdowns: store.countdowns)
    }

    // MARK: 晚间收尾

    private var wrapFacts: EveningWrapFacts {
        AIEveningWrap.facts(items: store.checkInItems, records: store.records, today: checklistDate)
    }

    /// After 21:00 on today's list, in the place of 今日提要.
    private var showsWrap: Bool {
        let now = AIClock.now()
        return ai.settings.eveningWrap && ai.showsEntries && isShowingToday
            && AIEveningWrap.isEvening(now) && !wrap.isDismissed(on: now)
            && AIEveningWrap.shouldShow(wrapFacts, pending: pendingItems.count)
    }

    private var eveningWrapCard: some View {
        let now = AIClock.now()
        let pending = pendingItems
        let applied = wrap.isApplied(on: now)
        let saved = ai.isReady ? wrap.arrangement(on: now) : [:]
        let arranged = !applied && pending.contains { saved[$0.id] != nil }
        let rows: [EveningWrapRow] = arranged
            ? pending.prefix(AIEveningWrap.maximumRows).map { item in
                let model = saved[item.id]?.action ?? .tomorrow
                let action = wrapEdits[item.id] ?? model
                return EveningWrapRow(id: item.id, title: item.title, action: action,
                                      note: action == model ? saved[item.id]?.note : nil)
            }
            : []
        return EveningWrapCard(
            factsLine: AIEveningWrap.factsLine(wrapFacts),
            secondLine: AIEveningWrap.secondLine(applied: applied, pending: pending.count, hasArrangement: arranged),
            rows: rows,
            hiddenCount: arranged ? max(0, pending.count - AIEveningWrap.maximumRows) : 0,
            isLoading: wrap.isLoading && !applied,
            isReady: ai.isReady,
            isEditing: isEditingWrap,
            writesCount: writes.activeCount(on: now),
            choose: { id, action in wrapEdits[id] = action },
            apply: { applyWrap(pending: pending, saved: saved) },
            toggleEditing: { withAnimation(.easeOut(duration: 0.2)) { isEditingWrap.toggle() } },
            openIntro: { showsAIIntro = true },
            openWrites: { showsWrites = true },
            dismiss: {
                withAnimation(.easeOut(duration: 0.2)) { wrap.dismiss(on: now) }
            }
        )
        .animation(.easeOut(duration: 0.25), value: rows)
        .animation(.easeOut(duration: 0.25), value: wrap.isLoading)
    }

    /// Asked for once an evening while the card is on screen; see AIEveningWrapState.
    private func refreshEveningWrap() {
        guard showsWrap else { return }
        wrap.refreshIfNeeded(done: completedItemsForChecklistDate.filter(\.isCompleted),
                             pending: schedulePendingItems, countdowns: store.countdowns, now: AIClock.now())
    }

    /// 照此处理: one write for every plan left, undoable, kept in 今天的整理.
    /// Plans past the six rows are carried to tomorrow as usual.
    private func applyWrap(pending: [CheckInItem], saved: [UUID: AIEveningWrapState.Entry]) {
        let now = AIClock.now()
        let plans = pending.enumerated().map { index, item -> (item: CheckInItem, action: WrapAction) in
            guard index < AIEveningWrap.maximumRows else { return (item, .tomorrow) }
            return (item, wrapEdits[item.id] ?? saved[item.id]?.action ?? .tomorrow)
        }
        let outcome = AIEveningWrap.apply(plans, today: now)
        withAnimation(.spring(response: 0.42, dampingFraction: 0.84)) {
            if !outcome.changed.isEmpty {
                store.commitDrafts(changing: outcome.changed, notice: "已照此安排，可撤销", summary: outcome.summary)
            }
            wrap.markApplied(on: now)
            isEditingWrap = false
            wrapEdits = [:]
        }
        if let id = outcome.firstSplit, let item = store.checkInItems.first(where: { $0.id == id }) {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { openBreakdown(item, replacesOriginal: true) }
        }
    }

    // MARK: 分享、说一句添加

    /// The oldest thing shared in from another app: drafts at once when a
    /// model is set up, otherwise the introduction, keeping the item for later.
    /// The import page takes the item out of the inbox once it has read it:
    /// removing it here would delete a shared picture before it is read.
    private func takeSharedItemIfNeeded() {
        guard sharedItem == nil, !showsImport, !showsAIIntro, let item = ShareInbox.oldest() else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
            guard sharedItem == nil, !showsImport, !showsAIIntro else { return }
            if ai.isReady {
                sharedItem = item
                showsImport = true
            } else {
                introStartsWithDemo = false
                introForShare = true
                showsAIIntro = true
            }
        }
    }

    /// 说一句添加: today's list, the field focused and the microphone on (only
    /// the keyboard when speech cannot be recognised here).
    private func startSaying() {
        checklistDate = Date()
        expandedItemID = nil
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
            isQuickEntryFocused = true
#if DEBUG
            if AIQAFixture.scenario == "ai-say" {
                speech.simulateForQA(text: "明天上午交周报") { quickTitle = $0 }
                return
            }
#endif
            guard speech.isAvailable, !speech.isListening else { return }
            toggleSpeech()
        }
    }

    // MARK: 顺延旁批

    /// Days late when the note in the margin belongs under this row.
    private func carryNoteDays(for item: CheckInItem) -> Int? {
        guard ai.settings.carryOverNotes, ai.showsEntries, item.status == .planned,
              !daily.isCarryNoteDismissed(item.id),
              let days = CarryOverSeal.daysLate(scheduledStart: item.scheduledStart, on: checklistDate),
              days >= AIDailyBrief.carryNoteDays else { return nil }
        return days
    }

    /// 拆成步骤 from any entry point: the draft sheet when a model is set up,
    /// otherwise the introduction. Nothing is sent from the introduction.
    private func openBreakdown(_ item: CheckInItem, replacesOriginal: Bool) {
        guard ai.isReady else {
            showsAIIntro = true
            return
        }
        breakdownRoute = PlanBreakdownRoute(item: item, replacesOriginal: replacesOriginal)
    }

    private var todayHeading: some View {
        HStack(spacing: 10) {
            InkSealMark(
                character: "今",
                size: 25,
                style: .doubleSquare,
                seed: InkVariant.seed(for: checklistDate.description)
            )

            VStack(alignment: .leading, spacing: 2) {
                Text("今日计划")
                    .font(.subheadline.weight(.semibold))
                Text(
                    checklistDate.formatted(
                        .dateTime.month(.wide).day().weekday(.wide)
                    )
                )
                .font(.caption2)
                .foregroundStyle(.secondary)
            }

            Spacer(minLength: 12)

            InkBrushDivider(
                tint: .planPrimary,
                animated: false,
                seed: InkVariant.seed(for: "dashboard-today-heading")
            )
            .frame(width: 44)
            .opacity(0.26)

            let completedCount = completedItemsForChecklistDate.filter(\.isCompleted).count
            let totalCount = pendingItems.count + completedItemsForChecklistDate.count
            Text(totalCount == 0 ? "今日无计划" : "完成 \(completedCount)/\(totalCount)")
                .font(.caption.weight(.semibold).monospacedDigit())
                .foregroundStyle(totalCount == 0 ? Color.secondary : Color.planPrimary)
                .multilineTextAlignment(.trailing)
                .frame(minWidth: 78, alignment: .trailing)
        }
        .frame(minHeight: 39)
        .accessibilityElement(children: .combine)
    }

    /// Shown only on a genuinely empty install. After a re-sign that is exactly
    /// what the user sees, and this turns "所有数据都没了" into one tap.
    private var showsRestorePrompt: Bool {
        store.isEmpty && !didDismissRestorePrompt
    }

    private var restorePrompt: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack(spacing: 8) {
                InkSealMark(character: "复", size: 26, style: .round, seed: 11)
                Text("本机还没有数据")
                    .font(.subheadline.weight(.semibold))
                Spacer()
                Button {
                    withAnimation(.easeOut(duration: 0.2)) {
                        didDismissRestorePrompt = true
                    }
                } label: {
                    Image(systemName: "xmark")
                        .font(.caption2.weight(.bold))
                        .foregroundStyle(.tertiary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("关闭恢复提示")
            }

            Text(BackupRestoreCopy.folderHint)
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            Button {
                isPickingRestoreFolder = true
            } label: {
                Text("选择备份文件夹恢复")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.planVermilion)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Button {
                isPickingRestoreFile = true
            } label: {
                Text("改为选择单个备份文件")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(14)
        .inkPaperSurface(cornerRadius: 16, showsBrushMark: true)
        .fileImporter(
            isPresented: $isPickingRestoreFolder,
            allowedContentTypes: [.folder]
        ) { result in
            switch result {
            case let .success(url):
                restoreBackup(fromFolderAt: url)
            case let .failure(error):
                restoreMessage = error.localizedDescription
            }
        }
        .fileImporter(
            isPresented: $isPickingRestoreFile,
            allowedContentTypes: MojiBackupFile.readableContentTypes
        ) { result in
            switch result {
            case let .success(url):
                restoreBackup(fromFileAt: url)
            case let .failure(error):
                restoreMessage = error.localizedDescription
            }
        }
    }

    private func restoreBackup(fromFolderAt url: URL) {
        do {
            try BackupRestoreCoordinator.restore(fromFolderAt: url, into: store)
            didOfferRestore = true
            withAnimation(.easeOut(duration: 0.25)) {
                didDismissRestorePrompt = true
            }
        } catch {
            restoreMessage = "无法从这个文件夹恢复：\(error.localizedDescription)"
        }
    }

    private func restoreBackup(fromFileAt url: URL) {
        do {
            let didAdoptFolder = try BackupRestoreCoordinator.restore(
                fromFileAt: url,
                into: store
            )
            didOfferRestore = true
            withAnimation(.easeOut(duration: 0.25)) {
                didDismissRestorePrompt = true
            }
            if !didAdoptFolder {
                restoreMessage = BackupRestoreCopy.folderStillNeeded
            }
        } catch {
            restoreMessage = "这份文件无法恢复：\(error.localizedDescription)"
        }
    }

    private var quietEmptyState: some View {
        VStack(spacing: 10) {
            InkBrushMedallion(symbol: "checkmark", tint: .planPrimary, size: 44)
            Text("今日留白")
                .font(.title3.weight(.medium))
            Text("先写一句，时间与日历稍后再定。")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 46)
    }

    @ViewBuilder
    private func planRow(_ item: CheckInItem, archived: Bool) -> some View {
        let row = MinimalPlanRow(
            store: store,
            pomodoro: pomodoro,
            item: item,
            checklistDate: checklistDate,
            isExpanded: expandedItemID == item.id,
            toggleExpansion: {
                withAnimation(.easeInOut(duration: 0.2)) {
                    expandedItemID = expandedItemID == item.id ? nil : item.id
                }
            },
            openEditor: { openEditor(for: item) },
            openCopy: { copyingItem = item },
            breakDown: ai.showsEntries && !archived
                ? { replaces in openBreakdown(item, replacesOriginal: replaces) }
                : nil
        )
        VStack(spacing: 0) {
            Group {
                if expandedItemID == item.id {
                    row
                        .padding(.horizontal, 14)
                        .inkPaperSurface(cornerRadius: 14)
                } else {
                    row
                        .padding(.horizontal, 4)
                }
            }

            if !archived {
                if let suggestion = entrySuggestions[item.id] {
                    EntrySuggestionStrip(
                        suggestion: suggestion,
                        apply: { applySuggestion(suggestion, to: item.id) },
                        split: { splitEntry(suggestion, id: item.id) },
                        dismiss: { withAnimation(.easeOut(duration: 0.2)) { entrySuggestions[item.id] = nil } }
                    )
                    .padding(.leading, 46)
                    .padding(.trailing, 4)
                    .padding(.bottom, 8)
                    .transition(.opacity.combined(with: .move(edge: .top)))
                } else if readingEntryIDs.contains(item.id) {
                    InkDraftWaiting(text: "在看这句话")
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.leading, 58)
                        .padding(.bottom, 8)
                        .transition(.opacity)
                }

                if let days = carryNoteDays(for: item) {
                    CarryOverNoteLine(
                        daysLate: days,
                        open: { openBreakdown(item, replacesOriginal: true) },
                        dismiss: { withAnimation(.easeOut(duration: 0.2)) { daily.dismissCarryNote(item.id) } }
                    )
                    .padding(.leading, 46)
                    .padding(.trailing, 4)
                    .padding(.bottom, 4)
                    .transition(.opacity)
                }
            }

            if !archived && expandedItemID != item.id {
                InkBrushDivider(
                    animated: false,
                    seed: InkVariant.seed(for: item.id.uuidString)
                )
                .padding(.leading, 58)
                .opacity(0.34)
            }
        }
        .listRowInsets(
            EdgeInsets(
                top: 2,
                leading: PlanLayout.pageHorizontalPadding,
                bottom: 2,
                trailing: PlanLayout.pageHorizontalPadding
            )
        )
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
        .id("\(archived ? "archived" : "pending")-\(item.id.uuidString)")
        .contextMenu {
            Button("复制到今天") { store.copyPlan(item.id, to: Date()) }
            Button("复制到指定日期") { copyingItem = item }
            Button("保存为模板") { store.saveTemplate(from: item.id) }
            Button("归档") { store.batchPlans([item.id], archived: true) }
            Button("修改") { openEditor(for: item) }
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button {
                deletingItem = item
            } label: {
                Label("删除", systemImage: "trash")
            }
            .tint(Color.planVermilion)

            Button {
                openEditor(for: item)
            } label: {
                Label("修改", systemImage: "pencil")
            }
            .tint(Color.planSecondary)

            if item.status == .planned {
                Button {
                    store.skipCheckIn(id: item.id)
                } label: {
                    Label("跳过", systemImage: "forward.end")
                }
                .tint(Color.planPrimary.opacity(0.7))

                Button {
                    store.postponeCheckIn(id: item.id)
                } label: {
                    Label("顺延", systemImage: "calendar.badge.clock")
                }
                .tint(Color.planSecondary.opacity(0.85))
            }
        }
    }

    private func openEditor(for item: CheckInItem) {
        editorRoute = PlanEditorRoute(
            item: item,
            defaultKind: item.kind,
            defaultDate: item.scheduledStart
        )
    }

    private func presentNewEditor(kind: CheckInKind) {
        editorRoute = PlanEditorRoute(
            item: nil,
            defaultKind: kind,
            defaultDate: Date()
        )
    }

#if DEBUG
    /// Moves a sample plan to the same time a few days back.
    private func moveQAPlan(_ title: String, daysBack: Int) {
        let calendar = Calendar.mojiISO
        guard var item = store.checkInItems.first(where: { $0.title == title }),
              let day = calendar.date(byAdding: .day, value: -daysBack, to: calendar.startOfDay(for: Date())) else { return }
        let time = calendar.dateComponents([.hour, .minute], from: item.scheduledStart)
        item.scheduledStart = calendar.date(bySettingHour: time.hour ?? 0, minute: time.minute ?? 0,
                                            second: 0, of: day) ?? day
        store.saveCheckIn(item)
    }

    private func runEditorQAScenarioIfNeeded() {
        guard !didRunQAScenario else { return }
        // Each screenshot starts with an empty share inbox unless it is about
        // sharing: an item kept by ai-share-off would otherwise open drafts here.
        if let route = ProcessInfo.processInfo.environment["MOJI_QA_SCENARIO"],
           !["ai-share", "ai-share-off"].contains(route) {
            ShareInbox.removeAll()
        }
        if let route = ProcessInfo.processInfo.environment["MOJI_QA_SCENARIO"], route.hasPrefix("workflow-") {
            didRunQAScenario = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                switch route {
                case "workflow-library", "workflow-library-filters", "workflow-templates", "workflow-library-batch": showsLibrary = true
                case "workflow-plan-new": presentNewEditor(kind: .planned)
                case "workflow-calendar-month", "workflow-calendar-week", "workflow-calendar-day", "workflow-calendar-jump": showsCalendar = true
                case "workflow-plan-editor", "workflow-plan-editor-advanced":
                    if let item = store.checkInItems.first(where: { $0.title == "整理 1.6.0 发布说明" }) { openEditor(for: item) }
                case "workflow-plan-expanded", "workflow-plan-expanded-bottom", "workflow-plan-inline": expandedItemID = store.checkInItems.first(where: { $0.title == "整理 1.6.0 发布说明" })?.id
                case "workflow-plan-completed": showsCompleted = true
                case "workflow-plan-copy": copyingItem = store.checkInItems.first(where: { $0.title == "整理 1.6.0 发布说明" })
                default:
                    if route.hasPrefix("workflow-plan-quick-") { expandedItemID = store.checkInItems.first(where: { $0.title == "整理 1.6.0 发布说明" })?.id }
                }
            }
            return
        }
        switch ProcessInfo.processInfo.environment["MOJI_QA_SCENARIO"] {
        case "all-day-editor":
            didRunQAScenario = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) {
                presentNewEditor(kind: .planned)
            }
        case "completed-log-editor":
            didRunQAScenario = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) {
                presentNewEditor(kind: .completedLog)
            }
        case "expanded-plan":
            didRunQAScenario = true
            let start = Calendar.current.date(
                bySettingHour: 14,
                minute: 30,
                second: 0,
                of: Date()
            ) ?? Date()
            let fixture = CheckInItem(
                id: UUID(uuidString: "66666666-6666-6666-6666-666666666666") ?? UUID(),
                title: "整理朋友测试版反馈",
                category: RecordCategory.custom(named: "创作") ?? .work,
                scheduledStart: start,
                plannedMinutes: 45,
                note: "先归纳影响使用的问题，再整理界面方面的建议。\n需要核对备忘录、番茄钟和锁屏实时活动是否符合直觉，最后记录可以下一版再做的想法。",
                scheduleKind: .exactTime,
                detailsConfigured: true,
                repeatRule: .weekly,
                reminderMinutesBefore: 15,
                plannedDurationEnabled: true
            )
            store.saveCheckIn(fixture)
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) {
                expandedItemID = fixture.id
            }
        case "existing-plan-completed-editor", "existing-plan-date-editor":
            didRunQAScenario = true
            let start = Calendar.current.date(
                bySettingHour: 14,
                minute: 30,
                second: 0,
                of: Date()
            ) ?? Date()
            let fixture = CheckInItem(
                title: "中文标题：整理发布说明",
                category: RecordCategory.custom(named: "创作") ?? .study,
                scheduledStart: start,
                plannedMinutes: 45,
                note: "这里是独立保存的详细说明，切换日期或记录方式后不应丢失。",
                scheduleKind: .exactTime,
                detailsConfigured: true,
                plannedDurationEnabled: true
            )
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.65) {
                openEditor(for: fixture)
            }
        case "calendar-month", "calendar-week", "calendar-day", "calendar-record-delete":
            didRunQAScenario = true
        case "ai-entry-suggestion", "ai-entry-split", "ai-entry-applied":
            didRunQAScenario = true
            let scenario = ProcessInfo.processInfo.environment["MOJI_QA_SCENARIO"] ?? ""
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                quickTitle = scenario == "ai-entry-split" ? "买菜、取快递、交电费" : "明天下午三点交保险计划书 45分钟"
                addQuickPlan()
                isQuickEntryFocused = false
                guard scenario == "ai-entry-applied" else { return }
                DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
                    if let entry = entrySuggestions.first { applySuggestion(entry.value, to: entry.key) }
                }
            }
        case "ai-breakdown", "ai-breakdown-carry":
            didRunQAScenario = true
            let carried = ProcessInfo.processInfo.environment["MOJI_QA_SCENARIO"] == "ai-breakdown-carry"
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                if let item = pendingItems.first {
                    breakdownRoute = PlanBreakdownRoute(item: item, replacesOriginal: carried)
                }
            }
        case "ai-import", "ai-import-drafts":
            didRunQAScenario = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { showsImport = true }
        case "ai-brief", "ai-brief-off", "ai-carry-note":
            didRunQAScenario = true
            // The fixture's plan from yesterday, carried for three days instead.
            moveQAPlan("归纳昨日待办", daysBack: 3)
        case "ai-entry-chips":
            didRunQAScenario = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                let text = "周三下午三点交周报 45分钟"
                quickTitle = text
                isQuickEntryFocused = false
                adoptedChipIDs = Set(QuickEntryParser.tokens(
                    in: text, categories: AIAssistant.categories(), defaultCategory: quickEntryCategory
                ).filter { $0.kind == .day || $0.kind == .time }.map(\.id))
            }
        case "ai-intro", "ai-demo", "ai-demo-bottom":
            didRunQAScenario = true
            introStartsWithDemo = ProcessInfo.processInfo.environment["MOJI_QA_SCENARIO"] != "ai-intro"
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { showsAIIntro = true }
        case "ai-wrap", "ai-wrap-edit", "ai-wrap-off", "ai-writes":
            didRunQAScenario = true
            let scenario = AIQAFixture.scenario
            // 归纳昨日待办 carried three days, as for 今日提要; three writes from earlier today.
            moveQAPlan("归纳昨日待办", daysBack: 3)
            writes.seedForQA(plans: store.checkInItems.filter { $0.kind == .planned }, now: AIClock.now())
            if scenario == "ai-writes" {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { showsWrites = true }
            } else if scenario == "ai-wrap-edit" {
                DispatchQueue.main.asyncAfter(deadline: .now() + 2.4) { isEditingWrap = true }
            }
        case "ai-origin":
            didRunQAScenario = true
            let calendar = Calendar.current
            let today = calendar.startOfDay(for: Date())
            for (index, step) in [("列出汇报的三个要点", 15), ("找齐三个月的数据", 30)].enumerated() {
                var item = CheckInItem(
                    title: step.0,
                    category: .work,
                    scheduledStart: calendar.date(bySettingHour: 16 + index, minute: 0, second: 0, of: today) ?? today,
                    plannedMinutes: step.1,
                    scheduleKind: .exactTime,
                    detailsConfigured: true,
                    plannedDurationEnabled: true
                )
                item.aiOrigin = AIWriteText.splitOrigin("准备季度汇报")
                store.saveCheckIn(item)
            }
        case "ai-share", "ai-share-off":
            didRunQAScenario = true
            ShareInbox.removeAll()
            _ = try? ShareInbox.add(text: "周五前交季度报告，周三下午和小王对一下数据，明天上午订会议室", image: nil)
            takeSharedItemIfNeeded()
        case "ai-share-image":
            // Only a picture, as when a screenshot is shared: the drafts can
            // come only from reading it on the device.
            didRunQAScenario = true
            ShareInbox.removeAll()
            _ = try? ShareInbox.add(text: nil, image: Self.qaSharedScreenshot())
            takeSharedItemIfNeeded()
        case "ai-say":
            didRunQAScenario = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { startSaying() }
        case "ai-breakdown-button":
            didRunQAScenario = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                expandedItemID = store.checkInItems.first(where: { $0.title == "整理 1.6.0 发布说明" })?.id
            }
        case "ai-library-filter", "ai-library-filter-applied":
            didRunQAScenario = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { showsLibrary = true }
        default:
            break
        }
    }

    /// ai-share-image: black text on white, the sentence the ai-share item carries.
    private static func qaSharedScreenshot() -> UIImage {
        let lines = ["周五前交季度报告", "周三下午和小王对一下数据", "明天上午订会议室"]
        let size = CGSize(width: 1_080, height: 600)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let attributes: [NSAttributedString.Key: Any] = [
            .font: UIFont.systemFont(ofSize: 60),
            .foregroundColor: UIColor.black
        ]
        return UIGraphicsImageRenderer(size: size, format: format).image { context in
            UIColor.white.setFill()
            context.fill(CGRect(origin: .zero, size: size))
            for (index, line) in lines.enumerated() {
                (line as NSString).draw(at: CGPoint(x: 72, y: 90 + CGFloat(index) * 150), withAttributes: attributes)
            }
        }
    }
#endif

    private func activeStrip(_ active: ActiveSession) -> some View {
        HStack(spacing: 11) {
            InkStatusBlotView(opacity: 0.78)
                .frame(width: 9, height: 9)
            Text(active.title)
                .font(.subheadline.weight(.medium))
                .lineLimit(1)
            Spacer()
            if let targetDate = active.targetDate {
                Text(targetDate, style: .timer)
                    .monospacedDigit()
                    .font(.subheadline.weight(.semibold))
            } else {
                Text(active.startedAt, style: .timer)
                    .monospacedDigit()
                    .font(.subheadline.weight(.semibold))
            }
            Button {
                store.stopSession()
            } label: {
                InkBrushMedallion(
                    symbol: "stop.fill",
                    tint: .planSecondary,
                    size: 30
                )
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("结束计时")
        }
        .padding(.horizontal, 14)
        .frame(height: 48)
        .inkPaperSurface(cornerRadius: 24, showsBrushMark: false)
        .inkReveal(delay: 0.04)
    }

    /// A pomodoro started on the 倒计时 tab is still "正在进行" here. Surfacing
    /// it keeps the two modules telling the same story instead of the checklist
    /// looking idle while a focus runs.
    private var pomodoroStrip: some View {
        Button {
            NotificationCenter.default.post(name: .mojiOpenPomodoro, object: nil)
        } label: {
            HStack(spacing: 11) {
                InkStatusBlotView(opacity: 0.78)
                    .frame(width: 9, height: 9)
                VStack(alignment: .leading, spacing: 1) {
                    Text(pomodoro.title)
                        .font(.subheadline.weight(.medium))
                        .lineLimit(1)
                    Text(pomodoroStatusText)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if let targetDate = pomodoro.targetDate {
                    Text(targetDate, style: .timer)
                        .monospacedDigit()
                        .font(.subheadline.weight(.semibold))
                } else {
                    Text(pomodoro.clockText())
                        .monospacedDigit()
                        .font(.subheadline.weight(.semibold))
                }
                Image(systemName: "chevron.right")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 14)
            .frame(height: 48)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(.primary)
        .inkPaperSurface(cornerRadius: 24, showsBrushMark: false)
        .inkReveal(delay: 0.04)
        .accessibilityLabel("\(pomodoro.title)，\(pomodoroStatusText)，前往番茄钟")
    }

    private var pomodoroStatusText: String {
        if let completed = pomodoro.completedPhase {
            return "\(completed.displayName)完成"
        }
        if pomodoro.isRunning {
            return "\(pomodoro.phase.displayName)中"
        }
        if pomodoro.remainingSeconds() < pomodoro.phaseDurationSeconds {
            return "\(pomodoro.phase.displayName)已暂停"
        }
        return "\(pomodoro.phase.displayName)待开始"
    }

    private var cleanQuickTitle: String {
        quickTitle.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func addQuickPlan() {
        guard !cleanQuickTitle.isEmpty else { return }
        speech.stop()
        let text = cleanQuickTitle
        let today = Calendar.current.startOfDay(for: Date())
        var plan = CheckInItem(
            title: text,
            category: quickEntryCategory,
            scheduledStart: today,
            plannedMinutes: 25,
            scheduleKind: .allDay,
            detailsConfigured: false
        )
        // The chips the user tapped go on in the same save; the rest are ignored.
        let tokens = quickTokens
        let adopted = adoptedChipIDs.intersection(tokens.map(\.id))
        var movedTo: Date?
        if !adopted.isEmpty,
           let chosen = QuickEntryParser.suggestion(text: quickTitle, tokens: tokens, adopted: adopted),
           let updated = chosen.applied(to: plan) {
            plan = updated
            if let day = chosen.day, !Calendar.current.isDate(day, inSameDayAs: today) { movedTo = day }
        }
        withAnimation(.spring(response: 0.42, dampingFraction: 0.82)) {
            store.saveCheckIn(plan)
        }
        quickTitle = ""
        adoptedChipIDs = []
        isQuickEntryFocused = true
        if let movedTo {
            store.workflowNotice = "已加到\(AIDrafting.dayText(movedTo))，可在日历查看"
        }
        // A plan moved off today has no row here to hang a suggestion under.
        if plan.isVisibleInChecklist(on: checklistDate) {
            readQuickEntry(plan, text: text)
        }
    }

    /// The plan is already saved. If the sentence carries a time, a length or
    /// several things, ask what the words say and offer under the row only
    /// what would still change.
    private func readQuickEntry(_ plan: CheckInItem, text: String) {
        guard ai.isReady, ai.settings.readsQuickEntry, AIDrafting.isWorthReading(text) else { return }
        readingEntryIDs.insert(plan.id)
        Task { @MainActor in
            // A failed reading is not worth a message: the plan is there either way.
            let reading = try? await AIAssistant.shared.readEntry(text)
            withAnimation(.easeOut(duration: 0.25)) {
                readingEntryIDs.remove(plan.id)
                guard let current = store.checkInItems.first(where: {
                          $0.id == plan.id && $0.title == plan.title && $0.status == .planned
                      }),
                      let suggestion = reading?.removingMatches(of: current), !suggestion.isEmpty
                else { return }
                entrySuggestions[plan.id] = suggestion
            }
        }
    }

    private func applySuggestion(_ suggestion: EntrySuggestion, to id: UUID) {
        withAnimation(.easeOut(duration: 0.2)) { entrySuggestions[id] = nil }
        guard let plan = store.checkInItems.first(where: { $0.id == id }),
              let updated = suggestion.applied(to: plan) else { return }
        let notice = suggestion.day.map { "已移到\(AIDrafting.dayText($0))，可在日历查看" } ?? "已按建议调整"
        let fields = AIWriteText.changedFields(from: plan, to: updated)
        withAnimation(.spring(response: 0.42, dampingFraction: 0.84)) {
            _ = store.commitDrafts(changing: [updated], notice: notice,
                               summary: fields.isEmpty ? nil : AIWriteText.entryChange(title: plan.title, fields: fields))
        }
    }

    private func splitEntry(_ suggestion: EntrySuggestion, id: UUID) {
        withAnimation(.easeOut(duration: 0.2)) { entrySuggestions[id] = nil }
        guard suggestion.parts.count >= 2,
              let plan = store.checkInItems.first(where: { $0.id == id }), plan.kind == .planned else { return }
        var first = plan
        first.title = suggestion.parts[0]
        let rest = suggestion.parts.dropFirst().map { part in
            var item = CheckInItem(
                title: part,
                category: plan.category,
                scheduledStart: plan.scheduledStart,
                plannedMinutes: plan.plannedMinutes,
                scheduleKind: plan.effectiveScheduleKind,
                detailsConfigured: plan.hasConfiguredDetails,
                plannedDurationEnabled: plan.plannedDurationEnabled
            )
            item.aiOrigin = AIWriteText.splitOrigin(plan.title)
            return item
        }
        withAnimation(.spring(response: 0.42, dampingFraction: 0.84)) {
            _ = store.commitDrafts(adding: Array(rest), changing: [first], notice: "已拆成 \(suggestion.parts.count) 条",
                               summary: AIWriteText.entrySplit(count: suggestion.parts.count, original: plan.title))
        }
    }

    private var quickEntryCategory: RecordCategory {
        RecordCategory(
            rawValue: SharedPersistence.sharedDefaults.string(
                forKey: PlanSettingsKeys.defaultCategory
            ) ?? ""
        ) ?? .study
    }
}

private struct MinimalPlanRow: View {
    @ObservedObject var store: PlanStore
    @ObservedObject var pomodoro: PomodoroEngine
    let item: CheckInItem
    /// The day this checklist is showing, so a carried-over plan can say how
    /// long it has been waiting for that day specifically.
    let checklistDate: Date
    let isExpanded: Bool
    let toggleExpansion: () -> Void
    let openEditor: () -> Void
    let openCopy: () -> Void
    /// Present while the AI entry points are shown (when AI 整理 is not set up,
    /// calling it opens the introduction); the argument says whether the steps
    /// are meant to replace this plan.
    var breakDown: ((Bool) -> Void)? = nil

    @State private var completionInkProgress = 0.0
    @State private var isCommittingCompletion = false
    @State private var completionFeedback = 0
    @State private var quickField: PlanQuickField?
    @State private var confirmsDelete = false
    @AppStorage(PlanSettingsKeys.hapticsEnabled, store: SharedPersistence.sharedDefaults)
    private var hapticsEnabled = true

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                Button {
                    toggleCompletionWithInk()
                } label: {
                    ZStack {
                        Image(systemName: statusSymbol)
                            .font(.title3)
                            .foregroundStyle(
                                item.isCompleted || item.status == .skipped
                                    ? Color.planPrimary
                                    : Color.secondary
                            )
                        InkCompletionBurst(progress: completionInkProgress)
                            .frame(width: 54, height: 54)
                    }
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(isCommittingCompletion)
                .accessibilityLabel(statusAccessibilityLabel)

                Button {
                    if isExpanded { quickField = .content } else { toggleExpansion() }
                } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(item.title)
                            .font(.system(size: 17))
                            .foregroundStyle(item.isCompleted ? Color.secondary : Color.primary)
                            .lineLimit(isExpanded ? nil : 2)
                            .fixedSize(horizontal: false, vertical: true)
                            .overlay {
                                InkBrushStrike(progress: completionInkProgress, tint: .planPrimary,
                                    opacity: item.status == .skipped ? 0.46 : 0.72)
                                    .frame(height: 11).offset(y: 1)
                            }
                        if !isExpanded {
                            metadataLine
                            if !item.note.isEmpty {
                                Text(item.note).font(.system(size: 12)).foregroundStyle(.secondary).lineLimit(1)
                            }
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .layoutPriority(1)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(isExpanded ? "编辑内容：\(item.title)" : item.title)

                carryOverSeal

                Button {
                    toggleExpansion()
                } label: {
                    Image(systemName: "chevron.down")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.tertiary)
                        .rotationEffect(.degrees(isExpanded ? 180 : 0))
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(isExpanded ? "收起计划" : "展开计划")
            }
            .padding(.vertical, 10)

            if isExpanded {
                InkBrushDivider(animated: true)
                expandedDetails
                    .transition(
                        .asymmetric(
                            insertion: .opacity.combined(with: .move(edge: .top)),
                            removal: .opacity
                        )
                    )
            }
        }
        .sensoryFeedback(.success, trigger: completionFeedback)
        .onAppear {
            synchronizeCompletionInk(isFinished)
#if DEBUG
            applyQuickEditorQA()
#endif
        }
        .onChange(of: isFinished) { _, value in synchronizeCompletionInk(value) }
        .onChange(of: isExpanded) { _, _ in
#if DEBUG
            applyQuickEditorQA()
#endif
        }
        .sheet(item: $quickField) { field in
            PlanQuickEditor(store: store, item: item, field: field)
        }
        .alert("删除这个计划？", isPresented: $confirmsDelete) {
            Button("取消", role: .cancel) {}
            Button("删除", role: .destructive) { store.deleteCheckIn(id: item.id) }
        } message: { Text(item.title) }
    }

#if DEBUG
    private func applyQuickEditorQA() {
        guard isExpanded, item.title == "整理 1.6.0 发布说明" else { return }
        let route = ProcessInfo.processInfo.environment["MOJI_QA_SCENARIO"] ?? ""
        if route == "workflow-plan-inline" { quickField = .content }
        else if route.hasPrefix("workflow-plan-quick-"),
                let field = PlanQuickField(rawValue: String(route.dropFirst("workflow-plan-quick-".count)).components(separatedBy: "-").first ?? "") {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { quickField = field }
        }
    }
#endif

    private var expandedDetails: some View {
        VStack(alignment: .leading, spacing: 10) {
            Button { quickField = .content } label: {
                HStack(alignment: .top, spacing: 8) {
                    Text(item.note.isEmpty ? "添加备注" : item.note)
                        .font(.system(size: 15)).lineSpacing(3)
                        .foregroundStyle(item.note.isEmpty ? Color.secondary : Color.primary.opacity(0.86))
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Image(systemName: "pencil").font(.system(size: 12)).foregroundStyle(.tertiary).padding(.top, 3)
                }.frame(minHeight: 32, alignment: .leading).contentShape(Rectangle())
            }.buttonStyle(.plain).accessibilityIdentifier("plan.expanded.details")

            VStack(spacing: 0) {
                detailRow("日期", value: item.scheduledStart.formatted(.dateTime.month().day().weekday(.short)), symbol: "calendar", field: .date)
                Divider().padding(.leading, 38)
                detailRow("安排", value: item.timingSummaryText, symbol: item.effectiveScheduleKind.symbolName, field: .schedule)
                Divider().padding(.leading, 38)
                detailRow("类型", value: item.category.displayName, symbol: item.category.symbolName, field: .category)
            }
            .background(Color.planPrimary.opacity(0.045), in: RoundedRectangle(cornerRadius: 12))

            HStack(spacing: 8) {
                detailPill("重复", value: item.repeatSummaryText, symbol: "repeat", field: .recurrence)
                detailPill("提醒", value: item.reminderSummaryText, symbol: "bell", field: .reminder)
            }
            if item.calendarEventIdentifier != nil {
                Label("已写入系统日历", systemImage: "calendar.badge.checkmark").font(.system(size: 12)).foregroundStyle(.secondary)
            }

            HStack(spacing: 8) {
                let isOpen = item.status == .planned || item.status == .inProgress
                let showsBreakDown = breakDown != nil && isOpen
                Menu {
                    Button("完整编辑", action: openEditor)
                    Button("复制到今天") { store.copyPlan(item.id, to: Date()) }
                    Button("复制到指定日期", action: openCopy)
                    Button("保存为模板") { store.saveTemplate(from: item.id) }
                    Button("归档") { store.batchPlans([item.id], archived: true) }
                        .disabled(item.status == .inProgress)
                    if item.status == .planned {
                        Button("顺延到明天") { store.postponeCheckIn(id: item.id) }
                        Button("跳过") { store.skipCheckIn(id: item.id) }
                    }
                    Button("删除", role: .destructive) { confirmsDelete = true }
                } label: {
                    if showsBreakDown {
                        Image(systemName: "ellipsis")
                            .font(.system(size: 13, weight: .medium))
                            .frame(width: 44, height: 44)
                    } else {
                        Label("更多操作", systemImage: "ellipsis")
                            .font(.system(size: 13, weight: .medium))
                            .frame(maxWidth: .infinity, minHeight: 44)
                    }
                }
                    .background(Color.planPrimary.opacity(0.045), in: RoundedRectangle(cornerRadius: 11))
                    .accessibilityLabel("更多操作")
                if showsBreakDown {
                    expandedAction(title: "拆成步骤", message: "先出草稿再加入", symbol: "list.bullet.indent",
                        emphasized: false, symbolTint: .planVermilion) { breakDown?(false) }
                        .accessibilityIdentifier("plan.expanded.breakdown")
                }
                if isOpen {
                    expandedAction(title: pomodoro.hasActiveSession ? "继续专注" : "去专注",
                        message: pomodoro.hasActiveSession ? "前往当前会话" : "选择时长后开始",
                        symbol: "timer", emphasized: true, action: startPomodoro)
                }
            }
        }
        .padding(.top, 8)
        .padding(.bottom, 12)
    }

    private func detailRow(_ title: String, value: String, symbol: String, field: PlanQuickField) -> some View {
        Button { quickField = field } label: {
            HStack(spacing: 8) {
                Image(systemName: symbol).font(.system(size: 14)).frame(width: 18)
                Text(title).font(.system(size: 13)).foregroundStyle(.secondary).frame(width: 32, alignment: .leading)
                Text(value).font(.system(size: 14, weight: .medium)).fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.right").font(.system(size: 10, weight: .semibold)).foregroundStyle(.tertiary)
            }.padding(.horizontal, 10).padding(.vertical, 8).frame(minHeight: 44).contentShape(Rectangle())
        }.buttonStyle(.plain).accessibilityLabel("修改\(title)：\(value)").accessibilityIdentifier("plan.quick.\(field.rawValue)")
    }

    private func detailPill(_ title: String, value: String, symbol: String, field: PlanQuickField) -> some View {
        Button { quickField = field } label: {
            HStack(spacing: 6) {
                Image(systemName: symbol).font(.system(size: 12))
                Text(value).font(.system(size: 12)).fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.right").font(.system(size: 9)).foregroundStyle(.tertiary)
            }.padding(.horizontal, 10).padding(.vertical, 6).frame(maxWidth: .infinity, minHeight: 36)
                .background(Color.planPrimary.opacity(0.045), in: RoundedRectangle(cornerRadius: 10))
        }.buttonStyle(.plain).accessibilityLabel("修改\(title)：\(value)").accessibilityIdentifier("plan.quick.\(field.rawValue)")
    }

    /// How many days a still-open plan has been carried past its own day.
    /// Finished plans never carry over, so they never wear the seal.
    private var carryOverDaysLate: Int? {
        guard !item.isCompleted, item.status != .skipped else { return nil }
        return CarryOverSeal.daysLate(
            scheduledStart: item.scheduledStart,
            on: checklistDate
        )
    }

    /// A stamp in the margin, carrying the day count and nothing else — the
    /// plan's own words stay exactly as the user wrote them.
    @ViewBuilder
    private var carryOverSeal: some View {
        if let days = carryOverDaysLate {
            let seal = CarryOverDaySeal(daysLate: days, size: 22)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(CarryOverSeal.accessibilityLabel(daysLate: days))
                .accessibilityHidden(false)
            if let breakDown, item.status == .planned {
                // A plan that keeps being carried over is usually too big, on
                // the wrong day, or no longer wanted: the seal offers all three.
                Menu {
                    Button("拆小一点") { breakDown(true) }
                    Button("改期") { quickField = .date }
                    Button("放下") { store.skipCheckIn(id: item.id) }
                } label: {
                    seal
                        .frame(width: 22, height: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel("\(CarryOverSeal.accessibilityLabel(daysLate: days))，处理这个计划")
            } else {
                seal
            }
        }
    }

    private var metadataLine: some View {
        HStack(spacing: 5) {
            Image(systemName: item.effectiveScheduleKind.symbolName)
                .font(.system(size: 9, weight: .semibold))
                .foregroundStyle(Color.planSecondary)

            Text(item.scheduleText)
                .fontWeight(.semibold)
                .foregroundStyle(Color.planPrimary)

            if let minutes = item.plannedDurationMinutes {
                Text("·")
                    .foregroundStyle(Color.planSecondary)
                Text(DurationText.full(minutes: minutes))
                    .foregroundStyle(Color.planSecondary)
            }

            Text("·")
                .foregroundStyle(Color.planSecondary)
            Text(item.category.displayName)
                .foregroundStyle(Color.planSecondary)

            if let origin = item.aiOrigin, !origin.isEmpty {
                Text("·")
                    .foregroundStyle(Color.planSecondary)
                Text(AIWriteText.mark(origin))
                    .foregroundStyle(Color.planVermilion)
                    .layoutPriority(-1)
                    .accessibilityIdentifier("plan.origin")
            }
        }
        .font(.caption2)
        .foregroundStyle(.secondary)
        .lineLimit(1)
        .minimumScaleFactor(0.78)
    }

    private func expandedAction(
        title: String,
        message: String,
        symbol: String,
        emphasized: Bool,
        symbolTint: Color? = nil,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: symbol)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(symbolTint ?? (emphasized ? Color.planBackground : Color.planPrimary))
                VStack(alignment: .leading, spacing: 1) {
                    Text(title)
                        .font(.caption.weight(.semibold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                    Text(message)
                        .font(.caption2)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .opacity(0.72)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 11)
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .foregroundStyle(emphasized ? Color.planBackground : Color.planPrimary)
            .background(
                emphasized ? Color.planPrimary : Color.planPrimary.opacity(0.065),
                in: RoundedRectangle(cornerRadius: 12, style: .continuous)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func startPomodoro() {
        guard !pomodoro.hasActiveSession else {
            NotificationCenter.default.post(name: .mojiOpenPomodoro, object: nil)
            return
        }
        pomodoro.link(to: item)
        NotificationCenter.default.post(name: .mojiOpenPomodoro, object: nil)
    }

    private func toggleCompletionWithInk() {
        let shouldAnimate = !isFinished
        guard shouldAnimate else {
            synchronizeCompletionInk(false)
            isCommittingCompletion = false
            store.toggleCheckIn(id: item.id)
            return
        }
        guard !isCommittingCompletion else { return }

        isCommittingCompletion = true
        completionInkProgress = 0
        withAnimation(.easeOut(duration: 0.56)) {
            completionInkProgress = 1
        }
        if hapticsEnabled {
            completionFeedback += 1
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.58) {
            withAnimation(.easeInOut(duration: 0.24)) {
                store.toggleCheckIn(id: item.id)
            }
            isCommittingCompletion = false
        }
    }

    private var isFinished: Bool {
        item.isCompleted || item.status == .skipped
    }

    private func synchronizeCompletionInk(_ isComplete: Bool) {
        var transaction = Transaction(animation: nil)
        transaction.disablesAnimations = true
        withTransaction(transaction) {
            completionInkProgress = isComplete ? 1 : 0
        }
    }

    private var statusSymbol: String {
        if item.isCompleted { return "checkmark.circle.fill" }
        if item.status == .skipped { return "forward.end.circle.fill" }
        return "circle"
    }

    private var statusAccessibilityLabel: String {
        if item.isCompleted { return "取消完成" }
        if item.status == .skipped { return "恢复计划" }
        return "标记完成"
    }
}

/// The first thing a fresh install shows, so restoring is never something the
/// user has to go looking for.
///
/// The folder route is the one that matters: it brings the data back *and*
/// re-points automatic backup at the same folder, so a restored install keeps
/// the backup habit of the one it replaced.
private struct RestoreWelcomeSheet: View {
    @ObservedObject var store: PlanStore
    let onMessage: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @AppStorage("minuteplan.onboarding.didOfferRestore", store: SharedPersistence.sharedDefaults)
    private var didOfferRestore = false
    @State private var isPickingFolder = false
    @State private var isPickingFile = false

    private var expectedFolderName: String? {
        ExternalBackupService.shared.expectedFolderName
    }

    var body: some View {
        NavigationStack {
            ZStack {
                InkWashBackground()

                VStack(alignment: .leading, spacing: 18) {
                    VStack(alignment: .leading, spacing: 10) {
                        HStack(spacing: 10) {
                            InkSealMark(character: "复", size: 30, style: .round, seed: 11)
                            Text("先把旧数据接回来")
                                .font(.title3.weight(.semibold))
                        }
                        Text(BackupRestoreCopy.folderHint)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                        if let expectedFolderName {
                            Label("上次备份在「\(expectedFolderName)」", systemImage: "folder")
                                .font(.caption)
                                .foregroundStyle(Color.planVermilion)
                        }
                    }

                    VStack(spacing: 10) {
                        Button {
                            isPickingFolder = true
                        } label: {
                            Label("选择备份文件夹恢复", systemImage: "folder.badge.plus")
                                .font(.subheadline.weight(.semibold))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 13)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(Color.planVermilion)

                        Button {
                            isPickingFile = true
                        } label: {
                            Label("选择单个备份文件", systemImage: "doc.badge.arrow.up")
                                .font(.subheadline)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 11)
                        }
                        .buttonStyle(.bordered)
                        .tint(Color.planPrimary)
                    }

                    Text("从空白开始也可以，之后在「回顾 → 数据备份」里随时恢复。")
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    Spacer(minLength: 0)
                }
                .padding(22)
            }
            .navigationTitle("欢迎回到 Moji")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("从空白开始") { finish() }
                }
            }
            .fileImporter(
                isPresented: $isPickingFolder,
                allowedContentTypes: [.folder]
            ) { result in
                switch result {
                case let .success(url):
                    restoreFromFolder(at: url)
                case let .failure(error):
                    onMessage(error.localizedDescription)
                }
            }
            .fileImporter(
                isPresented: $isPickingFile,
                allowedContentTypes: MojiBackupFile.readableContentTypes
            ) { result in
                switch result {
                case let .success(url):
                    restoreFromFile(at: url)
                case let .failure(error):
                    onMessage(error.localizedDescription)
                }
            }
        }
    }

    private func restoreFromFolder(at url: URL) {
        do {
            try BackupRestoreCoordinator.restore(fromFolderAt: url, into: store)
            onMessage("已恢复 \(store.checkInItems.count) 条计划、\(store.countdowns.count) 个日期，并继续备份到同一个文件夹。")
            finish()
        } catch {
            onMessage("无法从这个文件夹恢复：\(error.localizedDescription)")
        }
    }

    private func restoreFromFile(at url: URL) {
        do {
            let didAdoptFolder = try BackupRestoreCoordinator.restore(
                fromFileAt: url,
                into: store
            )
            onMessage(
                didAdoptFolder
                    ? "已恢复数据，并继续备份到同一个文件夹。"
                    : BackupRestoreCopy.folderStillNeeded
            )
            finish()
        } catch {
            onMessage("这份文件无法恢复：\(error.localizedDescription)")
        }
    }

    private func finish() {
        didOfferRestore = true
        dismiss()
    }
}
