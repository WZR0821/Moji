import SwiftUI

enum PlanCalendarMode: String, CaseIterable, Identifiable {
    case month
    case week
    case day

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .month: return "月"
        case .week: return "周"
        case .day: return "日"
        }
    }
}

struct PlanCalendarEntry: Identifiable {
    enum Source {
        case checkIn(CheckInItem)
        case record(TimeRecord)
        case countdown(CountdownEvent)
    }

    let source: Source
    let title: String
    let note: String
    let category: RecordCategory
    let date: Date
    let scheduleKind: ScheduleKind
    let status: CheckInStatus
    let timingText: String
    let actualRecord: TimeRecord?

    var id: String {
        switch source {
        case let .checkIn(item): return "plan-\(item.id.uuidString)"
        case let .record(record): return "record-\(record.id.uuidString)"
        case let .countdown(event):
            return "countdown-\(event.id.uuidString)-\(Int(date.timeIntervalSinceReferenceDate))"
        }
    }

    var isCompleted: Bool {
        status == .completed
    }

    var statusText: String {
        if case .countdown = source { return "倒数日" }
        switch status {
        case .planned: return "待完成"
        case .inProgress: return "进行中"
        case .completed: return "已完成"
        case .skipped: return "已跳过"
        }
    }

    var statusSymbol: String {
        if case let .countdown(event) = source { return event.symbolName }
        switch status {
        case .planned: return "circle"
        case .inProgress: return "play.circle.fill"
        case .completed: return "checkmark.circle.fill"
        case .skipped: return "forward.end.circle"
        }
    }

    var isPomodoroRecord: Bool {
        actualRecord?.note.contains("番茄钟") == true
    }

    var isCountdown: Bool {
        if case .countdown = source { return true }
        return false
    }
}

/// Calendar indicators use shape as well as tone so status is readable in
/// monochrome mode and at a glance. The larger ink marks remain reserved for
/// detail rows; month cells stay quiet and legible.
private struct CalendarStatusMark: View {
    let status: CheckInStatus
    let isCountdown: Bool
    let tint: Color

    var body: some View {
        ZStack {
            if isCountdown {
                RoundedRectangle(cornerRadius: 1.5, style: .continuous)
                    .fill(tint.opacity(0.72))
            } else {
                switch status {
                case .planned:
                    Circle()
                        .stroke(tint, lineWidth: 1.15)
                case .inProgress:
                    Circle()
                        .fill(tint.opacity(0.36))
                        .overlay {
                            Circle().stroke(tint, lineWidth: 1)
                        }
                case .completed:
                    Circle().fill(tint)
                case .skipped:
                    Image(systemName: "slash.circle")
                        .font(.system(size: 9, weight: .semibold))
                        .foregroundStyle(tint)
                }
            }
        }
        .frame(width: 9, height: 9)
        .accessibilityHidden(true)
    }
}

struct PlanCalendarData {
    let entries: [PlanCalendarEntry]
    /// Entries bucketed by day.
    ///
    /// `entries(on:)` used to scan the whole list for every cell. A month grid
    /// asks 42 times, and while paging three grids are on screen, so a single
    /// frame cost 126 full scans — that was the drag stutter.
    private let entriesByDay: [Date: [PlanCalendarEntry]]
    private let oneTimeCountdownsByDay: [Date: [CountdownEvent]]
    private let yearlyCountdownsByMonthAndDay: [Int: [CountdownEvent]]
    private let monthlyCountdownsByDay: [Int: [CountdownEvent]]
    private let weeklyCountdownsByWeekday: [Int: [CountdownEvent]]

    init(
        items: [CheckInItem],
        records: [TimeRecord],
        countdowns: [CountdownEvent] = []
    ) {
        let plannedItems = items.filter { $0.kind == .planned }
        let linkedRecords = Dictionary(grouping: records.compactMap { record in
            record.checkInItemID.map { ($0, record) }
        }, by: \.0)
        var consumedRecordIDs = Set<UUID>()

        var result = plannedItems.map { item -> PlanCalendarEntry in
            let candidates = linkedRecords[item.id]?.map(\.1) ?? []
            let exactCompletion = candidates.first { record in
                guard let start = item.actualStartDate, let end = item.actualEndDate else {
                    return false
                }
                return abs(record.startDate.timeIntervalSince(start)) < 0.5
                    && abs(record.endDate.timeIntervalSince(end)) < 0.5
            }
            let actualRecord = item.isCompleted
                ? (exactCompletion ?? candidates.max { $0.startDate < $1.startDate })
                : nil
            if let actualRecord {
                consumedRecordIDs.insert(actualRecord.id)
            }
            return PlanCalendarEntry(
                source: .checkIn(item),
                title: item.title,
                note: item.note,
                category: item.category,
                date: actualRecord?.startDate ?? item.scheduledStart,
                scheduleKind: actualRecord?.effectiveScheduleKind
                    ?? item.effectiveScheduleKind,
                status: item.status,
                timingText: actualRecord?.timingSummaryText
                    ?? item.timingSummaryText,
                actualRecord: actualRecord
            )
        }

        result.append(
            contentsOf: records
                .filter { record in
                    !consumedRecordIDs.contains(record.id)
                }
                .map { record in
                    PlanCalendarEntry(
                        source: .record(record),
                        title: record.title,
                        note: record.note,
                        category: record.category,
                        date: record.startDate,
                        scheduleKind: record.effectiveScheduleKind,
                        status: .completed,
                        timingText: record.timingSummaryText,
                        actualRecord: record
                    )
                }
        )

        let calendar = Calendar.current
        let sorted = Self.sorted(result)
        entriesByDay = Dictionary(
            grouping: sorted,
            by: { calendar.startOfDay(for: $0.date) }
        )

        oneTimeCountdownsByDay = Dictionary(
            grouping: countdowns.filter { $0.effectiveRepeatRule == .never },
            by: { calendar.startOfDay(for: $0.targetDate) }
        )
        yearlyCountdownsByMonthAndDay = Dictionary(
            grouping: countdowns.filter { $0.effectiveRepeatRule == .yearly },
            by: {
                calendar.component(.month, from: $0.targetDate) * 100
                    + calendar.component(.day, from: $0.targetDate)
            }
        )
        monthlyCountdownsByDay = Dictionary(
            grouping: countdowns.filter { $0.effectiveRepeatRule == .monthly },
            by: { calendar.component(.day, from: $0.targetDate) }
        )
        weeklyCountdownsByWeekday = Dictionary(
            grouping: countdowns.filter { $0.effectiveRepeatRule == .weekly },
            by: { calendar.component(.weekday, from: $0.targetDate) }
        )

        entries = Self.sorted(
            sorted + countdowns.map {
                Self.countdownEntry(for: $0, on: calendar.startOfDay(for: $0.targetDate))
            }
        )
    }

