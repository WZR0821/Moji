import SwiftUI

struct CheckInEditorView: View {
    @ObservedObject var store: PlanStore
    let item: CheckInItem?

    @Environment(\.dismiss) private var dismiss
    @State private var isTitleFocused = false

    @State private var kind: CheckInKind
    @State private var title: String
    @State private var category: RecordCategory
    @State private var scheduleKind: ScheduleKind
    @State private var scheduledStart: Date
    @State private var plannedMinutes: Int
    @State private var plannedDurationEnabled: Bool
    @State private var actualStart: Date
    @State private var actualEnd: Date
    @State private var note: String
    @State private var calendarSyncEnabled: Bool
    @State private var calendarEventIdentifier: String?
    @State private var repeatRule: PlanRepeatRule
    @State private var selectedWeekdays: Set<Int>
    @State private var reminderMinutesBefore: Int
    @State private var isAdvancedSettingsExpanded: Bool
    @State private var isSaving = false
    @State private var calendarError: String?

    init(
        store: PlanStore,
        item: CheckInItem? = nil,
        defaultKind: CheckInKind = .planned,
        defaultDate: Date = Date()
    ) {
        self.store = store
        self.item = item

        let calendar = Calendar.current
        let now = Date().roundedDownToMinute()
        let defaultTime = calendar.dateComponents([.hour, .minute], from: now)
        var components = calendar.dateComponents([.year, .month, .day], from: defaultDate)
        components.hour = defaultTime.hour
        components.minute = defaultTime.minute
        let start = calendar.date(from: components)?.roundedDownToMinute() ?? now

        let defaults = SharedPersistence.sharedDefaults
        let defaultCategory = RecordCategory(
            rawValue: defaults.string(forKey: PlanSettingsKeys.defaultCategory) ?? ""
        ) ?? .study
        let defaultScheduleKind = ScheduleKind(
            rawValue: defaults.string(forKey: PlanSettingsKeys.defaultScheduleKind) ?? ""
        ) ?? .allDay
        let defaultPlannedMinutes = PlanSettingsKeys.integer(
            PlanSettingsKeys.defaultPlannedMinutes,
            fallback: 25,
            defaults: defaults
        )
        let defaultDurationEnabled = PlanSettingsKeys.bool(
            PlanSettingsKeys.defaultPlannedDurationEnabled,
            fallback: false,
            defaults: defaults
        )

        _kind = State(initialValue: item?.kind ?? defaultKind)
        _title = State(initialValue: item?.title ?? "")
        _category = State(initialValue: item?.category ?? defaultCategory)
        _scheduleKind = State(initialValue: item?.effectiveScheduleKind ?? defaultScheduleKind)
        _scheduledStart = State(initialValue: item?.scheduledStart ?? start)
        _plannedMinutes = State(initialValue: item?.plannedMinutes ?? max(1, defaultPlannedMinutes))
        _plannedDurationEnabled = State(
            initialValue: item?.hasPlannedDuration ?? defaultDurationEnabled
        )
        _actualStart = State(
            initialValue: item?.actualStartDate
                ?? start.addingTimeInterval(TimeInterval(-max(1, defaultPlannedMinutes) * 60))
        )
        _actualEnd = State(initialValue: item?.actualEndDate ?? start)
        _note = State(initialValue: item?.note ?? "")
        _calendarSyncEnabled = State(initialValue: item?.calendarSyncEnabled ?? false)
        _calendarEventIdentifier = State(initialValue: item?.calendarEventIdentifier)
        _repeatRule = State(initialValue: item?.effectiveRepeatRule ?? .never)
        let initialWeekdays = item?.effectiveRepeatWeekdays ?? []
        let scheduledWeekday = calendar.component(.weekday, from: item?.scheduledStart ?? start)
        _selectedWeekdays = State(
            initialValue: Set(initialWeekdays.isEmpty ? [scheduledWeekday] : initialWeekdays)
        )
        _reminderMinutesBefore = State(initialValue: item?.reminderMinutesBefore ?? -1)
        _isAdvancedSettingsExpanded = State(
            initialValue: item?.hasPlannedDuration == true
                || item?.effectiveRepeatRule != .never
                || item?.reminderMinutesBefore != nil
                || item?.calendarSyncEnabled == true
        )
    }

