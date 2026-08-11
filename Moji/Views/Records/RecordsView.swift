import SwiftUI
import UniformTypeIdentifiers
import WidgetKit

private enum SummaryRange: String, CaseIterable, Identifiable {
    case week
    case month

    var id: String { rawValue }
}

struct RecordsView: View {
    @ObservedObject var store: PlanStore
    @State private var qaRange: SummaryRange?
#if DEBUG
    @State private var didRunSummaryQAScenario = false
#endif

    private var weekSummary: CheckInWeekSummary {
        CheckInAnalytics.week(
            items: store.checkInItems,
            records: store.records,
            containing: Date()
        )
    }

    private var monthSummary: CheckInMonthSummary {
        CheckInAnalytics.month(
            items: store.checkInItems,
            records: store.records,
            containing: Date()
        )
    }

    private var recentRecords: [TimeRecord] {
        Array(store.records.sorted { $0.startDate > $1.startDate }.prefix(3))
    }

    var body: some View {
        NavigationStack {
            List {
                Section("总结") {
                    NavigationLink {
                        SummaryDetailView(store: store, range: .week)
                    } label: {
                        summaryRow(
                            title: "本周",
                            detail: "\(weekSummary.completedCount)/\(weekSummary.plannedCount) 项 · \(DurationText.compact(minutes: weekSummary.actualMinutes))"
                        )
                    }

                    NavigationLink {
                        SummaryDetailView(store: store, range: .month)
                    } label: {
                        summaryRow(
                            title: "本月",
                            detail: "\(Int(monthSummary.completionRate * 100))% · 连续 \(monthSummary.currentStreak) 天"
                        )
                    }
                }

                Section("最近记录") {
                    if recentRecords.isEmpty {
                        Text("还没有时间记录")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(recentRecords) { record in
                            RecordRow(record: record)
                        }
                        NavigationLink {
                            AllRecordsView(store: store)
                        } label: {
                            Label("查看全部", systemImage: "list.bullet")
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background {
                InkWashBackground()
            }
            .navigationTitle("总结")
            .navigationDestination(item: $qaRange) { range in
                SummaryDetailView(store: store, range: range)
            }
            .onAppear {
#if DEBUG
                runSummaryQAScenarioIfNeeded()
#endif
            }
        }
    }

    private func summaryRow(title: String, detail: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title)
                .font(.body.weight(.medium))
            Text(detail)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }

#if DEBUG
    private func runSummaryQAScenarioIfNeeded() {
        guard !didRunSummaryQAScenario else { return }

        let range: SummaryRange
        switch ProcessInfo.processInfo.environment["MOJI_QA_SCENARIO"] {
        case "summary-week": range = .week
        case "summary-month": range = .month
        default: return
        }

        didRunSummaryQAScenario = true
        if !store.records.contains(where: { $0.title.hasPrefix("UI 核验样本") }) {
            let calendar = Calendar.mojiISO
            let startOfWeek = calendar.dateInterval(
                of: .weekOfYear,
                for: Date()
            )?.start ?? calendar.startOfDay(for: Date())
            let categories: [RecordCategory] = [.study, .work, .customSummary]

            for dayOffset in 0..<7 {
                let day = calendar.date(
                    byAdding: .day,
                    value: dayOffset,
                    to: startOfWeek
                ) ?? startOfWeek
                let start = calendar.date(
                    bySettingHour: 9 + dayOffset,
                    minute: 0,
                    second: 0,
                    of: day
                ) ?? day
                store.saveRecord(
                    TimeRecord(
                        title: "UI 核验样本 \(dayOffset + 1)",
                        category: categories[dayOffset % categories.count],
                        startDate: start,
                        endDate: start.addingTimeInterval(
                            TimeInterval((dayOffset + 2) * 9 * 60)
                        )
                    )
                )
            }
        }

        // Wait until the summary sheet finishes presenting before pushing the
        // detail route; otherwise NavigationStack can discard the QA-only path.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) {
            qaRange = range
        }
    }
#endif
}

private struct SummaryDetailView: View {
    @ObservedObject var store: PlanStore
    let range: SummaryRange
    @State private var anchorDate = Date()

    var body: some View {
        ScrollView {
            if range == .week {
                WeeklySummaryView(
                    records: store.records,
                    checkInItems: store.checkInItems,
                    anchorDate: $anchorDate
                )
            } else {
                MonthlySummaryView(
                    records: store.records,
                    checkInItems: store.checkInItems,
                    anchorDate: $anchorDate
                )
            }
        }
        .contentMargins(
            .horizontal,
            PlanLayout.pageHorizontalPadding,
            for: .scrollContent
        )
        .contentMargins(.vertical, 16, for: .scrollContent)
        .background {
            InkWashBackground()
        }
        .navigationTitle(range == .week ? "周总结" : "月总结")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct AllRecordsView: View {
    @ObservedObject var store: PlanStore
    @State private var isPresentingEditor = false
    @State private var editingRecord: TimeRecord?

    private var groupedRecords: [RecordDayGroup] {
        let groups = Dictionary(grouping: store.records) {
            Calendar.current.startOfDay(for: $0.startDate)
        }
        return groups
            .map {
                RecordDayGroup(
                    date: $0.key,
                    records: $0.value.sorted { $0.startDate > $1.startDate }
                )
            }
            .sorted { $0.date > $1.date }
    }

    var body: some View {
        List {
            if groupedRecords.isEmpty {
                Text("还没有时间记录")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(groupedRecords) { group in
                    Section(group.headerTitle) {
                        ForEach(group.records) { record in
                            Button {
                                editingRecord = record
                            } label: {
                                RecordRow(record: record)
                            }
                            .buttonStyle(.plain)
                            .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                Button(role: .destructive) {
                                    store.deleteRecord(id: record.id)
                                } label: {
                                    Label("删除", systemImage: "trash")
                                }
                            }
                        }
                    }
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background {
            InkWashBackground()
        }
        .navigationTitle("时间记录")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    isPresentingEditor = true
                } label: {
                    Image(systemName: "plus")
                }
                .accessibilityLabel("添加记录")
            }
        }
        .sheet(isPresented: $isPresentingEditor) {
            RecordEditorView(store: store)
        }
        .sheet(item: $editingRecord) { record in
            RecordEditorView(store: store, record: record)
        }
    }
}

private struct RecordDayGroup: Identifiable {
    let date: Date
    let records: [TimeRecord]

    var id: Date { date }

    var headerTitle: String {
        if Calendar.current.isDateInToday(date) { return "今天" }
        if Calendar.current.isDateInYesterday(date) { return "昨天" }
        return date.formatted(.dateTime.year().month().day().weekday(.wide))
    }
}

struct PersonalView: View {
    @ObservedObject var store: PlanStore

    @State private var isPresentingSettings = false
    @State private var selectedReviewDay: CheckInDaySummary?
#if DEBUG
    @State private var didRunSettingsQAScenario = false
#endif

    private var weekSummary: CheckInWeekSummary {
        CheckInAnalytics.week(
            items: store.checkInItems,
            records: store.records,
            containing: Date()
        )
    }

    private var monthSummary: CheckInMonthSummary {
        CheckInAnalytics.month(
            items: store.checkInItems,
            records: store.records,
            containing: Date()
        )
    }

    private var pendingPlanCount: Int {
        store.checkInItems.filter {
            $0.kind == .planned
                && ($0.status == .planned || $0.status == .inProgress)
                && $0.isVisibleInChecklist(on: Date())
        }.count
    }

    private var hasWeeklyFocus: Bool {
        weekSummary.days.contains { $0.actualMinutes > 0 }
    }

    var body: some View {
        NavigationStack {
            ZStack {
                InkWashBackground()

                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        reviewSnapshot
                            .inkReveal(delay: 0.02)

                        VStack(spacing: 0) {
                            NavigationLink {
                                SummaryDetailView(store: store, range: .week)
                            } label: {
                                actionRowLabel(
                                    title: "周总结",
                                    message: "查看本周完成、专注和每日趋势",
                                    symbol: "chart.bar.xaxis"
                                )
                            }
                            .buttonStyle(.plain)

                            InkBrushDivider(tint: .planPrimary, animated: false)
                                .opacity(0.32)

                            NavigationLink {
                                SummaryDetailView(store: store, range: .month)
                            } label: {
                                actionRowLabel(
                                    title: "月总结",
                                    message: "查看月度热力图和计划/实际对比",
                                    symbol: "calendar"
                                )
                            }
                            .buttonStyle(.plain)

                            InkBrushDivider(tint: .planPrimary, animated: false)
                                .opacity(0.32)

                            NavigationLink {
                                AllRecordsView(store: store)
                            } label: {
                                actionRowLabel(
                                    title: "时间记录",
                                    message: "查看、补记或修改全部记录",
                                    symbol: "list.bullet.rectangle"
                                )
                            }
                            .buttonStyle(.plain)
                        }
                        .padding(.horizontal, 14)
                        .inkReveal(delay: 0.08)

                        Text("数据本机优先，可自动备份到 iCloud")
                            .font(.caption)
                            .foregroundStyle(Color.planSecondary.opacity(0.78))
                    }
                    .padding(.horizontal, 18)
                    .padding(.top, 16)
                    .padding(.bottom, 28)
                }
            }
            .navigationTitle("回顾")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        isPresentingSettings = true
                    } label: {
                        Image(systemName: "gearshape")
                            .frame(width: 44, height: 44)
                            .contentShape(Rectangle())
                    }
                    .accessibilityLabel("打开设置")
                }
            }
            .onAppear {
#if DEBUG
                guard !didRunSettingsQAScenario else { return }
                let scenario = ProcessInfo.processInfo.environment["MOJI_QA_SCENARIO"]
                guard
                    scenario == "settings"
                        || scenario == "summary-week"
                        || scenario == "summary-month"
                else { return }
                didRunSettingsQAScenario = true
                DispatchQueue.main.async {
                    if scenario == "settings" {
                        isPresentingSettings = true
                    } else {
                        seedSummaryQADataIfNeeded()
                    }
                }
#endif
            }
            .sheet(isPresented: $isPresentingSettings) {
                SettingsView(store: store)
            }
        }
    }

    private var reviewSnapshot: some View {
        VStack(alignment: .leading, spacing: 15) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("本周回顾")
                        .font(.title3.weight(.bold))
                    Text(weekSummary.startDate.formatted(.dateTime.month().day()) + " – " +
                         (Calendar.mojiISO.date(byAdding: .day, value: -1, to: weekSummary.endDate) ?? weekSummary.endDate)
                        .formatted(.dateTime.month().day()))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Text("完成率 \(Int(weekSummary.completionRate * 100))%")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(weekSummary.completionRate > 0 ? Color.planPrimary : .secondary)
            }

            HStack(alignment: .lastTextBaseline, spacing: 18) {
                reviewMetric(
                    value: weekSummary.plannedCount > 0
                        ? "\(weekSummary.completedCount)/\(weekSummary.plannedCount)"
                        : "—",
                    title: "完成计划"
                )
                reviewMetric(
                    value: DurationText.compact(minutes: weekSummary.actualMinutes),
                    title: "实际专注"
                )
                reviewMetric(
                    value: "\(monthSummary.currentStreak)天",
                    title: "连续完成"
                )
            }

            weeklyTrend

            if pendingPlanCount > 0 || !hasWeeklyFocus {
                Divider()
                    .opacity(0.22)
                reviewNextAction
            }
        }
        .padding(16)
        .inkPaperSurface(cornerRadius: 14)
    }

    @ViewBuilder
    private var weeklyTrend: some View {
        if hasWeeklyFocus {
            HStack(alignment: .bottom, spacing: 7) {
                ForEach(weekSummary.days) { day in
                    Button {
                        selectedReviewDay = day
                    } label: {
                        VStack(spacing: 5) {
                                Capsule(style: .continuous)
                                    .fill(
                                        day.actualMinutes > 0
                                            ? Color.planPrimary
                                            : Color.planPrimary.opacity(0.11)
                                    )
                                    .frame(
                                        width: 12,
                                        height: max(
                                            4,
                                            48 * CGFloat(day.actualMinutes)
                                            / CGFloat(weeklyMaximum)
                                    )
                                )
                            Text(day.date.formatted(.dateTime.weekday(.narrow)))
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .bottom)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(
                        "\(day.date.formatted(.dateTime.weekday(.wide)))，实际专注 "
                            + DurationText.compact(minutes: day.actualMinutes)
                    )
                }
            }
            .frame(height: 68, alignment: .bottom)

            if let selectedReviewDay {
                Text(
                    "\(selectedReviewDay.date.formatted(.dateTime.weekday(.wide))) · 实际专注 "
                        + DurationText.compact(minutes: selectedReviewDay.actualMinutes)
                )
                .font(.caption)
                .foregroundStyle(Color.planSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .transition(.opacity)
            }
        } else {
            VStack(alignment: .leading, spacing: 8) {
                Capsule(style: .continuous)
                    .fill(Color.planPrimary.opacity(0.12))
                    .frame(height: 2)
                Text("本周暂无专注记录")
                    .font(.caption)
                    .foregroundStyle(Color.planSecondary)
            }
            .frame(height: 44, alignment: .bottom)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("本周暂无专注记录")
        }
    }

    private var weeklyMaximum: Int {
        max(1, weekSummary.days.map(\.actualMinutes).max() ?? 0)
    }

    @ViewBuilder
    private var reviewNextAction: some View {
        if pendingPlanCount > 0 {
            reviewActionButton(
                title: "下一步：完成剩余 \(pendingPlanCount) 项计划",
                symbol: "checklist"
            ) {
                NotificationCenter.default.post(name: .mojiOpenChecklist, object: nil)
            }
        } else if !hasWeeklyFocus {
            reviewActionButton(
                title: "下一步：开始一个 25 分钟专注",
                symbol: "timer"
            ) {
                NotificationCenter.default.post(name: .mojiOpenPomodoro, object: nil)
            }
        }
    }

    private func reviewActionButton(
        title: String,
        symbol: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: symbol)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.planPrimary)
                    .frame(width: 24)
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.planPrimary)
                Spacer()
                Image(systemName: "arrow.up.right")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(Color.planSecondary)
            }
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func reviewMetric(value: String, title: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(value)
                .font(.title3.weight(.bold))
                .monospacedDigit()
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func actionRow(
        title: String,
        message: String,
        symbol: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            actionRowLabel(title: title, message: message, symbol: symbol)
        }
        .buttonStyle(.plain)
    }

    private func actionRowLabel(
        title: String,
        message: String,
        symbol: String
    ) -> some View {
        HStack(spacing: 13) {
            Image(systemName: symbol)
                .font(.body)
                .foregroundStyle(Color.planPrimary)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.body.weight(.medium))
                    .foregroundStyle(.primary)
                Text(message)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.leading)
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.caption.weight(.bold))
                .foregroundStyle(Color.planSecondary.opacity(0.72))
        }
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }

#if DEBUG
    private func seedSummaryQADataIfNeeded() {
        guard !store.records.contains(where: { $0.title.hasPrefix("UI 核验样本") }) else {
            return
        }
        let calendar = Calendar.mojiISO
        let startOfWeek = calendar.dateInterval(
            of: .weekOfYear,
            for: Date()
        )?.start ?? calendar.startOfDay(for: Date())
        let categories: [RecordCategory] = [.study, .work, .customSummary]

        for dayOffset in 0..<7 {
            let day = calendar.date(
                byAdding: .day,
                value: dayOffset,
                to: startOfWeek
            ) ?? startOfWeek
            let start = calendar.date(
                bySettingHour: 9 + dayOffset,
                minute: 0,
                second: 0,
                of: day
            ) ?? day
            store.saveRecord(
                TimeRecord(
                    title: "UI 核验样本 \(dayOffset + 1)",
                    category: categories[dayOffset % categories.count],
                    startDate: start,
                    endDate: start.addingTimeInterval(
                        TimeInterval((dayOffset + 2) * 9 * 60)
                    )
                )
            )
        }
    }
#endif
}

private struct SettingsView: View {
    @ObservedObject var store: PlanStore
    @Environment(\.dismiss) private var dismiss

    @AppStorage(PlanSettingsKeys.defaultCategory, store: SharedPersistence.sharedDefaults)
    private var defaultCategoryRaw = RecordCategory.study.rawValue
    @AppStorage(PlanSettingsKeys.defaultScheduleKind, store: SharedPersistence.sharedDefaults)
    private var defaultScheduleKindRaw = ScheduleKind.allDay.rawValue
    @AppStorage(
        PlanSettingsKeys.defaultPlannedDurationEnabled,
        store: SharedPersistence.sharedDefaults
    )
    private var defaultPlannedDurationEnabled = false
    @AppStorage(PlanSettingsKeys.defaultPlannedMinutes, store: SharedPersistence.sharedDefaults)
    private var defaultPlannedMinutes = 25
    @AppStorage(
        PlanSettingsKeys.carryOverUnfinishedPlans,
        store: SharedPersistence.sharedDefaults
    )
    private var carriesOverUnfinishedPlans = true
    @AppStorage(PlanSettingsKeys.quickPlanPresets, store: SharedPersistence.sharedDefaults)
    private var quickPlanPresetsJSON = QuickPlanPresetStore.defaultJSON
    @AppStorage(PomodoroStorageKeys.focusMinutes, store: SharedPersistence.sharedDefaults)
    private var focusMinutes = 25
    @AppStorage(PomodoroStorageKeys.shortBreakMinutes, store: SharedPersistence.sharedDefaults)
    private var shortBreakMinutes = 5
    @AppStorage(
        PlanSettingsKeys.planNotificationsEnabled,
        store: SharedPersistence.sharedDefaults
    )
    private var planNotificationsEnabled = true
    @AppStorage(
        PlanSettingsKeys.notificationSoundEnabled,
        store: SharedPersistence.sharedDefaults
    )
    private var notificationSoundEnabled = true
    @AppStorage(PlanSettingsKeys.allDayReminderHour, store: SharedPersistence.sharedDefaults)
    private var allDayReminderHour = 9
    @AppStorage(PlanSettingsKeys.appearanceMode, store: SharedPersistence.sharedDefaults)
    private var appearanceModeRaw = AppAppearanceMode.system.rawValue
    @AppStorage(PlanSettingsKeys.inkMotionLevel, store: SharedPersistence.sharedDefaults)
    private var inkMotionLevelRaw = InkMotionLevel.full.rawValue
    @AppStorage(PlanSettingsKeys.paperTextureEnabled, store: SharedPersistence.sharedDefaults)
    private var paperTextureEnabled = true