    func entries(
        on date: Date,
        calendar: Calendar = .current
    ) -> [PlanCalendarEntry] {
        let day = calendar.startOfDay(for: date)
        let monthAndDay = calendar.component(.month, from: day) * 100
            + calendar.component(.day, from: day)
        let dayOfMonth = calendar.component(.day, from: day)
        let weekday = calendar.component(.weekday, from: day)

        var countdowns = oneTimeCountdownsByDay[day] ?? []
        countdowns += yearlyCountdownsByMonthAndDay[monthAndDay] ?? []
        countdowns += monthlyCountdownsByDay[dayOfMonth] ?? []
        countdowns += weeklyCountdownsByWeekday[weekday] ?? []
        countdowns = countdowns.filter {
            calendar.startOfDay(for: $0.targetDate) <= day
        }

        let dynamicEntries = countdowns.map {
            Self.countdownEntry(for: $0, on: day)
        }
        return Self.sorted((entriesByDay[day] ?? []) + dynamicEntries)
    }

    private static func countdownEntry(
        for event: CountdownEvent,
        on date: Date
    ) -> PlanCalendarEntry {
        PlanCalendarEntry(
            source: .countdown(event),
            title: event.title,
            note: "",
            category: .customSummary,
            date: date,
            scheduleKind: .allDay,
            status: .planned,
            timingText: event.dateSummaryText,
            actualRecord: nil
        )
    }

    private static func sorted(_ entries: [PlanCalendarEntry]) -> [PlanCalendarEntry] {
        entries.sorted {
            if $0.date != $1.date { return $0.date < $1.date }
            if $0.isCountdown != $1.isCountdown { return $0.isCountdown }
            if $0.scheduleKind.sortOrder != $1.scheduleKind.sortOrder {
                return $0.scheduleKind.sortOrder < $1.scheduleKind.sortOrder
            }
            return $0.title.localizedStandardCompare($1.title) == .orderedAscending
        }
    }
}

enum PlanCalendarDates {
    static func mondayFirstCalendar(from input: Calendar = .current) -> Calendar {
        var calendar = input
        calendar.firstWeekday = 2
        calendar.minimumDaysInFirstWeek = 4
        return calendar
    }

    static func startOfWeek(
        containing date: Date,
        calendar input: Calendar = .current
    ) -> Date {
        let calendar = mondayFirstCalendar(from: input)
        let startOfDay = calendar.startOfDay(for: date)
        let weekday = calendar.component(.weekday, from: startOfDay)
        let offset = (weekday - calendar.firstWeekday + 7) % 7
        return calendar.date(byAdding: .day, value: -offset, to: startOfDay)
            ?? startOfDay
    }

    static func monthGrid(
        containing date: Date,
        calendar input: Calendar = .current
    ) -> [Date] {
        let calendar = mondayFirstCalendar(from: input)
        guard
            let monthStart = calendar.date(
                from: calendar.dateComponents([.year, .month], from: date)
            )
        else { return [] }

        let weekday = calendar.component(.weekday, from: monthStart)
        let leadingDays = (weekday - calendar.firstWeekday + 7) % 7
        guard
            let gridStart = calendar.date(
                byAdding: .day,
                value: -leadingDays,
                to: monthStart
            )
        else { return [] }

        return (0..<42).compactMap {
            calendar.date(byAdding: .day, value: $0, to: gridStart)
        }
    }
}

private enum CalendarDragAxis {
    case horizontal
    case vertical
}

private struct CalendarEditorRoute: Identifiable {
    let id = UUID()
    let item: CheckInItem?
    let kind: CheckInKind
    let date: Date
}

private struct CalendarDeletionRequest: Identifiable {
    enum Target {
        case record(UUID)
        case checkIn(UUID)
        case countdown(UUID)
    }