    private var cleanTitle: String {
        title.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var actualMinutes: Int {
        guard actualEnd > actualStart else { return 0 }
        return Int(ceil(actualEnd.timeIntervalSince(actualStart) / 60))
    }

    private var canSave: Bool {
        !cleanTitle.isEmpty
            && (
                kind == .planned
                    || scheduleKind != .exactTime
                    || actualMinutes > 0
            )
    }

    var body: some View {
        NavigationStack {
            ZStack {
                InkWashBackground()

                Form {
                    Section("记录方式") {
                        Picker("记录方式", selection: $kind) {
                            ForEach(CheckInKind.allCases) { option in
                                Text(option.displayName).tag(option)
                            }
                        }
                        .pickerStyle(.segmented)
                    }

                    Section("标题") {
                        IMESafeTextField(
                            placeholder: kind == .planned
                                ? "例如：完成报告第一章"
                                : "例如：整理会议纪要",
                            text: $title,
                            isFocused: $isTitleFocused,
                            accessibilityIdentifier: "plan.editor.title"
                        )
                    }

                    Section {
                        TextField(
                            "补充背景、步骤或注意事项",
                            text: $note,
                            axis: .vertical
                        )
                            .lineLimit(5...12)
                            .frame(minHeight: 116, alignment: .topLeading)
                            .accessibilityIdentifier("plan.editor.details")
                    } header: {
                        Text("详细说明（可选）")
                    } footer: {
                        Text("保存后，展开计划即可直接阅读完整内容。")
                    }

                    Section("类型") {
                        PlanCategoryPicker(selection: $category)
                    }

                    if kind == .planned {
                        plannedTimeSection
                    } else {
                        completedTimeSection
                    }

                    advancedSettingsSection

                }
                .inkFormStyle()
            }
            .navigationTitle(
                item == nil
                    ? kind.displayName
                    : (kind == .planned ? "编辑计划" : "记录已做")
            )
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                        .disabled(isSaving)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(isSaving ? "保存中…" : "保存") {
                        IMETextInput.commit {
                            Task { await save() }
                        }
                    }
                    .fontWeight(.semibold)
                    .disabled((!canSave && !isTitleFocused) || isSaving)
                }
            }
            .onAppear {
                if item == nil { isTitleFocused = true }
#if DEBUG
                runEditorQAScenarioIfNeeded()
#endif
            }
            .onChange(of: kind) { previousKind, newKind in
                guard previousKind != newKind, newKind == .completedLog else { return }
                if scheduleKind == .exactTime {
                    let end = Date().roundedDownToMinute()
                    let minutes = item?.plannedDurationMinutes ?? max(1, plannedMinutes)
                    actualEnd = end
                    actualStart = end.addingTimeInterval(TimeInterval(-minutes * 60))
                } else {
                    actualStart = scheduledStart
                    actualEnd = scheduledStart
                }
            }
            .onChange(of: scheduleKind) { _, newKind in
                guard kind == .completedLog else { return }
                if newKind == .exactTime, actualEnd <= actualStart {
                    actualEnd = actualStart.addingTimeInterval(
                        TimeInterval(max(1, plannedMinutes) * 60)
                    )
                }
            }
            .onChange(of: calendarSyncEnabled) { _, enabled in
                guard enabled, calendarEventIdentifier == nil else { return }
                Task {
                    do {
                        let granted = try await CalendarSyncService.shared.requestWriteAccessIfNeeded()
                        if !granted {
                            calendarSyncEnabled = false
                            calendarError = CalendarSyncError.accessDenied.localizedDescription
                        }
                    } catch {
                        calendarSyncEnabled = false
                        calendarError = error.localizedDescription
                    }
                }
            }
            .alert(
                "无法保存设置",
                isPresented: Binding(
                    get: { calendarError != nil },
                    set: { if !$0 { calendarError = nil } }
                )
            ) {
                Button("知道了", role: .cancel) {}
            } message: {
                Text(calendarError ?? "")
            }
        }
    }

    private var plannedTimeSection: some View {
        Section {
            Picker("安排方式", selection: $scheduleKind) {
                ForEach(ScheduleKind.allCases) { option in
                    Label(option.displayName, systemImage: option.symbolName).tag(option)
                }
            }

            if scheduleKind == .exactTime {
                DatePicker(
                    "开始",
                    selection: $scheduledStart,
                    displayedComponents: [.date, .hourAndMinute]
                )
            } else {
                DatePicker(
                    "日期",
                    selection: $scheduledStart,
                    displayedComponents: [.date]
                )
            }

        } header: {
            Text("安排到")
        } footer: {
            Text(scheduleHelpText)
        }
    }

    @ViewBuilder
    private var advancedSettingsSection: some View {
        Section {
            DisclosureGroup(isExpanded: $isAdvancedSettingsExpanded) {
                VStack(alignment: .leading, spacing: 16) {
                    if kind == .planned {
                        plannedEffortControls
                        Divider().opacity(0.45)
                        repeatAndReminderControls
                        Divider().opacity(0.45)
                    }

                    calendarSyncControls
                }
                .padding(.vertical, 4)
            } label: {
                VStack(alignment: .leading, spacing: 3) {
                    Text("高级设置")
                        .font(.body.weight(.medium))
                    Text(
                        advancedSettingsSummary
                    )
                    .font(.caption)
                    .foregroundStyle(
                        advancedSettingsSummary == "未设置"
                            ? Color.planSecondary
                            : Color.planPrimary
                    )
                }
            }
        }
    }

    private var advancedSettingsSummary: String {
        var values: [String] = []

        if kind == .planned, plannedDurationEnabled {
            values.append("预计 \(DurationText.full(minutes: plannedMinutes))")
        }

        if kind == .planned, repeatRule != .never {
            values.append(repeatRule.displayName)
        }

        if kind == .planned, reminderMinutesBefore >= 0 {
            values.append(reminderSummaryText)
        }

        if calendarSyncEnabled {
            values.append("已同步日历")
        }

        return values.isEmpty ? "未设置" : values.joined(separator: " · ")
    }

    private var reminderSummaryText: String {
        switch reminderMinutesBefore {
        case 0: return "开始时提醒"
        case 1..<60: return "提前 \(reminderMinutesBefore) 分钟提醒"
        case 60: return "提前 1 小时提醒"
        case 1_440: return "提前 1 天提醒"
        default:
            if reminderMinutesBefore.isMultiple(of: 60) {
                return "提前 \(reminderMinutesBefore / 60) 小时提醒"
            }
            return "提前 \(reminderMinutesBefore) 分钟提醒"
        }
    }

    private var plannedEffortControls: some View {
        VStack(alignment: .leading, spacing: 9) {
            Text("投入估算（可选）")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)

            Toggle("添加预计投入", isOn: $plannedDurationEnabled)

            if plannedDurationEnabled {
                Stepper(value: $plannedMinutes, in: 1...1_440) {
                    HStack {
                        Text("预计投入")
                        Spacer()
                        Text(DurationText.full(minutes: plannedMinutes))
                            .fontWeight(.semibold)
                    }
                }

                durationButtons {
                    plannedMinutes = $0
                }
            }

            Text("预计投入用于番茄钟和周总结，与全天或具体时间相互独立。")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private var completedTimeSection: some View {
        Section {
            Picker("记录时段", selection: $scheduleKind) {
                ForEach(ScheduleKind.allCases) { option in
                    Label(option.displayName, systemImage: option.symbolName).tag(option)
                }
            }

            if scheduleKind == .exactTime {
                DatePicker(
                    "开始",
                    selection: $actualStart,
                    displayedComponents: [.date, .hourAndMinute]
                )
                DatePicker(
                    "结束",
                    selection: $actualEnd,
                    displayedComponents: [.date, .hourAndMinute]
                )

                durationButtons {
                    actualEnd = actualStart.addingTimeInterval(TimeInterval($0 * 60))
                }

                HStack {
                    Text("实际用时")
                    Spacer()
                    Text(DurationText.full(minutes: actualMinutes))
                        .fontWeight(.semibold)
                        .foregroundStyle(actualMinutes > 0 ? category.color : Color.secondary)
                }
            } else {
                DatePicker(
                    "日期",
                    selection: $actualStart,
                    displayedComponents: [.date]
                )

                Label(
                    "\(scheduleKind.displayName)记录不虚构开始时间或时长",
                    systemImage: scheduleKind.symbolName
                )
                .font(.caption)
                .foregroundStyle(.secondary)
            }
        } header: {
            Text("实际发生在")
        } footer: {
            Text(
                scheduleKind == .exactTime
                    ? "需要统计实际投入时，可精确记录到分钟。"
                    : "全天与时段记录会计入完成项，但不会被换算成虚假的分钟数。"
            )
        }
    }

    private var repeatAndReminderControls: some View {
        VStack(alignment: .leading, spacing: 9) {
            Text("重复与提醒")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)

            Picker("重复", selection: $repeatRule) {
                ForEach(PlanRepeatRule.allCases) { rule in
                    Text(rule.displayName).tag(rule)
                }
            }

            if repeatRule == .customWeekdays {
                HStack(spacing: 6) {
                    ForEach(weekdayOptions, id: \.value) { option in
                        Button {
                            if selectedWeekdays.contains(option.value) {
                                if selectedWeekdays.count > 1 {
                                    selectedWeekdays.remove(option.value)
                                }
                            } else {
                                selectedWeekdays.insert(option.value)
                            }
                        } label: {
                            Text(option.label)
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(
                                    selectedWeekdays.contains(option.value)
                                        ? Color.planBackground
                                        : Color.secondary
                                )
                                .frame(maxWidth: .infinity)
                                .frame(height: 32)
                                .background(
                                    selectedWeekdays.contains(option.value)
                                        ? Color.planPrimary
                                        : Color.planPrimary.opacity(0.06),
                                    in: Circle()
                                )
                        }
                        .buttonStyle(.plain)
                    }
                }
            }

            Picker("提醒", selection: $reminderMinutesBefore) {
                ForEach(reminderOptions, id: \.value) { option in
                    Text(option.label).tag(option.value)
                }
            }

            Text("重复计划会在完成或跳过后生成下一次；提醒只保存在本机。")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private var calendarSyncControls: some View {
        VStack(alignment: .leading, spacing: 9) {
            Toggle("添加到 Apple 日历", isOn: $calendarSyncEnabled)
                .disabled(calendarEventIdentifier != nil)

            if calendarEventIdentifier != nil {
                Label("已添加到 Apple 日历", systemImage: "calendar.badge.checkmark")
                    .font(.caption)
                    .foregroundStyle(Color.planSecondary)
            }

            Text("默认不上传。开启后仅单向添加一次；之后如需修改或删除日历事件，请在 Apple 日历中操作。")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private func durationButtons(action: @escaping (Int) -> Void) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach([15, 25, 45, 60, 90], id: \.self) { minutes in
                    Button("\(minutes) 分") {
                        action(minutes)
                    }
                    .buttonStyle(.bordered)
                    .buttonBorderShape(.capsule)
                }
            }
        }
    }

    @MainActor
    private func save() async {
        guard canSave else { return }
        isSaving = true

        let completedHasPreciseTime = scheduleKind == .exactTime
        let normalizedActualStart = completedHasPreciseTime
            ? actualStart.roundedDownToMinute()
            : normalizedCompletedStart()
        let normalizedActualEnd = completedHasPreciseTime
            ? max(
                actualEnd.roundedDownToMinute(),
                normalizedActualStart.addingTimeInterval(60)
            )
            : normalizedActualStart
        let normalizedScheduledStart = kind == .planned
            ? normalizedPlanStart()
            : normalizedActualStart

        var updatedItem = CheckInItem(
            id: item?.id ?? UUID(),
            title: cleanTitle,
            category: category,
            kind: kind,
            scheduledStart: normalizedScheduledStart,
            plannedMinutes: kind == .planned
                ? plannedMinutes
                : max(1, completedHasPreciseTime ? actualMinutes : 1),
            note: note.trimmingCharacters(in: .whitespacesAndNewlines),
            status: kind == .completedLog ? .completed : (item?.status ?? .planned),
            actualStartDate: kind == .completedLog ? normalizedActualStart : item?.actualStartDate,
            actualEndDate: kind == .completedLog ? normalizedActualEnd : item?.actualEndDate,
            completedAt: kind == .completedLog ? normalizedActualEnd : item?.completedAt,
            calendarSyncEnabled: calendarSyncEnabled,
            calendarEventIdentifier: calendarEventIdentifier,
            createdAt: item?.createdAt ?? Date(),
            scheduleKind: scheduleKind,
            detailsConfigured: true,
            repeatRule: kind == .planned ? repeatRule : .never,
            repeatWeekdays: kind == .planned ? selectedWeekdays.sorted() : [],
            seriesID: item?.seriesID,
            reminderMinutesBefore: kind == .planned && reminderMinutesBefore >= 0
                ? reminderMinutesBefore
                : nil,
            plannedDurationEnabled: kind == .planned ? plannedDurationEnabled : false
        )

        if
            kind == .planned,
            reminderMinutesBefore >= 0,
            PlanSettingsKeys.bool(
                PlanSettingsKeys.planNotificationsEnabled,
                fallback: true
            )
        {
            do {
                let granted = try await PlanNotificationService.shared.requestAuthorizationIfNeeded()
                guard granted else {
                    calendarError = PlanNotificationError.accessDenied.localizedDescription
                    isSaving = false
                    return
                }
            } catch {
                calendarError = error.localizedDescription
                isSaving = false
                return
            }
        }

        if calendarSyncEnabled && calendarEventIdentifier == nil {
            do {
                updatedItem.calendarEventIdentifier = try await CalendarSyncService.shared.addEvent(
                    for: updatedItem
                )
                calendarEventIdentifier = updatedItem.calendarEventIdentifier
            } catch {
                calendarError = error.localizedDescription
                isSaving = false
                return
            }
        }

        store.saveCheckIn(updatedItem)
        dismiss()
    }

    private var weekdayOptions: [(label: String, value: Int)] {
        [
            ("一", 2), ("二", 3), ("三", 4), ("四", 5),
            ("五", 6), ("六", 7), ("日", 1)
        ]
    }

    private var reminderOptions: [(label: String, value: Int)] {
        [
            ("不提醒", -1),
            ("开始时", 0),
            ("提前 5 分钟", 5),
            ("提前 15 分钟", 15),
            ("提前 30 分钟", 30),
            ("提前 1 小时", 60),
            ("提前 1 天", 1_440)
        ]
    }

#if DEBUG
    private func runEditorQAScenarioIfNeeded() {
        guard item != nil else { return }
        switch ProcessInfo.processInfo.environment["MOJI_QA_SCENARIO"] {
        case "existing-plan-completed-editor":
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                kind = .completedLog
            }
        case "existing-plan-date-editor":
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                scheduledStart = Calendar.current.date(
                    byAdding: .day,
                    value: 1,
                    to: scheduledStart
                ) ?? scheduledStart
            }
        default:
            break
        }
    }