    var body: some View {
        NavigationStack {
            List {
                Section("偏好") {
                    NavigationLink {
                        PlanDefaultsSettingsView()
                    } label: {
                        settingsRow(
                            title: "计划默认值",
                            detail: planDefaultsDetail,
                            symbol: "checklist"
                        )
                    }

                    NavigationLink {
                        QuickPlanPresetSettingsView()
                    } label: {
                        settingsRow(
                            title: "小组件快速添加",
                            detail: "\(quickPlanPresetCount) 个桌面预设",
                            symbol: "square.grid.2x2"
                        )
                    }

                    NavigationLink {
                        PomodoroSettingsView()
                    } label: {
                        settingsRow(
                            title: "番茄钟",
                            detail: "专注 \(focusMinutes) 分钟 · 短休 \(shortBreakMinutes) 分钟",
                            symbol: "timer"
                        )
                    }

                    NavigationLink {
                        NotificationFeedbackSettingsView(store: store)
                    } label: {
                        settingsRow(
                            title: "提醒与反馈",
                            detail: notificationDetail,
                            symbol: "bell.and.waves.left.and.right"
                        )
                    }

                    NavigationLink {
                        AppearanceSettingsView()
                    } label: {
                        settingsRow(
                            title: "外观",
                            detail: appearanceDetail,
                            symbol: "paintbrush"
                        )
                    }
                }

                Section("系统与数据") {
                    NavigationLink {
                        PermissionSettingsView()
                    } label: {
                        settingsRow(
                            title: "权限",
                            detail: "通知与 Apple 日历",
                            symbol: "hand.raised"
                        )
                    }

                    NavigationLink {
                        BackupSettingsView(store: store)
                    } label: {
                        settingsRow(
                            title: "数据备份",
                            detail: "导出、导入与换签保护",
                            symbol: "externaldrive"
                        )
                    }

                    NavigationLink {
                        AboutSettingsView()
                    } label: {
                        settingsRow(
                            title: "关于 Moji",
                            detail: "版本与数据格式",
                            symbol: "info.circle"
                        )
                    }
                }

                Section {
                    LabeledContent("开发者", value: "WANG ZIRUI")
                        .contentShape(Rectangle())
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background {
                InkWashBackground()
            }
            .navigationTitle("设置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                        .fontWeight(.semibold)
                }
            }
        }
    }

    private func settingsRow(
        title: String,
        detail: String,
        symbol: String
    ) -> some View {
        HStack(spacing: 12) {
            Image(systemName: symbol)
                .foregroundStyle(Color.planPrimary)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.body.weight(.medium))
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(Color.planSecondary.opacity(0.88))
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 3)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }

    private var planDefaultsDetail: String {
        let category = RecordCategory(rawValue: defaultCategoryRaw)?.displayName ?? "学习"
        let schedule = ScheduleKind(rawValue: defaultScheduleKindRaw)?.displayName ?? "全天"
        let carry = carriesOverUnfinishedPlans ? "未完成顺延" : "只看当天"
        if defaultPlannedDurationEnabled {
            return "\(category) · \(schedule) · 预计 \(DurationText.full(minutes: defaultPlannedMinutes)) · \(carry)"
        }
        return "\(category) · \(schedule) · 不估算时长 · \(carry)"
    }

    private var quickPlanPresetCount: Int {
        QuickPlanPresetStore.presets(from: quickPlanPresetsJSON).count
    }

    private var notificationDetail: String {
        let reminder = planNotificationsEnabled ? "提醒开" : "提醒关"
        let sound = notificationSoundEnabled ? "声音开" : "静音"
        return "全天 \(String(format: "%02d:00", allDayReminderHour)) · \(reminder) · \(sound)"
    }

    private var appearanceDetail: String {
        let mode = AppAppearanceMode(rawValue: appearanceModeRaw)?.displayName ?? "跟随系统"
        let texture = paperTextureEnabled ? "纹理开" : "纹理关"
        let motion = InkMotionLevel(rawValue: inkMotionLevelRaw)?.displayName ?? "完整"
        return "\(mode) · \(texture) · 运笔\(motion)"
    }
}

private struct PlanDefaultsSettingsView: View {
    @AppStorage(PlanSettingsKeys.defaultCategory, store: SharedPersistence.sharedDefaults)
    private var defaultCategoryRaw = RecordCategory.study.rawValue
    @AppStorage(PlanSettingsKeys.customCategories, store: SharedPersistence.sharedDefaults)
    private var customCategoriesJSON = "[]"
    @AppStorage(PlanSettingsKeys.defaultScheduleKind, store: SharedPersistence.sharedDefaults)
    private var defaultScheduleKindRaw = ScheduleKind.allDay.rawValue
    @AppStorage(
        PlanSettingsKeys.defaultPlannedDurationEnabled,
        store: SharedPersistence.sharedDefaults
    )
    private var defaultPlannedDurationEnabled = false
    @AppStorage(PlanSettingsKeys.defaultPlannedMinutes, store: SharedPersistence.sharedDefaults)
    private var defaultPlannedMinutes = 25
    @AppStorage(
        PlanSettingsKeys.carryOverUnfinishedPlans,
        store: SharedPersistence.sharedDefaults
    )
    private var carriesOverUnfinishedPlans = true
    @State private var newCategoryName = ""
    @State private var isCategoryNameFocused = false

    private var availableCategories: [RecordCategory] {
        PlanCategoryLibrary.availableCategories(customJSON: customCategoriesJSON)
    }

    private var customCategories: [RecordCategory] {
        PlanCategoryLibrary.customCategories(from: customCategoriesJSON)
    }