    let id = UUID()
    let target: Target
    let title: String
    let message: String
}

struct PlanCalendarView: View {
    @ObservedObject var store: PlanStore

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var mode: PlanCalendarMode = .month
    @State private var anchorDate = Date()
    @State private var selectedDate = Date()
    @State private var editorRoute: CalendarEditorRoute?
    @State private var editingRecord: TimeRecord?
    @State private var editingCountdown: CountdownEvent?
    @State private var pendingDeletion: CalendarDeletionRequest?
    @State private var cachedData = PlanCalendarData(
        items: [],
        records: [],
        countdowns: []
    )
    @State private var dragOffset: CGFloat = 0
    /// Locked on the first decisive movement of a drag. Re-deciding on every
    /// change event meant a wobbling finger flipped between axes and the page
    /// snapped back and forth — the visible "抖动".
    @State private var dragAxis: CalendarDragAxis?
    /// Prevents a second navigation command from interrupting a live transition.
    @State private var isPaging = false
    @State private var pageWidth: CGFloat = 0
    @State private var showsQuickJump = false
#if DEBUG
    @State private var didConfigureQAScenario = false
#endif

    private var calendar: Calendar {
        PlanCalendarDates.mondayFirstCalendar()
    }

    /// Built once per data change instead of once per body evaluation.
    ///
    /// This used to be a computed property, so every frame of a drag rebuilt
    /// and re-sorted every entry in the store. That was the single biggest
    /// cause of the stutter while paging.
    private var data: PlanCalendarData {
        cachedData
    }