#endif

    private func normalizedPlanStart() -> Date {
        let rounded = scheduledStart.roundedDownToMinute()
        guard let hour = scheduleKind.representativeHour else { return rounded }
        var components = Calendar.current.dateComponents(
            [.year, .month, .day],
            from: rounded
        )
        components.hour = hour
        components.minute = 0
        return Calendar.current.date(from: components) ?? rounded
    }

    private func normalizedCompletedStart() -> Date {
        let rounded = actualStart.roundedDownToMinute()
        guard let hour = scheduleKind.representativeHour else { return rounded }
        var components = Calendar.current.dateComponents(
            [.year, .month, .day],
            from: rounded
        )
        components.hour = hour
        components.minute = 0
        return Calendar.current.date(from: components) ?? rounded
    }

    private var scheduleHelpText: String {
        switch scheduleKind {
        case .allDay:
            return "全天只决定计划在哪一天出现，不要求时长，也不会占用一段日历时间。"
        case .morning, .afternoon, .evening:
            return "时段用于清单排序与提醒；是否估算投入时间由下方单独决定。"
        case .exactTime:
            return "具体时间表示开始点；预计投入仍然是可选项。"
        }
    }
}

struct RecordEditorView: View {
    @ObservedObject var store: PlanStore
    let record: TimeRecord?

