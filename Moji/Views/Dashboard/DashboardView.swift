import SwiftUI

private struct PlanEditorRoute: Identifiable {
    let id = UUID()
    let item: CheckInItem?
    let defaultKind: CheckInKind
    let defaultDate: Date
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

    private var pendingItems: [CheckInItem] {
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
                                Text("今日已归档 \(completedItems.count)")
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
            }
            .navigationTitle("计划")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        showsCalendar = true
                    } label: {
                        Image(systemName: "calendar")
                    }
                    .accessibilityLabel("打开计划日历")
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
            }
            .onReceive(
                NotificationCenter.default.publisher(for: .mojiOpenPlanEditor)
            ) { notification in
                openPlanEditor(for: notification.object as? UUID)
            }
            .onChange(of: scenePhase) { _, newValue in
                guard newValue == .active else { return }
                checklistDate = Date()
                store.reload()
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
        HStack(spacing: 10) {
            IMESafeTextField(
                placeholder: "写下一件要做的事",
                text: $quickTitle,
                isFocused: $isQuickEntryFocused,
                keepsFocusOnSubmit: true,
                accessibilityIdentifier: "plan.quickTitle",
                onSubmit: addQuickPlan
            )

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
        .padding(.leading, 16)
        .padding(.trailing, 8)
        .frame(height: 52)
        .inkPaperSurface(
            cornerRadius: 25,
            showsBrushMark: false,
            borderColor: Color.planPrimary.opacity(isQuickEntryFocused ? 0.30 : 0.095),
            borderWidth: isQuickEntryFocused ? 1.1 : 0.75
        )
        .animation(.easeOut(duration: 0.22), value: isQuickEntryFocused)
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

            let completedCount = completedItemsForChecklistDate.count
            let totalCount = pendingItems.count + completedCount
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
            openEditor: {
                openEditor(for: item)
            }
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
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button {
                if expandedItemID == item.id {
                    expandedItemID = nil
                }
                store.deleteCheckIn(id: item.id)
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
    private func runEditorQAScenarioIfNeeded() {
        guard !didRunQAScenario else { return }
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
        default:
            break
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
        let today = Calendar.current.startOfDay(for: Date())
        withAnimation(.spring(response: 0.42, dampingFraction: 0.82)) {
            store.saveCheckIn(
                CheckInItem(
                    title: cleanQuickTitle,
                    category: quickEntryCategory,
                    scheduledStart: today,
                    plannedMinutes: 25,
                    scheduleKind: .allDay,
                    detailsConfigured: false
                )
            )
        }
        quickTitle = ""
        isQuickEntryFocused = true
    }

    private var quickEntryCategory: RecordCategory {
        RecordCategory(
            rawValue: SharedPersistence.sharedDefaults.string(
                forKey: PlanSettingsKeys.defaultCategory
            ) ?? ""
        ) ?? .study
    }
}

private struct PlanDetailDatum {
    let title: String
    let value: String
    let symbol: String
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

    @State private var completionInkProgress = 0.0
    @State private var isCommittingCompletion = false
    @State private var completionFeedback = 0
    @State private var isInlineEditing = false
    @State private var quickEditTitle = ""
    @State private var quickEditNote = ""
    @State private var isQuickEditTitleFocused = false
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

                Group {
                    if isExpanded {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(item.title)
                                .font(.body)
                                .foregroundStyle(
                                    item.isCompleted ? Color.secondary : Color.primary
                                )
                                .lineLimit(2)
                                .overlay {
                                    InkBrushStrike(
                                        progress: completionInkProgress,
                                        tint: .planPrimary,
                                        opacity: item.status == .skipped ? 0.46 : 0.72
                                    )
                                    .frame(height: 11)
                                    .offset(y: 1)
                                }
                            metadataLine
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .layoutPriority(1)
                        .textSelection(.enabled)
                    } else {
                        Button(action: toggleExpansion) {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(item.title)
                                    .font(.body)
                                    .foregroundStyle(
                                        item.isCompleted ? Color.secondary : Color.primary
                                    )
                                    .lineLimit(1)
                                    .overlay {
                                        InkBrushStrike(
                                            progress: completionInkProgress,
                                            tint: .planPrimary,
                                            opacity: item.status == .skipped ? 0.46 : 0.72
                                        )
                                        .frame(height: 11)
                                        .offset(y: 1)
                                    }

                                metadataLine

                                if !item.note.isEmpty {
                                    Text(item.note)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                        .lineLimit(2, reservesSpace: true)
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .layoutPriority(1)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }

                carryOverSeal

                if item.calendarEventIdentifier != nil {
                    Image(systemName: "calendar.badge.checkmark")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Button {
                    cancelInlineEditing()
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
            .padding(.vertical, 14)

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
        }
        .onChange(of: isFinished) { _, newValue in
            synchronizeCompletionInk(newValue)
        }
        .onChange(of: isExpanded) { _, expanded in
            if !expanded {
                cancelInlineEditing()
            }
        }
    }

    private var expandedDetails: some View {
        VStack(alignment: .leading, spacing: 13) {
            if isInlineEditing {
                inlineEditor
            } else if !item.note.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    HStack(spacing: 7) {
                        InkSealMark(
                            character: "注",
                            size: 19,
                            style: .round,
                            seed: InkVariant.seed(for: item.id.uuidString)
                        )
                        Text("详细说明")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.secondary)
                    }

                    Text(item.note)
                        .font(.subheadline)
                        .foregroundStyle(.primary.opacity(0.86))
                        .lineSpacing(4)
                        .fixedSize(horizontal: false, vertical: true)
                        .textSelection(.enabled)
                        .accessibilityIdentifier("plan.expanded.details")
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            VStack(spacing: 8) {
                ForEach(Array(stride(from: 0, to: detailData.count, by: 2)), id: \.self) {
                    index in
                    HStack(alignment: .top, spacing: 8) {
                        detailTile(detailData[index])
                        if detailData.indices.contains(index + 1) {
                            detailTile(detailData[index + 1])
                        }
                    }
                }
            }
            .textSelection(.enabled)

            if !isInlineEditing {
                HStack(spacing: 9) {
                    expandedTextAction(
                        title: "快捷编辑",
                        symbol: "pencil.line",
                        action: beginInlineEditing
                    )

                    expandedTextAction(
                        title: "更多设置",
                        symbol: "slider.horizontal.3",
                        action: openEditor
                    )
                }
            }

            if item.status == .planned {
                expandedAction(
                    title: "开始番茄钟",
                    message: pomodoro.hasActiveSession ? "前往当前会话" : "带入专注",
                    symbol: "timer",
                    emphasized: true,
                    action: startPomodoro
                )
            }
        }
        .padding(.top, 12)
        .padding(.bottom, 10)
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
            CarryOverDaySeal(daysLate: days, size: 26)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(CarryOverSeal.accessibilityLabel(daysLate: days))
                .accessibilityHidden(false)
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
        }
        .font(.caption2)
        .foregroundStyle(.secondary)
        .lineLimit(1)
        .minimumScaleFactor(0.78)
    }

    private var inlineEditor: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 7) {
                InkSealMark(
                    character: "改",
                    size: 19,
                    style: .round,
                    seed: InkVariant.seed(for: item.id.uuidString)
                )
                Text("快捷编辑")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
            }

            IMESafeTextField(
                placeholder: "计划标题",
                text: $quickEditTitle,
                isFocused: $isQuickEditTitleFocused,
                accessibilityIdentifier: "plan.expanded.quickEdit.title",
                textStyle: .body
            )
            .frame(minHeight: 42)

            ZStack(alignment: .topLeading) {
                if quickEditNote.isEmpty {
                    Text("添加详细说明（可选）")
                        .font(.subheadline)
                        .foregroundStyle(.tertiary)
                        .padding(.horizontal, 5)
                        .padding(.vertical, 8)
                        .allowsHitTesting(false)
                }
                TextEditor(text: $quickEditNote)
                    .font(.subheadline)
                    .lineSpacing(4)
                    .scrollContentBackground(.hidden)
                    .padding(.horizontal, -1)
                    .accessibilityIdentifier("plan.expanded.quickEdit.note")
            }
            .frame(minHeight: 88, maxHeight: 126)
            .padding(.horizontal, 6)
            .background(
                Color.planPrimary.opacity(0.045),
                in: RoundedRectangle(cornerRadius: 10, style: .continuous)
            )

            HStack(spacing: 9) {
                Button("取消") {
                    cancelInlineEditing()
                }
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(
                    Color.planPrimary.opacity(0.06),
                    in: RoundedRectangle(cornerRadius: 11, style: .continuous)
                )
                .buttonStyle(.plain)

                Button("保存") {
                    IMETextInput.commit(saveInlineEditing)
                }
                .fontWeight(.semibold)
                .foregroundStyle(Color.planBackground)
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(
                    Color.planPrimary,
                    in: RoundedRectangle(cornerRadius: 11, style: .continuous)
                )
                .buttonStyle(.plain)
                .disabled(!canSaveInlineEdit)
                .opacity(canSaveInlineEdit ? 1 : 0.42)
                .accessibilityIdentifier("plan.expanded.quickEdit.save")
            }
        }
        .accessibilityIdentifier("plan.expanded.quickEdit")
    }

    private var detailData: [PlanDetailDatum] {
        var result = [
            PlanDetailDatum(
                title: "日期",
                value: item.scheduledStart.formatted(
                    .dateTime.month().day().weekday(.short)
                ),
                symbol: "calendar"
            ),
            PlanDetailDatum(
                title: "安排",
                value: item.timingSummaryText,
                symbol: item.effectiveScheduleKind.symbolName
            ),
            PlanDetailDatum(
                title: "类型",
                value: item.category.displayName,
                symbol: item.category.symbolName
            )
        ]
        if item.effectiveRepeatRule != .never {
            result.append(
                PlanDetailDatum(
                    title: "重复",
                    value: item.repeatSummaryText,
                    symbol: "repeat"
                )
            )
        }
        if item.reminderMinutesBefore != nil {
            result.append(
                PlanDetailDatum(
                    title: "提醒",
                    value: item.reminderSummaryText,
                    symbol: "bell"
                )
            )
        }
        if item.calendarEventIdentifier != nil {
            result.append(
                PlanDetailDatum(
                    title: "Apple 日历",
                    value: "已同步",
                    symbol: "calendar.badge.checkmark"
                )
            )
        }
        return result
    }

    private func detailTile(_ data: PlanDetailDatum) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: data.symbol)
                .font(.caption.weight(.semibold))
                .foregroundStyle(Color.planPrimary.opacity(0.78))
                .frame(width: 18, height: 18)
            VStack(alignment: .leading, spacing: 2) {
                Text(data.title)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Text(data.value)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.primary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 9)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            Color.planPrimary.opacity(0.045),
            in: RoundedRectangle(cornerRadius: 10, style: .continuous)
        )
    }

    private func expandedAction(
        title: String,
        message: String,
        symbol: String,
        emphasized: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: symbol)
                    .font(.subheadline.weight(.semibold))
                VStack(alignment: .leading, spacing: 1) {
                    Text(title)
                        .font(.caption.weight(.semibold))
                        .lineLimit(1)
                    Text(message)
                        .font(.caption2)
                        .lineLimit(1)
                        .opacity(0.72)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 11)
            .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
            .foregroundStyle(emphasized ? Color.planBackground : Color.planPrimary)
            .background(
                emphasized ? Color.planPrimary : Color.planPrimary.opacity(0.065),
                in: RoundedRectangle(cornerRadius: 12, style: .continuous)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func expandedTextAction(
        title: String,
        symbol: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Label(title, systemImage: symbol)
                .font(.caption.weight(.semibold))
                .foregroundStyle(Color.planSecondary)
                .frame(maxWidth: .infinity, minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var canSaveInlineEdit: Bool {
        !quickEditTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private func beginInlineEditing() {
        quickEditTitle = item.title
        quickEditNote = item.note
        isInlineEditing = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.08) {
            isQuickEditTitleFocused = true
        }
    }

    private func cancelInlineEditing() {
        isQuickEditTitleFocused = false
        isInlineEditing = false
        quickEditTitle = ""
        quickEditNote = ""
    }

    private func saveInlineEditing() {
        let cleanTitle = quickEditTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanTitle.isEmpty else { return }

        var updated = item
        updated.title = cleanTitle
        updated.note = quickEditNote.trimmingCharacters(in: .whitespacesAndNewlines)
        updated.detailsConfigured = true
        store.saveCheckIn(updated)
        cancelInlineEditing()
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