    var body: some View {
        ZStack {
            InkWashBackground()

            VStack(spacing: 0) {
                periodHeader
                modePicker
                pagedPeriodContent
            }
        }
        .navigationTitle("日历")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button {
                        presentEditor(kind: .planned)
                    } label: {
                        Label("添加计划", systemImage: "plus")
                    }
                    Button {
                        presentEditor(kind: .completedLog)
                    } label: {
                        Label("记录已做", systemImage: "clock.badge.checkmark")
                    }
                } label: {
                    Image(systemName: "plus")
                }
                .accessibilityLabel("在所选日期添加")
            }
        }
        .sheet(item: $editorRoute) { route in
            CheckInEditorView(
                store: store,
                item: route.item,
                defaultKind: route.kind,
                defaultDate: route.date
            )
            .id(route.id)
        }
        .sheet(item: $editingRecord) { record in
            RecordEditorView(store: store, record: record)
        }
        .sheet(item: $editingCountdown) { event in
            CountdownEditorView(store: store, event: event)
        }
        .sheet(isPresented: $showsQuickJump) {
            CalendarQuickJumpSheet(initialDate: anchorDate) { picked in
                jump(to: picked)
            }
            .presentationDetents([.height(320)])
            .presentationDragIndicator(.visible)
        }
        .alert(item: $pendingDeletion) { request in
            Alert(
                title: Text(request.title),
                message: Text(request.message),
                primaryButton: .destructive(Text("删除")) {
                    performDeletion(request)
                },
                secondaryButton: .cancel(Text("取消"))
            )
        }
        .onAppear {
            rebuildCache()
#if DEBUG
            configureQAScenarioIfNeeded()
#endif
        }
        .onReceive(store.$snapshot) { snapshot in
            cachedData = PlanCalendarData(
                items: snapshot.checkInItems,
                records: snapshot.records,
                countdowns: snapshot.countdowns
            )
        }
    }

    private var periodHeader: some View {
        HStack(spacing: 12) {
            Button {
                shiftPeriod(by: -1)
            } label: {
                Image(systemName: "chevron.left")
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("上一个\(mode.displayName)视图")

            Button {
                showsQuickJump = true
            } label: {
                HStack(spacing: 4) {
                    Text(periodTitle)
                        .font(.headline)
                        .contentTransition(.numericText())
                    Image(systemName: "chevron.down")
                        .font(.system(size: 9, weight: .bold))
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("跳转到指定年月，当前 \(periodTitle)")

            Button("今天") {
                jump(to: Date())
            }
            .font(.caption.weight(.semibold))
            .foregroundStyle(Color.planVermilion)

            Button {
                shiftPeriod(by: 1)
            } label: {
                Image(systemName: "chevron.right")
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("下一个\(mode.displayName)视图")
        }
        .padding(.horizontal, 18)
        .padding(.top, 8)
        .frame(height: 48)
    }

    private var modePicker: some View {
        Picker("日历视角", selection: $mode) {
            ForEach(PlanCalendarMode.allCases) { option in
                Text(option.displayName).tag(option)
            }
        }
        .pickerStyle(.segmented)
        .padding(.horizontal, 18)
        .padding(.vertical, 8)
        .onChange(of: mode) { _, _ in
            anchorDate = selectedDate
        }
        .accessibilityIdentifier("calendar.mode")
    }

    private func monthView(anchor: Date, selected: Date) -> some View {
        ScrollView {
            VStack(spacing: 14) {
                VStack(spacing: 7) {
                    weekdayHeader

                    LazyVGrid(
                        columns: Array(
                            repeating: GridItem(.flexible(), spacing: 2),
                            count: 7
                        ),
                        spacing: 3
                    ) {
                        ForEach(
                            PlanCalendarDates.monthGrid(
                                containing: anchor,
                                calendar: calendar
                            ),
                            id: \.self
                        ) { date in
                            monthDayCell(date, anchor: anchor, selected: selected)
                        }
                    }

                    statusLegend
                        .padding(.top, 3)
                }
                .padding(12)
                .inkPaperSurface(cornerRadius: 18)

                dayAgenda(for: selected)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 28)
        }
        // Once the gesture is committed to paging, the inner scroll
        // view must let go, or the page slides sideways while the
        // content also creeps up and down under the finger.
        .scrollDisabled(dragAxis == .horizontal)
        .accessibilityIdentifier("calendar.month")
    }

    private var weekdayHeader: some View {
        HStack(spacing: 2) {
            ForEach(["一", "二", "三", "四", "五", "六", "日"], id: \.self) {
                Text($0)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity)
            }
        }
    }

    private func monthDayCell(_ date: Date, anchor: Date, selected: Date) -> some View {
        let entries = data.entries(on: date, calendar: calendar)
        let isSelected = calendar.isDate(date, inSameDayAs: selected)
        let isToday = calendar.isDateInToday(date)
        let isDisplayedMonth = calendar.component(.month, from: date)
            == calendar.component(.month, from: anchor)

        return Button {
            selectedDate = date
            if !isDisplayedMonth {
                anchorDate = date
            }
        } label: {
            VStack(spacing: 5) {
                Text("\(calendar.component(.day, from: date))")
                    .font(.subheadline.weight(isSelected || isToday ? .semibold : .regular))
                    .foregroundStyle(
                        isToday
                            ? Color.planVermilion
                            : (isDisplayedMonth ? Color.primary : Color.secondary.opacity(0.48))
                    )
                    .frame(width: 27, height: 27)
                    .background {
                        if isSelected {
                            Circle()
                                .fill(Color.planPrimary.opacity(0.10))
                                .overlay {
                                    Circle()
                                        .stroke(Color.planPrimary.opacity(0.42), lineWidth: 0.8)
                                }
                        }
                    }

                HStack(spacing: 2.5) {
                    // Keep the month cell quiet: two status marks communicate
                    // state, while the remainder is counted explicitly.
                    ForEach(Array(entries.prefix(2)), id: \.id) { entry in
                        CalendarStatusMark(
                            status: entry.status,
                            isCountdown: entry.isCountdown,
                            tint: indicatorColor(for: entry)
                        )
                    }
                    if entries.count > 2 {
                        Text("+\(entries.count - 2)")
                            .font(.system(size: 7, weight: .bold))
                            .foregroundStyle(.secondary)
                    }
                }
                .frame(height: 7)
            }
            .frame(maxWidth: .infinity)
            .frame(height: 49)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(
            "\(date.formatted(.dateTime.month().day()))，\(entries.count) 项"
        )
    }

    /// The legend used to list only two of the four states the dots can show.
    private var statusLegend: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 12) {
                legendItem(title: "待完成", status: .planned, color: .planVermilion)
                legendItem(title: "进行中", status: .inProgress, color: .planSecondary)
                legendItem(title: "已完成", status: .completed, color: .planPrimary)
                legendItem(title: "已跳过", status: .skipped, color: .secondary)
            }

            HStack(spacing: 4) {
                CalendarStatusMark(
                    status: .planned,
                    isCountdown: true,
                    tint: .planSecondary
                )
                Text("倒数日")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func legendItem(
        title: String,
        status: CheckInStatus,
        color: Color
    ) -> some View {
        HStack(spacing: 4) {
            CalendarStatusMark(
                status: status,
                isCountdown: false,
                tint: color
            )
            Text(title)
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(.secondary)
        }
    }

    private func dayAgenda(for day: Date) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("当日安排")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)

            HStack {
                Text(dayTitle(day))
                    .font(.headline)
                Spacer()
                Text("\(data.entries(on: day, calendar: calendar).count) 项")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            let entries = data.entries(on: day, calendar: calendar)
            if entries.isEmpty {
                calendarEmptyState(text: "这一天尚未落墨")
            } else {
                VStack(spacing: 0) {
                    ForEach(entries) { entry in
                        calendarEntryRow(entry)
                        if entry.id != entries.last?.id {
                            InkBrushDivider(animated: false, seed: InkVariant.seed(for: entry.id))
                                .opacity(0.42)
                        }
                    }
                }
                .padding(.horizontal, 13)
                .inkPaperSurface(cornerRadius: 16)
            }
        }
    }

    private func weekView(anchor: Date) -> some View {
        ScrollView {
            LazyVStack(spacing: 10) {
                ForEach(weekDates(for: anchor), id: \.self) { date in
                    let entries = data.entries(on: date, calendar: calendar)
                    VStack(spacing: 0) {
                        Button {
                            selectedDate = date
                            mode = .day
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(shortWeekdayTitle(date))
                                        .font(.headline)
                                    Text(shortMonthDayTitle(date))
                                        .font(.caption)
                                        .foregroundStyle(
                                            calendar.isDateInToday(date)
                                                ? Color.planVermilion
                                                : Color.secondary
                                        )
                                }
                                Spacer()
                                Text(entries.isEmpty ? "留白" : "\(entries.count) 项")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                Image(systemName: "chevron.right")
                                    .font(.caption2.weight(.bold))
                                    .foregroundStyle(.tertiary)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .padding(.vertical, 11)

                        if !entries.isEmpty {
                            InkBrushDivider(
                                animated: false,
                                seed: InkVariant.seed(for: date.description)
                            )
                            .opacity(0.42)
                            ForEach(entries) { entry in
                                calendarEntryRow(entry)
                            }
                        }
                    }
                    .padding(.horizontal, 14)
                    .inkPaperSurface(cornerRadius: 16)
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 28)
        }
        // Once the gesture is committed to paging, the inner scroll
        // view must let go, or the page slides sideways while the
        // content also creeps up and down under the finger.
        .scrollDisabled(dragAxis == .horizontal)
        .accessibilityIdentifier("calendar.week")
    }

    private func dayView(selected: Date) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                let dayEntries = data.entries(on: selected, calendar: calendar)
                if dayEntries.isEmpty {
                    calendarEmptyState(text: "今日留白")
                        .padding(.top, 42)
                } else {
                    ForEach(ScheduleKind.allCases) { scheduleKind in
                        let entries = dayEntries.filter {
                            $0.scheduleKind == scheduleKind
                        }
                        if !entries.isEmpty {
                            VStack(alignment: .leading, spacing: 8) {
                                Label(
                                    scheduleKind.displayName,
                                    systemImage: scheduleKind.symbolName
                                )
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(.secondary)

                                VStack(spacing: 0) {
                                    ForEach(entries) { entry in
                                        calendarEntryRow(entry)
                                        if entry.id != entries.last?.id {
                                            InkBrushDivider(animated: false, seed: InkVariant.seed(for: entry.id))
                                                .opacity(0.42)
                                        }
                                    }
                                }
                                .padding(.horizontal, 13)
                                .inkPaperSurface(cornerRadius: 16)
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 28)
        }
        // Once the gesture is committed to paging, the inner scroll
        // view must let go, or the page slides sideways while the
        // content also creeps up and down under the finger.
        .scrollDisabled(dragAxis == .horizontal)
        .accessibilityIdentifier("calendar.day")
    }

    private func calendarEntryRow(_ entry: PlanCalendarEntry) -> some View {
        HStack(spacing: 2) {
            Button {
                open(entry)
            } label: {
                HStack(spacing: 11) {
                    Image(systemName: entry.statusSymbol)
                        .font(.subheadline)
                        .foregroundStyle(indicatorColor(for: entry))
                        .frame(width: 22)

                    VStack(alignment: .leading, spacing: 3) {
                        Text(entry.title)
                            .font(.body.weight(.medium))
                            .foregroundStyle(
                                entry.isCompleted ? Color.secondary : Color.primary
                            )
                            .lineLimit(2)
                            .strikethrough(entry.status == .skipped, color: .secondary)
                        HStack(spacing: 5) {
                            Text(entry.timingText)
                            Text("·")
                            Text(entry.isCountdown ? "倒数日" : entry.category.displayName)
                        }
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    }

                    Spacer(minLength: 6)

                    Text(entry.statusText)
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(indicatorColor(for: entry))
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Menu {
                calendarEntryActions(entry)
            } label: {
                Image(systemName: "ellipsis")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("\(entry.title)的更多操作")
        }
        .padding(.vertical, 10)
        .contextMenu {
            calendarEntryActions(entry)
        }
    }

    @ViewBuilder
    private func calendarEntryActions(_ entry: PlanCalendarEntry) -> some View {
        switch entry.source {
        case let .checkIn(item):
            Button {
                openPlan(item)
            } label: {
                Label("修改计划", systemImage: "pencil")
            }

            if let record = entry.actualRecord {
                Button {
                    editingRecord = record
                } label: {
                    Label(
                        entry.isPomodoroRecord ? "修改番茄钟记录" : "修改实际记录",
                        systemImage: "clock.arrow.circlepath"
                    )
                }

                Button(role: .destructive) {
                    requestRecordDeletion(record, linkedToPlan: true)
                } label: {
                    Label(
                        entry.isPomodoroRecord ? "删除番茄钟记录" : "删除实际记录",
                        systemImage: "trash"
                    )
                }
            }

            if item.status == .completed || item.status == .skipped {
                Button {
                    store.toggleCheckIn(id: item.id)
                } label: {
                    Label("恢复为未完成", systemImage: "arrow.uturn.backward")
                }
            }

            Button(role: .destructive) {
                requestPlanDeletion(item)
            } label: {
                Label("删除计划", systemImage: "trash")
            }

        case let .record(record):
            Button {
                editingRecord = record
            } label: {
                Label(
                    entry.isPomodoroRecord ? "修改番茄钟记录" : "修改记录",
                    systemImage: "pencil"
                )
            }

            Button(role: .destructive) {
                requestRecordDeletion(record, linkedToPlan: false)
            } label: {
                Label(
                    entry.isPomodoroRecord ? "删除番茄钟记录" : "删除记录",
                    systemImage: "trash"
                )
            }

        case let .countdown(event):
            Button {
                editingCountdown = event
            } label: {
                Label("修改倒数日", systemImage: "pencil")
            }

            Button(role: .destructive) {
                requestCountdownDeletion(event)
            } label: {
                Label("删除倒数日", systemImage: "trash")
            }
        }
    }

    private func calendarEmptyState(text: String) -> some View {
        VStack(spacing: 8) {
            InkBrushMedallion(symbol: "calendar", tint: .planPrimary, size: 38)
            Text(text)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 28)
    }

    private func weekDates(for anchor: Date) -> [Date] {
        let start = PlanCalendarDates.startOfWeek(
            containing: anchor,
            calendar: calendar
        )
        return (0..<7).compactMap {
            calendar.date(byAdding: .day, value: $0, to: start)
        }
    }

    private var periodTitle: String {
        switch mode {
        case .month:
            return Self.monthFormatter.string(from: anchorDate)
        case .week:
            let dates = weekDates(for: anchorDate)
            guard let first = dates.first, let last = dates.last else { return "" }
            return "\(Self.shortDateFormatter.string(from: first))—\(Self.shortDateFormatter.string(from: last))"
        case .day:
            return dayTitle(selectedDate)
        }
    }

    /// Horizontal paging for the period.
    ///
    /// Previously a swipe just mutated `anchorDate` inside `withAnimation`, so
    /// the grid reflowed in place and the change read as a vertical shuffle —
    /// the gesture and the motion pointed in different directions. Now the
    /// period is identified by `periodIdentity` and slides in from the edge the
    /// swipe came from, and the drag itself tracks the finger.
    /// A three-page carousel that tracks the finger exactly.
    ///
    /// The previous attempt slid a single page and moved it at half the drag
    /// distance, so the period never travelled with the finger and there was
    /// nothing behind it. All three lightweight, indexed pages stay mounted so
    /// the first horizontal movement does not have to construct two full page
    /// hierarchies in the middle of a frame.
    private var pagedPeriodContent: some View {
        GeometryReader { proxy in
            let width = proxy.size.width
            HStack(spacing: 0) {
                periodPage(offsetBy: -1)
                    .frame(width: width, height: proxy.size.height)
                periodPage(offsetBy: 0)
                    .frame(width: width, height: proxy.size.height)
                periodPage(offsetBy: 1)
                    .frame(width: width, height: proxy.size.height)
            }
            .frame(width: width, alignment: .leading)
            .offset(x: -width + dragOffset)
            .contentShape(Rectangle())
            .simultaneousGesture(pagingGesture(width: width))
            .onAppear { pageWidth = width }
            .onChange(of: width) { _, newValue in pageWidth = newValue }
        }
        .clipped()
    }

    @ViewBuilder
    private func periodPage(offsetBy steps: Int) -> some View {
        let anchor = shiftedDate(anchorDate, by: steps)
        switch mode {
        case .month:
            monthView(anchor: anchor, selected: shiftedDate(selectedDate, by: steps))
        case .week:
            weekView(anchor: anchor)
        case .day:
            dayView(selected: shiftedDate(selectedDate, by: steps))
        }
    }

    private func shiftedDate(_ date: Date, by steps: Int) -> Date {
        guard steps != 0 else { return date }
        let component: Calendar.Component = mode == .month ? .month : .day
        let amount = mode == .week ? steps * 7 : steps
        return calendar.date(byAdding: component, value: amount, to: date) ?? date
    }

    private func pagingGesture(width: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 12)
            .onChanged { value in
                if dragAxis == nil {
                    // Decide once, on the first movement large enough to mean
                    // something, then stay on that axis for the whole gesture.
                    let horizontal = abs(value.translation.width)
                    let vertical = abs(value.translation.height)
                    guard max(horizontal, vertical) > 12 else { return }
                    dragAxis = horizontal > vertical ? .horizontal : .vertical
                    if dragAxis == .horizontal {
                        isPaging = true
                    }
                }
                guard dragAxis == .horizontal else { return }
                dragOffset = value.translation.width
            }
            .onEnded { value in
                defer { dragAxis = nil }
                guard dragAxis == .horizontal else { return }

                // Flicks count even when short, so a quick swipe still turns
                // the page instead of springing back.
                let projected = value.predictedEndTranslation.width
                let shouldTurn = abs(projected) > width * 0.32
                let direction = projected < 0 ? 1 : -1

                guard shouldTurn else {
                    if reduceMotion {
                        dragOffset = 0
                        isPaging = false
                    } else {
                        withAnimation(
                            .interactiveSpring(response: 0.26, dampingFraction: 0.9),
                            completionCriteria: .logicallyComplete
                        ) {
                            dragOffset = 0
                        } completion: {
                            isPaging = false
                        }
                    }
                    return
                }

                guard !reduceMotion else {
                    commitShift(by: direction)
                    dragOffset = 0
                    isPaging = false
                    return
                }

                withAnimation(
                    .interactiveSpring(response: 0.30, dampingFraction: 0.92),
                    completionCriteria: .logicallyComplete
                ) {
                    dragOffset = direction > 0 ? -width : width
                } completion: {
                    // Swap the anchor underneath without animating, so the
                    // carousel silently re-centres on the new period.
                    var transaction = Transaction()
                    transaction.disablesAnimations = true
                    withTransaction(transaction) {
                        commitShift(by: direction)
                        dragOffset = 0
                        isPaging = false
                    }
                }
            }
    }

    private func commitShift(by steps: Int) {
        anchorDate = shiftedDate(anchorDate, by: steps)
        selectedDate = shiftedDate(selectedDate, by: steps)
    }

    private func shiftPeriod(by value: Int) {
        let component: Calendar.Component
        let amount: Int
        switch mode {
        case .month:
            component = .month
            amount = value
        case .week:
            component = .day
            amount = value * 7
        case .day:
            component = .day
            amount = value
        }
        guard calendar.date(byAdding: component, value: amount, to: anchorDate) != nil
        else { return }
        animatePage(by: value)
    }

    /// Runs the same carousel the swipe does, so the arrows move the period
    /// sideways instead of reflowing the grid in place — which read as the
    /// content jumping up and down.
    private func animatePage(by steps: Int) {
        let width = pageWidth
        guard width > 0 else {
            commitShift(by: steps)
            return
        }
        guard !isPaging else { return }
        guard !reduceMotion else {
            commitShift(by: steps)
            return
        }
        isPaging = true
        withAnimation(
            .interactiveSpring(response: 0.30, dampingFraction: 0.92),
            completionCriteria: .logicallyComplete
        ) {
            dragOffset = steps > 0 ? -width : width
        } completion: {
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction) {
                commitShift(by: steps)
                dragOffset = 0
                isPaging = false
            }
        }
    }

    /// Jumps straight to a month without swiping through everything between.
    private func jump(to date: Date) {
        if reduceMotion {
            anchorDate = date
            selectedDate = date
        } else {
            withAnimation(.easeInOut(duration: 0.22)) {
                anchorDate = date
                selectedDate = date
            }
        }
    }

    private func indicatorColor(for entry: PlanCalendarEntry) -> Color {
        if case let .countdown(event) = entry.source {
            return PlanColorName.color(for: event.colorName)
        }
        switch entry.status {
        case .planned: return .planVermilion
        case .inProgress: return .planSecondary
        case .completed: return .planPrimary
        case .skipped: return .secondary
        }
    }

    private func presentEditor(kind: CheckInKind) {
        editorRoute = CalendarEditorRoute(
            item: nil,
            kind: kind,
            date: selectedDate
        )
    }

    private func open(_ entry: PlanCalendarEntry) {
        switch entry.source {
        case let .checkIn(item):
            openPlan(item)
        case let .record(record):
            editingRecord = record
        case let .countdown(event):
            editingCountdown = event
        }
    }

    private func openPlan(_ item: CheckInItem) {
        editorRoute = CalendarEditorRoute(
            item: item,
            kind: item.kind,
            date: item.scheduledStart
        )
    }

    private func requestRecordDeletion(
        _ record: TimeRecord,
        linkedToPlan: Bool
    ) {
        let isPomodoro = record.note.contains("番茄钟")
        pendingDeletion = CalendarDeletionRequest(
            target: .record(record.id),
            title: isPomodoro ? "删除番茄钟记录？" : "删除实际记录？",
            message: linkedToPlan
                ? "实际记录将被删除，对应计划会恢复为未完成；若它生成了下一次重复计划，也会撤回该次生成。"
                : "这条实际记录会从日历和总结中删除，此操作无法撤销。"
        )
    }

    private func requestPlanDeletion(_ item: CheckInItem) {
        pendingDeletion = CalendarDeletionRequest(
            target: .checkIn(item.id),
            title: "删除计划？",
            message: "计划及其关联的实际记录会一并删除，此操作无法撤销。"
        )
    }

    private func requestCountdownDeletion(_ event: CountdownEvent) {
        pendingDeletion = CalendarDeletionRequest(
            target: .countdown(event.id),
            title: "删除倒数日？",
            message: "这个倒数日会从倒数日列表和日历中删除，此操作无法撤销。"
        )
    }

    private func performDeletion(_ request: CalendarDeletionRequest) {
        switch request.target {
        case let .record(id):
            store.deleteRecord(id: id)
        case let .checkIn(id):
            store.deleteCheckIn(id: id)
        case let .countdown(id):
            store.deleteCountdown(id: id)
        }
    }

    private func dayTitle(_ date: Date) -> String {
        Self.dayFormatter.string(from: date)
    }

    private func shortWeekdayTitle(_ date: Date) -> String {
        Self.weekdayFormatter.string(from: date)
    }

    private func shortMonthDayTitle(_ date: Date) -> String {
        Self.shortDateFormatter.string(from: date)
    }

    private static let monthFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_Hans_CN")
        formatter.dateFormat = "yyyy年M月"
        return formatter
    }()

    private static let shortDateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_Hans_CN")
        formatter.dateFormat = "M月d日"
        return formatter
    }()

    private static let dayFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_Hans_CN")
        formatter.dateFormat = "M月d日 EEEE"
        return formatter
    }()

    private static let weekdayFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_Hans_CN")
        formatter.dateFormat = "EEEE"
        return formatter
    }()

#if DEBUG
    private func configureQAScenarioIfNeeded() {
        guard
            !didConfigureQAScenario,
            let scenario = ProcessInfo.processInfo.environment["MOJI_QA_SCENARIO"],
            [
                "calendar-month",
                "calendar-week",
                "calendar-day",
                "calendar-record-delete"
            ].contains(scenario)
        else { return }
        didConfigureQAScenario = true

        if scenario == "calendar-week" { mode = .week }
        if ["calendar-day", "calendar-record-delete"].contains(scenario) {
            mode = .day
        }

        let today = calendar.startOfDay(for: Date())
        let tomorrow = calendar.date(byAdding: .day, value: 1, to: today) ?? today
        let later = calendar.date(byAdding: .day, value: 3, to: today) ?? today
        anchorDate = today
        selectedDate = today

        if !store.checkInItems.contains(where: { $0.title == "日历验收：完成提案" }) {
            store.saveCheckIn(
                CheckInItem(
                    title: "日历验收：完成提案",
                    category: .work,
                    scheduledStart: today,
                    plannedMinutes: 25,
                    status: .completed,
                    completedAt: today,
                    scheduleKind: .allDay,
                    plannedDurationEnabled: false
                )
            )
        }
        if !store.checkInItems.contains(where: { $0.title == "日历验收：上午复习" }) {
            store.saveCheckIn(
                CheckInItem(
                    title: "日历验收：上午复习",
                    category: .study,
                    scheduledStart: calendar.date(
                        bySettingHour: 9,
                        minute: 0,
                        second: 0,
                        of: tomorrow
                    ) ?? tomorrow,
                    plannedMinutes: 25,
                    scheduleKind: .morning,
                    plannedDurationEnabled: false
                )
            )
        }
        if !store.checkInItems.contains(where: { $0.title == "日历验收：发布版本" }) {
            store.saveCheckIn(
                CheckInItem(
                    title: "日历验收：发布版本",
                    category: .work,
                    scheduledStart: calendar.date(
                        bySettingHour: 16,
                        minute: 30,
                        second: 0,
                        of: later
                    ) ?? later,
                    plannedMinutes: 45,
                    scheduleKind: .exactTime,
                    plannedDurationEnabled: true
                )
            )
        }
        if !store.countdowns.contains(where: { $0.title == "日历验收：倒数日" }) {
            store.saveCountdown(
                CountdownEvent(
                    title: "日历验收：倒数日",
                    targetDate: today,
                    symbolName: "flag.fill",
                    colorName: "vermilion",
                    isPinned: true
                )
            )
        }

        let pomodoroRecord: TimeRecord
        if let existing = store.records.first(where: {
            $0.title == "日历验收：误触番茄钟"
        }) {
            pomodoroRecord = existing
        } else {
            let start = calendar.date(
                bySettingHour: 10,
                minute: 0,
                second: 0,
                of: today
            ) ?? today
            pomodoroRecord = TimeRecord(
                title: "日历验收：误触番茄钟",
                category: .study,
                startDate: start,
                endDate: start.addingTimeInterval(25 * 60),
                note: "由番茄钟完成"
            )
            store.saveRecord(pomodoroRecord)
        }

        if scenario == "calendar-record-delete" {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.7) {
                requestRecordDeletion(pomodoroRecord, linkedToPlan: false)
            }
        }
    }