    @Environment(\.dismiss) private var dismiss
    @State private var isTitleFocused = false
    @State private var showsDeleteConfirmation = false

    @State private var title: String
    @State private var category: RecordCategory
    @State private var scheduleKind: ScheduleKind
    @State private var startDate: Date
    @State private var endDate: Date
    @State private var note: String

    init(store: PlanStore, record: TimeRecord? = nil) {
        self.store = store
        self.record = record

        let now = Date().roundedDownToMinute()
        _title = State(initialValue: record?.title ?? "")
        _category = State(initialValue: record?.category ?? .study)
        _scheduleKind = State(initialValue: record?.effectiveScheduleKind ?? .exactTime)
        _startDate = State(initialValue: record?.startDate ?? now.addingTimeInterval(-60 * 60))
        _endDate = State(initialValue: record?.endDate ?? now)
        _note = State(initialValue: record?.note ?? "")
    }

    private var cleanTitle: String {
        title.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var durationMinutes: Int {
        guard endDate > startDate else { return 0 }
        return Int(ceil(endDate.timeIntervalSince(startDate) / 60))
    }

    private var canSave: Bool {
        !cleanTitle.isEmpty
            && (scheduleKind != .exactTime || durationMinutes > 0)
    }

    var body: some View {
        NavigationStack {
            ZStack {
                InkWashBackground()

                Form {
                    Section("标题") {
                        IMESafeTextField(
                            placeholder: "例如：复习高等数学",
                            text: $title,
                            isFocused: $isTitleFocused,
                            accessibilityIdentifier: "record.title"
                        )
                    }

                    Section("详细说明（可选）") {
                        TextField(
                            "补充这段记录的细节",
                            text: $note,
                            axis: .vertical
                        )
                            .lineLimit(5...12)
                            .frame(minHeight: 116, alignment: .topLeading)
                            .accessibilityIdentifier("record.details")
                    }

                    Section("类型") {
                        PlanCategoryPicker(selection: $category)
                    }

                    Section {
                        Picker("记录时段", selection: $scheduleKind) {
                            ForEach(ScheduleKind.allCases) { option in
                                Label(option.displayName, systemImage: option.symbolName)
                                    .tag(option)
                            }
                        }

                        if scheduleKind == .exactTime {
                            DatePicker(
                                "开始",
                                selection: $startDate,
                                displayedComponents: [.date, .hourAndMinute]
                            )
                            DatePicker(
                                "结束",
                                selection: $endDate,
                                displayedComponents: [.date, .hourAndMinute]
                            )

                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 8) {
                                    ForEach([25, 45, 60, 90], id: \.self) { minutes in
                                        Button("\(minutes) 分钟") {
                                            endDate = startDate.addingTimeInterval(
                                                TimeInterval(minutes * 60)
                                            )
                                        }
                                        .buttonStyle(.bordered)
                                        .buttonBorderShape(.capsule)
                                    }
                                }
                            }

                            HStack {
                                Text("本次合计")
                                Spacer()
                                Text(DurationText.full(minutes: durationMinutes))
                                    .fontWeight(.semibold)
                                    .foregroundStyle(canSave ? category.color : Color.secondary)
                            }
                        } else {
                            DatePicker(
                                "日期",
                                selection: $startDate,
                                displayedComponents: [.date]
                            )
                            Text("只记录“\(scheduleKind.displayName)”这一事实，不自动估算分钟数。")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    } header: {
                        Text("实际发生在")
                    }

                    if record != nil {
                        Section {
                            Button("删除这条记录", role: .destructive) {
                                showsDeleteConfirmation = true
                            }
                        } footer: {
                            if record?.checkInItemID != nil {
                                Text("若记录关联计划，删除后对应计划会恢复为未完成。")
                            }
                        }
                    }
                }
                .inkFormStyle()
            }
            .navigationTitle(record == nil ? "添加记录" : "编辑记录")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        IMETextInput.commit(save)
                    }
                    .fontWeight(.semibold)
                    .disabled(!canSave && !isTitleFocused)
                }
            }
            .onAppear {
                if record == nil { isTitleFocused = true }
            }
            .alert("删除记录？", isPresented: $showsDeleteConfirmation) {
                Button("删除", role: .destructive) {
                    deleteRecord()
                }
                Button("取消", role: .cancel) {}
            } message: {
                Text(
                    record?.checkInItemID == nil
                        ? "记录会从日历和总结中删除，此操作无法撤销。"
                        : "记录会被删除，对应计划会恢复为未完成，此操作无法撤销。"
                )
            }
        }
    }

    private func save() {
        guard canSave else { return }
        let normalizedStart: Date
        let normalizedEnd: Date
        if scheduleKind == .exactTime {
            normalizedStart = startDate.roundedDownToMinute()
            let roundedEnd = endDate.roundedDownToMinute()
            normalizedEnd = max(roundedEnd, normalizedStart.addingTimeInterval(60))
        } else {
            normalizedStart = normalizedSemanticStart()
            normalizedEnd = normalizedStart
        }

        store.saveRecord(
            TimeRecord(
                id: record?.id ?? UUID(),
                title: cleanTitle,
                category: category,
                startDate: normalizedStart,
                endDate: normalizedEnd,
                note: note.trimmingCharacters(in: .whitespacesAndNewlines),
                checkInItemID: record?.checkInItemID,
                scheduleKind: scheduleKind
            )
        )
        dismiss()
    }

    private func deleteRecord() {
        guard let record else { return }
        store.deleteRecord(id: record.id)
        dismiss()
    }

    private func normalizedSemanticStart() -> Date {
        let rounded = startDate.roundedDownToMinute()
        guard let hour = scheduleKind.representativeHour else { return rounded }
        var components = Calendar.current.dateComponents(
            [.year, .month, .day],
            from: rounded
        )
        components.hour = hour
        components.minute = 0
        return Calendar.current.date(from: components) ?? rounded
    }
}