    var body: some View {
        Form {
            Section {
                Picker("默认类型", selection: $defaultCategoryRaw) {
                    ForEach(availableCategories) { category in
                        Text(category.displayName).tag(category.rawValue)
                    }
                }

                Picker("默认安排", selection: $defaultScheduleKindRaw) {
                    ForEach(ScheduleKind.allCases) { kind in
                        Text(kind.displayName).tag(kind.rawValue)
                    }
                }
            } header: {
                Text("详细新建")
            } footer: {
                Text("首页快速输入仍保持“今天、无时长”，避免添加一句计划时被迫配置细节。")
            }

            Section {
                Toggle("未完成的计划顺延到今天", isOn: $carriesOverUnfinishedPlans)
            } header: {
                Text("今日清单")
            } footer: {
                Text("开启时，之前没做完的计划会继续留在今日清单里；关闭后今日清单只显示安排在今天的计划，旧计划仍在日历和总结里，不会丢。已完成的计划只属于完成当天，任何时候都不会跟到第二天。")
            }

            Section {
                ForEach(customCategories) { category in
                    Label(category.displayName, systemImage: category.symbolName)
                }
                .onDelete(perform: deleteCategories)

                HStack(spacing: 10) {
                    IMESafeTextField(
                        placeholder: "例如：运动、阅读、生活",
                        text: $newCategoryName,
                        isFocused: $isCategoryNameFocused,
                        keepsFocusOnSubmit: true,
                        accessibilityIdentifier: "settings.category.new",
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
            } header: {
                Text("自定义类型")
            } footer: {
                Text("删除只会从今后的选择中移除；已有计划和实际记录仍会保留原来的类型名称。")
            }

            Section {
                Toggle("默认添加预计投入", isOn: $defaultPlannedDurationEnabled)
                if defaultPlannedDurationEnabled {
                    Stepper(value: $defaultPlannedMinutes, in: 5...480, step: 5) {
                        LabeledContent(
                            "默认投入",
                            value: DurationText.full(minutes: defaultPlannedMinutes)
                        )
                    }
                }
            } header: {
                Text("投入估算")
            } footer: {
                Text("这个值只用于详细新建；全天、时段和预计投入仍然相互独立。")
            }
        }
        .inkFormStyle()
        .background { InkWashBackground() }
        .navigationTitle("计划默认值")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func addCategory() {
        guard let result = PlanCategoryLibrary.adding(
            name: newCategoryName,
            to: customCategoriesJSON
        ) else { return }
        customCategoriesJSON = result.json
        defaultCategoryRaw = result.category.rawValue
        newCategoryName = ""
        isCategoryNameFocused = false
    }

    private func deleteCategories(at offsets: IndexSet) {
        var updatedJSON = customCategoriesJSON
        for index in offsets.sorted(by: >) where customCategories.indices.contains(index) {
            let category = customCategories[index]
            updatedJSON = PlanCategoryLibrary.removing(category, from: updatedJSON)
            if defaultCategoryRaw == category.rawValue {
                defaultCategoryRaw = RecordCategory.study.rawValue
            }
        }
        customCategoriesJSON = updatedJSON
    }
}

private struct QuickPlanPresetSettingsView: View {
    @AppStorage(PlanSettingsKeys.quickPlanPresets, store: SharedPersistence.sharedDefaults)
    private var quickPlanPresetsJSON = QuickPlanPresetStore.defaultJSON

    private var presets: [QuickPlanPreset] {
        QuickPlanPresetStore.presets(from: quickPlanPresetsJSON)
    }

    var body: some View {
        Form {
            Section {
                ForEach(presets) { preset in
                    NavigationLink {
                        QuickPlanPresetEditorView(preset: preset)
                    } label: {
                        HStack(spacing: 12) {
                            InkBrushMedallion(
                                symbol: preset.symbolName,
                                tint: preset.category?.color ?? .planPrimary,
                                size: 31,
                                seed: InkVariant.seed(for: preset.id)
                            )
                            VStack(alignment: .leading, spacing: 3) {
                                Text(preset.buttonTitle)
                                    .font(.body.weight(.medium))
                                Text(
                                    "\(preset.planTitle) · \(preset.category?.displayName ?? "跟随默认类型")"
                                )
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                            }
                            Spacer(minLength: 0)
                        }
                        .padding(.vertical, 2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                }
            } header: {
                Text("三个快捷按钮")
            } footer: {
                Text("中号小组件显示全部三个预设，小号小组件使用第一个。修改后会自动刷新桌面。")
            }

            Section {
                Button("恢复默认预设") {
                    quickPlanPresetsJSON = QuickPlanPresetStore.defaultJSON
                    WidgetCenter.shared.reloadTimelines(ofKind: "MojiQuickPlanWidget")
                }
                .foregroundStyle(Color.planVermilion)
            }
        }
        .inkFormStyle()
        .background { InkWashBackground() }
        .navigationTitle("快速添加预设")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct QuickPlanPresetEditorView: View {
    let presetID: String

    @Environment(\.dismiss) private var dismiss
    @AppStorage(PlanSettingsKeys.quickPlanPresets, store: SharedPersistence.sharedDefaults)
    private var quickPlanPresetsJSON = QuickPlanPresetStore.defaultJSON
    @AppStorage(PlanSettingsKeys.customCategories, store: SharedPersistence.sharedDefaults)
    private var customCategoriesJSON = "[]"

    @State private var buttonTitle: String
    @State private var planTitle: String
    @State private var categoryRaw: String
    @State private var isButtonTitleFocused = false
    @State private var isPlanTitleFocused = false

    init(preset: QuickPlanPreset) {
        presetID = preset.id
        _buttonTitle = State(initialValue: preset.buttonTitle)
        _planTitle = State(initialValue: preset.planTitle)
        _categoryRaw = State(initialValue: preset.category?.rawValue ?? "")
    }

    private var currentCategory: RecordCategory? {
        RecordCategory(rawValue: categoryRaw)
    }

    private var availableCategories: [RecordCategory] {
        PlanCategoryLibrary.availableCategories(
            customJSON: customCategoriesJSON,
            including: currentCategory
        )
    }

    private var cleanButtonTitle: String? {
        QuickPlanPresetStore.normalized(buttonTitle, limit: 8)
    }

    private var cleanPlanTitle: String? {
        QuickPlanPresetStore.normalized(planTitle, limit: 24)
    }

    var body: some View {
        Form {
            Section {
                HStack(spacing: 12) {
                    Text("按钮文字")
                    IMESafeTextField(
                        placeholder: "例如：背单词",
                        text: $buttonTitle,
                        isFocused: $isButtonTitleFocused
                    )
                    .multilineTextAlignment(.trailing)
                }

                HStack(spacing: 12) {
                    Text("计划标题")
                    IMESafeTextField(
                        placeholder: "例如：今日背单词",
                        text: $planTitle,
                        isFocused: $isPlanTitleFocused
                    )
                    .multilineTextAlignment(.trailing)
                }

                Picker("计划类型", selection: $categoryRaw) {
                    Text("跟随默认类型").tag("")
                    ForEach(availableCategories) { category in
                        Text(category.displayName).tag(category.rawValue)
                    }
                }
            } footer: {
                Text("按钮文字最多 8 个字，计划标题最多 24 个字。重复添加时会自动加上序号。")
            }

            Section {
                HStack {
                    Text("小组件预览")
                    Spacer()
                    Label(
                        cleanButtonTitle ?? "预设",
                        systemImage: currentCategory?.symbolName ?? "plus"
                    )
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(currentCategory?.color ?? Color.planPrimary)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 7)
                    .background(
                        (currentCategory?.color ?? Color.planPrimary).opacity(0.09),
                        in: UnevenRoundedRectangle(
                            topLeadingRadius: 10,
                            bottomLeadingRadius: 8,
                            bottomTrailingRadius: 11,
                            topTrailingRadius: 7
                        )
                    )
                }
            }
        }
        .inkFormStyle()
        .background { InkWashBackground() }
        .navigationTitle("编辑预设")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("保存") {
                    save()
                }
                .fontWeight(.semibold)
                .disabled(cleanButtonTitle == nil || cleanPlanTitle == nil)
            }
        }
    }

    private func save() {
        guard let cleanButtonTitle, let cleanPlanTitle else { return }
        var updated = QuickPlanPresetStore.presets(from: quickPlanPresetsJSON)
        guard let index = updated.firstIndex(where: { $0.id == presetID }) else { return }
        updated[index].buttonTitle = cleanButtonTitle
        updated[index].planTitle = cleanPlanTitle
        updated[index].category = currentCategory
        quickPlanPresetsJSON = QuickPlanPresetStore.json(for: updated)
        WidgetCenter.shared.reloadTimelines(ofKind: "MojiQuickPlanWidget")
        dismiss()
    }
}

private struct PomodoroSettingsView: View {
    @AppStorage(PomodoroStorageKeys.focusMinutes, store: SharedPersistence.sharedDefaults)
    private var focusMinutes = 25
    @AppStorage(PomodoroStorageKeys.shortBreakMinutes, store: SharedPersistence.sharedDefaults)
    private var shortBreakMinutes = 5
    @AppStorage(PomodoroStorageKeys.longBreakMinutes, store: SharedPersistence.sharedDefaults)
    private var longBreakMinutes = 15
    @AppStorage(PomodoroStorageKeys.longBreakInterval, store: SharedPersistence.sharedDefaults)
    private var longBreakInterval = 4
    @AppStorage(PomodoroStorageKeys.longBreakEnabled, store: SharedPersistence.sharedDefaults)
    private var longBreakEnabled = true
    @AppStorage(PlanSettingsKeys.autoStartBreaks, store: SharedPersistence.sharedDefaults)
    private var autoStartBreaks = false
    @AppStorage(PlanSettingsKeys.autoStartFocus, store: SharedPersistence.sharedDefaults)
    private var autoStartFocus = false
    @AppStorage(PlanSettingsKeys.liveActivitiesEnabled, store: SharedPersistence.sharedDefaults)
    private var liveActivitiesEnabled = true
    @AppStorage(PlanSettingsKeys.keepScreenAwake, store: SharedPersistence.sharedDefaults)
    private var keepScreenAwake = false

    var body: some View {
        Form {
            Section {
                Stepper(value: $focusMinutes, in: 1...120) {
                    settingValue(title: "专注时长", value: "\(focusMinutes) 分钟")
                }
                Stepper(value: $shortBreakMinutes, in: 1...30) {
                    settingValue(title: "短休息", value: "\(shortBreakMinutes) 分钟")
                }
                Toggle("使用长休息", isOn: $longBreakEnabled)
                if longBreakEnabled {
                    Stepper(value: $longBreakMinutes, in: 1...60) {
                        settingValue(title: "长休息", value: "\(longBreakMinutes) 分钟")
                    }
                    Stepper(value: $longBreakInterval, in: 1...8) {
                        settingValue(title: "长休息间隔", value: "\(longBreakInterval) 个番茄")
                    }
                }
            } header: {
                Text("时长")
            } footer: {
                Text("新的时长会在重置当前阶段或进入下一阶段时生效。")
            }

            Section {
                Toggle("专注后自动开始休息", isOn: $autoStartBreaks)
                Toggle("休息后自动开始专注", isOn: $autoStartFocus)
            } header: {
                Text("自动衔接")
            } footer: {
                Text("手动跳过阶段不会触发自动开始。")
            }

            Section {
                Toggle("锁屏与灵动岛", isOn: $liveActivitiesEnabled)
                Toggle("专注时保持屏幕常亮", isOn: $keepScreenAwake)
            } header: {
                Text("显示")
            } footer: {
                Text("关闭锁屏与灵动岛后，计时仍会在 App 内继续，并可使用本地通知提醒。")
            }
        }
        .inkFormStyle()
        .background { InkWashBackground() }
        .navigationTitle("番茄钟")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: liveActivitiesEnabled) { _, enabled in
            guard !enabled else { return }
            Task {
                await PomodoroLiveActivityController.end(
                    using: PomodoroSharedState.current()
                )
            }
        }
    }

    private func settingValue(title: String, value: String) -> some View {
        HStack {
            Text(title)
            Spacer()
            Text(value)
                .foregroundStyle(.secondary)
                .monospacedDigit()
        }
    }
}

private struct NotificationFeedbackSettingsView: View {
    @ObservedObject var store: PlanStore

    @AppStorage(
        PlanSettingsKeys.planNotificationsEnabled,
        store: SharedPersistence.sharedDefaults
    )
    private var planNotificationsEnabled = true
    @AppStorage(
        PlanSettingsKeys.notificationSoundEnabled,
        store: SharedPersistence.sharedDefaults
    )
    private var notificationSoundEnabled = true
    @AppStorage(PlanSettingsKeys.hapticsEnabled, store: SharedPersistence.sharedDefaults)
    private var hapticsEnabled = true
    @AppStorage(PlanSettingsKeys.allDayReminderHour, store: SharedPersistence.sharedDefaults)
    private var allDayReminderHour = 9

    var body: some View {
        Form {
            Section {
                Toggle("允许 App 安排提醒", isOn: $planNotificationsEnabled)
                Toggle("通知声音", isOn: $notificationSoundEnabled)
                    .disabled(!planNotificationsEnabled)

                Stepper(value: $allDayReminderHour, in: 0...23) {
                    LabeledContent(
                        "全天计划提醒",
                        value: String(format: "%02d:00", allDayReminderHour)
                    )
                }
                .disabled(!planNotificationsEnabled)
            } header: {
                Text("提醒")
            } footer: {
                Text("这是 Moji 内部总开关；iOS 系统通知权限仍可在“权限”中检查。具体计划仍需单独选择提醒。")
            }

            Section {
                Toggle("操作触感", isOn: $hapticsEnabled)
            } footer: {
                Text("控制完成打卡和底部切换时的触感反馈。")
            }
        }
        .inkFormStyle()
        .background { InkWashBackground() }
        .navigationTitle("提醒与反馈")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: planNotificationsEnabled) { _, enabled in
            if !enabled {
                PlanNotificationService.shared.cancelPomodoroCompletion()
            }
            store.applyPreferenceChanges()
        }
        .onChange(of: notificationSoundEnabled) { _, _ in
            store.applyPreferenceChanges()
        }
        .onChange(of: allDayReminderHour) { _, _ in
            store.applyPreferenceChanges()
        }
    }
}

private struct AppearanceSettingsView: View {
    @AppStorage(PlanSettingsKeys.appearanceMode, store: SharedPersistence.sharedDefaults)
    private var appearanceModeRaw = AppAppearanceMode.system.rawValue
    @AppStorage(PlanSettingsKeys.inkMotionLevel, store: SharedPersistence.sharedDefaults)
    private var inkMotionLevelRaw = InkMotionLevel.full.rawValue
    @AppStorage(PlanSettingsKeys.paperTextureEnabled, store: SharedPersistence.sharedDefaults)
    private var paperTextureEnabled = true

    var body: some View {
        Form {
            Section {
                Picker("显示模式", selection: $appearanceModeRaw) {
                    ForEach(AppAppearanceMode.allCases) { mode in
                        Text(mode.displayName).tag(mode.rawValue)
                    }
                }

                Toggle("宣纸纹理", isOn: $paperTextureEnabled)
            } header: {
                Text("纸墨")
            }

            Section {
                Picker("番茄钟运笔", selection: $inkMotionLevelRaw) {
                    ForEach(InkMotionLevel.allCases) { level in
                        Text(level.displayName).tag(level.rawValue)
                    }
                }
                .pickerStyle(.segmented)
            } header: {
                Text("动效")
            } footer: {
                Text("“轻量”降低绘制刷新率；“关闭”保留计时进度，但不显示移动笔锋。系统“减弱动态效果”仍有更高优先级。")
            }
        }
        .inkFormStyle()
        .background { InkWashBackground() }
        .navigationTitle("外观")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct PermissionSettingsView: View {
    @State private var notificationStatusText = "检查中"
    @State private var statusMessage: String?

    var body: some View {
        Form {
            Section {
                HStack {
                    Label("计划提醒", systemImage: "bell")
                    Spacer()
                    Text(notificationStatusText)
                        .foregroundStyle(.secondary)
                }
                Button("请求通知权限") {
                    Task { await requestNotificationAuthorization() }
                }
            } header: {
                Text("通知")
            } footer: {
                Text("番茄钟结束和计划到时提醒均使用 iOS 本地通知。")
            }

            Section("Apple 日历") {
                Label("计划与纪念日均可单独选择", systemImage: "calendar.badge.plus")
                Text("App 只申请日历写入权限，不读取你的其他日历内容。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .inkFormStyle()
        .background { InkWashBackground() }
        .navigationTitle("权限")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await updateNotificationStatus()
        }
        .alert(
            "权限",
            isPresented: Binding(
                get: { statusMessage != nil },
                set: { if !$0 { statusMessage = nil } }
            )
        ) {
            Button("好", role: .cancel) {}
        } message: {
            Text(statusMessage ?? "")
        }
    }

    @MainActor
    private func requestNotificationAuthorization() async {
        do {
            let granted = try await PlanNotificationService.shared.requestAuthorizationIfNeeded()
            statusMessage = granted
                ? "通知权限已开启。"
                : PlanNotificationError.accessDenied.localizedDescription
            await updateNotificationStatus()
        } catch {
            statusMessage = error.localizedDescription
        }
    }

    @MainActor
    private func updateNotificationStatus() async {
        switch await PlanNotificationService.shared.authorizationStatus() {
        case .authorized: notificationStatusText = "已允许"
        case .provisional, .ephemeral: notificationStatusText = "临时允许"
        case .denied: notificationStatusText = "已关闭"
        case .notDetermined: notificationStatusText = "未请求"
        @unknown default: notificationStatusText = "未知"
        }
    }
}

private struct BackupSettingsView: View {
    @ObservedObject var store: PlanStore

    @State private var backupDocument = PlanBackupDocument()
    @State private var isExportingBackup = false
    @State private var isImportingBackup = false
    @State private var showsImportConfirmation = false
    @State private var pendingImportData: Data?
    @State private var pendingImportURL: URL?
    @State private var statusMessage: String?
    @State private var isPickingBackupFolder = false
    @State private var isPickingRestoreFolder = false
    @ObservedObject private var externalBackup = ExternalBackupService.shared

    private var lastBackupText: String {
        guard let date = externalBackup.lastBackupDate else { return "尚未备份" }
        return date.formatted(date: .abbreviated, time: .shortened)
    }

    var body: some View {
        Form {
            Section {
                Label("已使用兼容爱思自签的备份方案", systemImage: "checkmark.shield")
                    .foregroundStyle(Color.planPrimary)
                Text("专用 iCloud 容器需要开发者描述文件，普通 Apple ID 的爱思 7 天自签无法授权。Moji 因此改用下方的 iCloud 云盘文件夹备份。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } header: {
                Text("爱思助手自签说明")
            } footer: {
                Text("备份文件可以放在 iCloud 云盘，不必保存在本机。")
            }

            Section {
                if let folder = externalBackup.folderDisplayName {
                    HStack {
                        Label("备份文件夹", systemImage: "folder")
                        Spacer()
                        Text(folder)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }

                    HStack {
                        Text("上次备份")
                            .foregroundStyle(.secondary)
                        Spacer()
                        Text(lastBackupText)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }

                    Picker(
                        "历史备份保留",
                        selection: Binding(
                            get: { externalBackup.retention },
                            set: { externalBackup.setRetention($0) }
                        )
                    ) {
                        ForEach(BackupRetention.allCases) { option in
                            Text(option.displayName).tag(option)
                        }
                    }

                    Button {
                        Task {
                            statusMessage = await externalBackup.backupNow()
                                ? "已写入最新备份。"
                                : (externalBackup.lastErrorMessage ?? "备份失败。")
                        }
                    } label: {
                        Label("立即备份一次", systemImage: "arrow.clockwise")
                    }

                    Button(role: .destructive) {
                        externalBackup.forgetFolder()
                        statusMessage = "已停止自动备份。"
                    } label: {
                        Label("停止自动备份", systemImage: "xmark.circle")
                    }
                } else {
                    if let expected = externalBackup.expectedFolderName {
                        Label(
                            "上次备份写在「\(expected)」，选中它就能接着用",
                            systemImage: "arrow.uturn.backward"
                        )
                        .font(.footnote)
                        .foregroundStyle(Color.planVermilion)
                    }
                    Button {
                        isPickingBackupFolder = true
                    } label: {
                        Label("选择自动备份文件夹", systemImage: "folder.badge.plus")
                    }
                }
            } header: {
                Text("iCloud 云盘文件夹备份（兼容爱思自签）")
            } footer: {
                Text(
                    externalBackup.lastErrorMessage
                        ?? "请优先在「文件」中选择 iCloud 云盘里的文件夹。数据变化后会自动写入，并每天另存历史备份。换签安装后，在首页提示中从同一云盘文件夹选取最新备份即可恢复。"
                )
            }

            Section {
                Button {
                    prepareBackupExport()
                } label: {
                    Label("导出完整备份", systemImage: "square.and.arrow.up")
                }

                Button {
                    isPickingRestoreFolder = true
                } label: {
                    Label("从备份文件夹恢复", systemImage: "folder.badge.gearshape")
                }

                Button {
                    isImportingBackup = true
                } label: {
                    Label("从单个备份文件导入", systemImage: "square.and.arrow.down")
                }
            } header: {
                Text("手动备份")
            } footer: {
                Text("重新签名会更换 App 身份，iOS 会把旧沙盒当作另一个 App 删除——这一点 App 代码无法绕过。上面的自动备份写在沙盒之外，所以能挺过重签。")
            }
        }
        .fileImporter(
            isPresented: $isPickingBackupFolder,
            allowedContentTypes: [.folder]
        ) { result in
            switch result {
            case let .success(url):
                externalBackup.useFolder(at: url)
                statusMessage = externalBackup.lastErrorMessage ?? "已开启自动备份。"
            case let .failure(error):
                statusMessage = error.localizedDescription
            }
        }
        .fileImporter(
            isPresented: $isPickingRestoreFolder,
            allowedContentTypes: [.folder]
        ) { result in
            switch result {
            case let .success(url):
                restoreFromFolder(at: url)
            case let .failure(error):
                statusMessage = error.localizedDescription
            }
        }
        .inkFormStyle()
        .background { InkWashBackground() }
        .navigationTitle("数据备份")
        .navigationBarTitleDisplayMode(.inline)
        .fileExporter(
            isPresented: $isExportingBackup,
            document: backupDocument,
            contentType: MojiBackupFile.contentType,
            defaultFilename: backupFilename
        ) { result in
            switch result {
            case .success:
                statusMessage = "备份已经导出。以后重签前保留这份文件即可恢复。"
            case .failure(let error):
                statusMessage = error.localizedDescription
            }
        }
        .fileImporter(
            isPresented: $isImportingBackup,
            allowedContentTypes: MojiBackupFile.readableContentTypes,
            allowsMultipleSelection: false
        ) { result in
            do {
                let url = try result.get().first
                guard let url else { return }
                pendingImportData = try ExternalBackupService.readBackup(at: url)
                pendingImportURL = url
                showsImportConfirmation = true
            } catch {
                statusMessage = error.localizedDescription
            }
        }
        .confirmationDialog(
            "导入会用备份内容替换 App 中的现有数据",
            isPresented: $showsImportConfirmation,
            titleVisibility: .visible
        ) {
            Button("替换并导入", role: .destructive) {
                importPendingBackup()
            }
            Button("取消", role: .cancel) {
                pendingImportData = nil
                pendingImportURL = nil
            }
        } message: {
            Text("当前数据会先由自动备份保留一份，但建议先手动导出。")
        }
        .alert(
            "数据备份",
            isPresented: Binding(
                get: { statusMessage != nil },
                set: { if !$0 { statusMessage = nil } }
            )
        ) {
            Button("好", role: .cancel) {}
        } message: {
            Text(statusMessage ?? "")
        }
    }

    private var backupFilename: String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "yyyyMMdd-HHmm"
        return "Moji-\(formatter.string(from: Date())).mojibackup"
    }

    private func prepareBackupExport() {
        do {
            backupDocument = PlanBackupDocument(data: try SharedPersistence.exportBackup())
            isExportingBackup = true
        } catch {
            statusMessage = error.localizedDescription
        }
    }

    private func restoreFromFolder(at url: URL) {
        do {
            try BackupRestoreCoordinator.restore(fromFolderAt: url, into: store)
            statusMessage = "\(importedSummary)自动备份已指向同一个文件夹。"
        } catch {
            statusMessage = error.localizedDescription
        }
    }

    private func importPendingBackup() {
        guard let pendingImportData else { return }
        do {
            try store.importBackup(pendingImportData)
            // The import rewrote the shared defaults, including the retention
            // setting this screen displays.
            externalBackup.reloadFromDefaults()
            let didAdoptFolder = pendingImportURL.map {
                externalBackup.adoptFolder(containing: $0)
            } ?? false
            statusMessage = importedSummary
                + (
                    didAdoptFolder
                        ? "自动备份已指向同一个文件夹。"
                        : "如需继续自动备份，请再选一次备份文件夹。"
                )
        } catch {
            statusMessage = error.localizedDescription
        }
        self.pendingImportData = nil
        self.pendingImportURL = nil
    }

    private var importedSummary: String {
        "备份已导入：\(store.checkInItems.count) 条计划、\(store.memos.count) 则备忘、\(store.records.count) 条记录、\(store.countdowns.count) 个日期。"
    }
}

private struct AboutSettingsView: View {
    var body: some View {
        Form {
            Section {
                LabeledContent("应用", value: "Moji")
                LabeledContent(
                    "版本",
                    value: "V\(appVersion) 正式版"
                )
                LabeledContent("开发者", value: "WANG ZIRUI")
                LabeledContent("数据格式", value: "v\(PlanSnapshot.currentSchemaVersion)")
                LabeledContent("最低系统", value: "iOS 17")
            }
        }
        .inkFormStyle()
        .background { InkWashBackground() }
        .navigationTitle("关于 Moji")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—"
    }
}

private struct PlanBackupDocument: FileDocument {
    static var readableContentTypes: [UTType] {
        [MojiBackupFile.contentType, .json]
    }

    var data: Data

    init(data: Data = Data()) {
        self.data = data
    }

    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else {
            throw SharedPersistenceError.invalidBackup
        }
        self.data = data
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
