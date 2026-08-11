import Charts
import SwiftUI

struct WeeklySummaryView: View {
    let records: [TimeRecord]
    let checkInItems: [CheckInItem]
    @Binding var anchorDate: Date

    private var summary: WeekSummary {
        WeeklyAnalytics.summary(records: records, containing: anchorDate)
    }

    private var chartValues: [WeekChartValue] {
        summary.days.flatMap { day in
            [
                WeekChartValue(date: day.date, category: .study, minutes: day.studyMinutes),
                WeekChartValue(date: day.date, category: .work, minutes: day.workMinutes),
                WeekChartValue(
                    date: day.date,
                    category: .customSummary,
                    minutes: day.customMinutes
                )
            ]
        }
    }

    private var checkInSummary: CheckInWeekSummary {
        CheckInAnalytics.week(
            items: checkInItems,
            records: records,
            containing: anchorDate
        )
    }

    private var isCurrentWeek: Bool {
        WeeklyAnalytics.summary(records: [], containing: Date()).startDate == summary.startDate
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            weekNavigation

            HStack(alignment: .lastTextBaseline) {
                Text(DurationText.full(minutes: summary.totalMinutes))
                    .font(.title2.weight(.bold))
                    .monospacedDigit()
                Spacer()
                if !isCurrentWeek {
                    Button("回到本周") { anchorDate = Date() }
                        .font(.caption.weight(.semibold))
                }
            }

            HStack(spacing: 12) {
                summaryMetric(
                    title: "完成计划",
                    value: "\(checkInSummary.completedCount)/\(checkInSummary.plannedCount)",
                    symbol: "checkmark.circle.fill",
                    tint: .planPrimary
                )
                summaryMetric(
                    title: "计划时间",
                    value: DurationText.compact(minutes: checkInSummary.plannedMinutes),
                    symbol: "calendar",
                    tint: .planPrimary
                )
            }

            Chart(chartValues) { value in
                BarMark(
                    x: .value("日期", value.date, unit: .day),
                    y: .value("分钟", value.minutes)
                )
                .foregroundStyle(by: .value("类型", value.category.displayName))
                .cornerRadius(4)
            }
            .chartForegroundStyleScale([
                RecordCategory.study.displayName: RecordCategory.study.color,
                RecordCategory.work.displayName: RecordCategory.work.color,
                RecordCategory.customSummary.displayName: RecordCategory.customSummary.color
            ])
            .chartXAxis {
                AxisMarks(values: summary.days.map(\.date)) { value in
                    AxisValueLabel(
                        format: .dateTime.weekday(.narrow),
                        centered: true
                    )
                    AxisTick().foregroundStyle(.clear)
                    AxisGridLine().foregroundStyle(.clear)
                }
            }
            .chartYAxis {
                AxisMarks(position: .leading) { value in
                    AxisGridLine().foregroundStyle(Color.secondary.opacity(0.15))
                    AxisValueLabel {
                        if let minutes = value.as(Int.self) {
                            Text(DurationText.compact(minutes: minutes))
                        }
                    }
                }
            }
            .frame(height: 180)
            .animation(.easeInOut(duration: 0.35), value: anchorDate)

            HStack(spacing: 12) {
                summaryLegend(category: .study, minutes: summary.studyMinutes)
                summaryLegend(category: .work, minutes: summary.workMinutes)
                summaryLegend(category: .customSummary, minutes: summary.customMinutes)
            }
        }
        .planCard()
    }

    private var weekNavigation: some View {
        HStack {
            Button {
                withAnimation(.easeInOut(duration: 0.24)) {
                    anchorDate = Calendar.mojiISO.date(
                        byAdding: .day,
                        value: -7,
                        to: anchorDate
                    ) ?? anchorDate
                }
            } label: {
                Image(systemName: "chevron.left")
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Spacer()
            VStack(spacing: 2) {
                Text("周总结")
                    .font(.headline)
                Text(weekRangeText)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()

            Button {
                withAnimation(.easeInOut(duration: 0.24)) {
                    anchorDate = Calendar.mojiISO.date(
                        byAdding: .day,
                        value: 7,
                        to: anchorDate
                    ) ?? anchorDate
                }
            } label: {
                Image(systemName: "chevron.right")
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(isCurrentWeek)
            .opacity(isCurrentWeek ? 0.3 : 1)
        }
    }

    private var weekRangeText: String {
        let finalDay = Calendar.mojiISO.date(byAdding: .day, value: -1, to: summary.endDate) ?? summary.endDate
        return "\(summary.startDate.formatted(.dateTime.month().day())) – \(finalDay.formatted(.dateTime.month().day()))"
    }

    private func summaryLegend(category: RecordCategory, minutes: Int) -> some View {
        VStack(spacing: 5) {
            HStack(spacing: 6) {
                Circle()
                    .fill(category.color)
                    .frame(width: 8, height: 8)
                Text(category.displayName)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Text(DurationText.full(minutes: minutes))
                .font(.caption.weight(.semibold))
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.72)
        }
        .frame(maxWidth: .infinity, alignment: .center)
        .multilineTextAlignment(.center)
    }

    private func summaryMetric(
        title: String,
        value: String,
        symbol: String,
        tint: Color
    ) -> some View {
        HStack(spacing: 8) {
            Image(systemName: symbol)
                .foregroundStyle(tint)
            VStack(alignment: .leading, spacing: 1) {
                Text(value)
                    .font(.subheadline.weight(.bold))
                    .monospacedDigit()
                Text(title)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }
        .padding(10)
        .background(Color.primary.opacity(0.04), in: RoundedRectangle(cornerRadius: 12))
    }
}

private struct WeekChartValue: Identifiable {
    let date: Date
    let category: RecordCategory
    let minutes: Int

    var id: String { "\(date.timeIntervalSince1970)-\(category.rawValue)" }
}

struct MonthlySummaryView: View {
    let records: [TimeRecord]
    let checkInItems: [CheckInItem]
    @Binding var anchorDate: Date

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 6), count: 7)

    private var summary: CheckInMonthSummary {
        CheckInAnalytics.month(
            items: checkInItems,
            records: records,
            containing: anchorDate
        )
    }

    private var isCurrentMonth: Bool {
        Calendar.current.isDate(anchorDate, equalTo: Date(), toGranularity: .month)
    }

    private var heatmapCells: [MonthActivityDaySummary?] {
        let weekday = Calendar.mojiISO.component(.weekday, from: summary.startDate)
        let leadingEmptyCount = (weekday + 5) % 7
        return Array(repeating: nil, count: leadingEmptyCount) + summary.days.map(Optional.some)
    }

    private var chartValues: [MonthChartValue] {
        summary.days.flatMap { day in
            [
                MonthChartValue(date: day.date, category: .study, minutes: day.studyMinutes),
                MonthChartValue(date: day.date, category: .work, minutes: day.workMinutes),
                MonthChartValue(
                    date: day.date,
                    category: .customSummary,
                    minutes: day.customMinutes
                )
            ]
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 17) {
            monthNavigation

            HStack(spacing: 10) {
                monthMetric(
                    value: "\(summary.completedCount)/\(summary.plannedCount)",
                    title: "完成计划"
                )
                monthMetric(
                    value: "\(Int(summary.completionRate * 100))%",
                    title: "完成率"
                )
                monthMetric(
                    value: "\(isCurrentMonth ? summary.currentStreak : summary.longestStreak) 天",
                    title: isCurrentMonth ? "当前连续" : "月内最长"
                )
            }

            heatmap
            planActualComparison

            Chart(chartValues) { value in
                BarMark(
                    x: .value("日期", value.date, unit: .day),
                    y: .value("分钟", value.minutes)
                )
                .foregroundStyle(by: .value("类型", value.category.displayName))
            }
            .chartForegroundStyleScale([
                RecordCategory.study.displayName: RecordCategory.study.color,
                RecordCategory.work.displayName: RecordCategory.work.color,
                RecordCategory.customSummary.displayName: RecordCategory.customSummary.color
            ])
            .chartXAxis {
                AxisMarks(values: .stride(by: .day, count: 7)) { value in
                    AxisValueLabel(format: .dateTime.day(), centered: true)
                    AxisTick().foregroundStyle(.clear)
                    AxisGridLine().foregroundStyle(.clear)
                }
            }
            .chartYAxis(.hidden)
            .frame(height: 118)
            .animation(.easeInOut(duration: 0.35), value: anchorDate)

            HStack(spacing: 12) {
                categoryTotal(.study, minutes: summary.studyMinutes)
                categoryTotal(.work, minutes: summary.workMinutes)
                categoryTotal(.customSummary, minutes: summary.customMinutes)
            }
        }
        .planCard()
    }

    private var monthNavigation: some View {
        HStack {
            Button {
                withAnimation(.easeInOut(duration: 0.24)) {
                    anchorDate = Calendar.current.date(
                        byAdding: .month,
                        value: -1,
                        to: anchorDate
                    ) ?? anchorDate
                }
            } label: {
                Image(systemName: "chevron.left")
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Spacer()
            VStack(spacing: 2) {
                Text("月总结")
                    .font(.headline)
                Text(summary.startDate.formatted(.dateTime.year().month(.wide)))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()

            Button {
                withAnimation(.easeInOut(duration: 0.24)) {
                    anchorDate = Calendar.current.date(
                        byAdding: .month,
                        value: 1,
                        to: anchorDate
                    ) ?? anchorDate
                }
            } label: {
                Image(systemName: "chevron.right")
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(isCurrentMonth)
            .opacity(isCurrentMonth ? 0.3 : 1)
        }
    }

    private var heatmap: some View {
        VStack(spacing: 7) {
            LazyVGrid(columns: columns, spacing: 6) {
                ForEach(["一", "二", "三", "四", "五", "六", "日"], id: \.self) { label in
                    Text(label)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity)
                }
            }

            LazyVGrid(columns: columns, spacing: 6) {
                ForEach(heatmapCells.indices, id: \.self) { index in
                    if let day = heatmapCells[index] {
                        Text(day.date.formatted(.dateTime.day()))
                            .font(.caption2.weight(day.checkIn.completedCount > 0 ? .bold : .regular))
                            .foregroundStyle(heatmapForeground(for: day))
                            .frame(maxWidth: .infinity)
                            .aspectRatio(1, contentMode: .fit)
                            .background(
                                heatmapColor(for: day),
                                in: RoundedRectangle(cornerRadius: 6, style: .continuous)
                            )
                            .accessibilityLabel(
                                "\(day.date.formatted(.dateTime.month().day()))，完成 \(day.checkIn.completedCount) 项，共 \(day.checkIn.plannedCount) 项"
                            )
                    } else {
                        Color.clear
                            .frame(maxWidth: .infinity)
                            .aspectRatio(1, contentMode: .fit)
                    }
                }
            }
        }
    }

    private var planActualComparison: some View {
        VStack(spacing: 9) {
            comparisonRow(
                title: "计划",
                minutes: summary.plannedMinutes,
                maximum: max(summary.plannedMinutes, summary.actualMinutes)
            )
            comparisonRow(
                title: "实际",
                minutes: summary.actualMinutes,
                maximum: max(summary.plannedMinutes, summary.actualMinutes)
            )
        }
    }

    private func comparisonRow(title: String, minutes: Int, maximum: Int) -> some View {
        HStack(spacing: 9) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
                .frame(width: 30, alignment: .leading)
            GeometryReader { proxy in
                InkProgressStroke(
                    progress: maximum > 0 ? Double(minutes) / Double(maximum) : 0,
                    tint: .planPrimary,
                    opacity: title == "计划" ? 0.50 : 0.88
                )
                .frame(width: proxy.size.width, height: proxy.size.height)
            }
            .frame(height: 9)
            Text(DurationText.compact(minutes: minutes))
                .font(.caption.weight(.semibold))
                .monospacedDigit()
                .frame(width: 56, alignment: .trailing)
        }
    }

    private func heatmapColor(for day: MonthActivityDaySummary) -> Color {
        if day.checkIn.plannedCount == 0 {
            return Color.primary.opacity(day.actualMinutes > 0 ? 0.18 : 0.035)
        }
        if day.checkIn.completedCount == 0 {
            return Color.primary.opacity(0.08)
        }
        return Color.planPrimary.opacity(0.30 + min(0.60, day.checkIn.completionRate * 0.60))
    }

    private func heatmapForeground(for day: MonthActivityDaySummary) -> Color {
        day.checkIn.completedCount > 0 ? Color.planBackground : Color.primary.opacity(0.72)
    }

    private func monthMetric(value: String, title: String) -> some View {
        VStack(spacing: 4) {
            Text(value)
                .font(.subheadline.weight(.bold))
                .monospacedDigit()
            Text(title)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }

    private func categoryTotal(_ category: RecordCategory, minutes: Int) -> some View {
        VStack(spacing: 5) {
            HStack(spacing: 6) {
                Circle()
                    .fill(category.color)
                    .frame(width: 7, height: 7)
                Text(category.displayName)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Text(DurationText.compact(minutes: minutes))
                .font(.caption.weight(.semibold))
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.72)
        }
        .frame(maxWidth: .infinity, alignment: .center)
        .multilineTextAlignment(.center)
    }
}

private struct MonthChartValue: Identifiable {
    let date: Date
    let category: RecordCategory
    let minutes: Int

    var id: String { "\(date.timeIntervalSince1970)-\(category.rawValue)" }
}