#endif
}

/// Year and month wheels plus a "本月" shortcut, so reaching a distant date is
/// one gesture instead of repeated swiping.
private struct CalendarQuickJumpSheet: View {
    let initialDate: Date
    let onPick: (Date) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var year: Int
    @State private var month: Int

    private let years: [Int]

    init(initialDate: Date, onPick: @escaping (Date) -> Void) {
        self.initialDate = initialDate
        self.onPick = onPick
        let calendar = Calendar.current
        let currentYear = calendar.component(.year, from: Date())
        years = Array((currentYear - 8)...(currentYear + 8))
        _year = State(initialValue: calendar.component(.year, from: initialDate))
        _month = State(initialValue: calendar.component(.month, from: initialDate))
    }

    var body: some View {
        NavigationStack {
            ZStack {
                InkWashBackground()

                VStack(spacing: 12) {
                    HStack(spacing: 0) {
                        Picker("年", selection: $year) {
                            ForEach(years, id: \.self) { value in
                                Text(verbatim: "\(value) 年").tag(value)
                            }
                        }
                        .pickerStyle(.wheel)
                        .frame(maxWidth: .infinity)

                        Picker("月", selection: $month) {
                            ForEach(1...12, id: \.self) { value in
                                Text("\(value) 月").tag(value)
                            }
                        }
                        .pickerStyle(.wheel)
                        .frame(maxWidth: .infinity)
                    }

                    Button("回到本月") {
                        let now = Date()
                        year = Calendar.current.component(.year, from: now)
                        month = Calendar.current.component(.month, from: now)
                    }
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.planVermilion)
                }
                .padding(.horizontal, 18)
            }
            .navigationTitle("跳转到")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("跳转") {
                        var components = DateComponents()
                        components.year = year
                        components.month = month
                        // Keep the day the user was already looking at when it
                        // exists in the target month.
                        let day = Calendar.current.component(.day, from: initialDate)
                        let probe = Calendar.current.date(from: components) ?? initialDate
                        let range = Calendar.current.range(of: .day, in: .month, for: probe)
                        components.day = min(day, range?.count ?? day)
                        if let target = Calendar.current.date(from: components) {
                            onPick(Calendar.current.startOfDay(for: target))
                        }
                        dismiss()
                    }
                    .fontWeight(.semibold)
                }
            }
        }
    }
}


extension PlanCalendarView {
    fileprivate func rebuildCache() {
        cachedData = PlanCalendarData(
            items: store.checkInItems,
            records: store.records,
            countdowns: store.countdowns
        )
    }
}