private struct PlanCategoryPicker: View {
    @Binding var selection: RecordCategory

    @AppStorage(PlanSettingsKeys.customCategories, store: SharedPersistence.sharedDefaults)
    private var customCategoriesJSON = "[]"
    @State private var isAddingCategory = false
    @State private var newCategoryName = ""
    @State private var isCategoryNameFocused = false

    private var categories: [RecordCategory] {
        PlanCategoryLibrary.availableCategories(
            customJSON: customCategoriesJSON,
            including: selection
        )
    }

    var body: some View {
        Picker("计划类型", selection: $selection) {
            ForEach(categories) { category in
                Label(category.displayName, systemImage: category.symbolName)
                    .tag(category)
            }
        }

        if isAddingCategory {
            HStack(spacing: 10) {
                IMESafeTextField(
                    placeholder: "输入类型名称",
                    text: $newCategoryName,
                    isFocused: $isCategoryNameFocused,
                    keepsFocusOnSubmit: true,
                    accessibilityIdentifier: "plan.category.new",
                    onSubmit: addCategory
                )

                Button("添加") {
                    IMETextInput.commit(addCategory)
                }
                .disabled(
                    PlanCategoryLibrary.normalizedName(newCategoryName).isEmpty
                        && !isCategoryNameFocused
                )
            }
        } else {
            Button {
                isAddingCategory = true
                isCategoryNameFocused = true
            } label: {
                Label("新建自定义类型", systemImage: "plus")
            }
        }
    }

    private func addCategory() {
        guard let result = PlanCategoryLibrary.adding(
            name: newCategoryName,
            to: customCategoriesJSON
        ) else { return }
        customCategoriesJSON = result.json
        selection = result.category
        newCategoryName = ""
        isCategoryNameFocused = false
        isAddingCategory = false
    }
}
