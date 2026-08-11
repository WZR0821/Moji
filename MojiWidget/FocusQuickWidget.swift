import AppIntents
import SwiftUI
import WidgetKit

// MARK: - Pickable plans

struct PlanEntity: AppEntity {
    static var typeDisplayRepresentation = TypeDisplayRepresentation(name: "计划")
    static var defaultQuery = PlanEntityQuery()

    var id: UUID
    var title: String
    var detail: String

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(title)", subtitle: "\(detail)")
    }
}

enum PlanEntityCatalog {
    /// Plans worth pinning to a widget: today and later, plus the last few days
    /// so a plan pinned yesterday does not vanish from the picker overnight.
    static func plans(now: Date = Date()) -> [CheckInItem] {
        let calendar = Calendar.current
        let earliest = calendar.date(byAdding: .day, value: -7, to: now) ?? now
        return SharedPersistence.load().checkInItems
            .filter { $0.kind == .planned && $0.scheduledStart >= earliest }
            .sorted { $0.scheduledStart < $1.scheduledStart }
            .prefix(60)
            .map { $0 }
    }

    static func entity(for plan: CheckInItem) -> PlanEntity {
        PlanEntity(
            id: plan.id,
            title: plan.title,
            detail: plan.timingSummaryText
        )
    }
}

struct PlanEntityQuery: EntityQuery {
    func entities(for identifiers: [UUID]) async throws -> [PlanEntity] {
        PlanEntityCatalog.plans()
            .filter { identifiers.contains($0.id) }
            .map(PlanEntityCatalog.entity)
    }

    func suggestedEntities() async throws -> [PlanEntity] {
        PlanEntityCatalog.plans().map(PlanEntityCatalog.entity)
    }
}

// MARK: - Configuration

struct SelectPlansIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "选择计划"
    static var description = IntentDescription("最多钉住三项计划；留空则显示今天最近的三项。")

    @Parameter(title: "第一项")
    var first: PlanEntity?

    @Parameter(title: "第二项")
    var second: PlanEntity?

    @Parameter(title: "第三项")
    var third: PlanEntity?

    /// A pinned plan gets completed and then the widget would sit half empty
    /// for the rest of the day; filling the remaining rows keeps it useful.
    @Parameter(title: "空位补今日计划", default: true)
    var fillsWithToday: Bool

    var selectedIdentifiers: [UUID] {
        [first?.id, second?.id, third?.id].compactMap { $0 }
    }
}

// MARK: - Timeline

struct PlanBoardEntry: TimelineEntry {
    let date: Date
    let plans: [CheckInItem]
    let pendingCount: Int
    let quickPlanPresets: [QuickPlanPreset]
}

struct PlanBoardProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> PlanBoardEntry {
        entry(from: .preview, configuration: SelectPlansIntent(), at: Date())
    }

    func snapshot(
        for configuration: SelectPlansIntent,
        in context: Context
    ) async -> PlanBoardEntry {
        let now = Date()
        guard !context.isPreview else {
            return entry(from: .preview, configuration: configuration, at: now)
        }
        return entry(from: SharedPersistence.load(), configuration: configuration, at: now)
    }

    func timeline(
        for configuration: SelectPlansIntent,
        in context: Context
    ) async -> Timeline<PlanBoardEntry> {
        let now = Date()
        let snapshot = SharedPersistence.load()
        let intervalRefresh = now.addingTimeInterval(15 * 60)
        let nextDay = Calendar.current.date(
            byAdding: .day,
            value: 1,
            to: Calendar.current.startOfDay(for: now)
        )?.addingTimeInterval(1)
        // Never let yesterday's board linger into today.
        let refreshDate = min(intervalRefresh, nextDay ?? intervalRefresh)
        return Timeline(
            entries: [entry(from: snapshot, configuration: configuration, at: now)],
            policy: .after(refreshDate)
        )
    }

    private func entry(
        from snapshot: PlanSnapshot,
        configuration: SelectPlansIntent,
        at now: Date
    ) -> PlanBoardEntry {
        let today = WidgetSelectionEngine.todayPlans(in: snapshot, now: now)
        return PlanBoardEntry(
            date: now,
            plans: WidgetSelectionEngine.plans(
                selecting: configuration.selectedIdentifiers,
                fillsWithToday: configuration.fillsWithToday,
                in: snapshot,
                now: now
            ),
            pendingCount: today.filter { !$0.isCompleted }.count,
            quickPlanPresets: QuickPlanPresetStore.load()
        )
    }
}

// MARK: - Widget

struct PlanBoardWidget: Widget {
    // The quick-plan widget's identity, so existing placements survive.
    let kind = "MojiQuickPlanWidget"

    var body: some WidgetConfiguration {
        AppIntentConfiguration(
            kind: kind,
            intent: SelectPlansIntent.self,
            provider: PlanBoardProvider()
        ) { entry in
            PlanBoardView(entry: entry)
                .containerBackground(for: .widget) {
                    WidgetInkBackground()
                }
        }
        .configurationDisplayName("Moji · 计划")
        .description("显示三项计划，可直接勾完、打开编辑或快速添一项。")
        .supportedFamilies([.systemLarge])
        .contentMarginsDisabled()
    }
}

private struct PlanBoardView: View {
    let entry: PlanBoardEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                WidgetInkGlyph(symbol: "checklist", accent: .planPrimary)
                    .frame(width: 22, height: 22)
                Text("计划")
                    .font(.caption.weight(.semibold))
                Spacer()
                Text(
                    entry.pendingCount == 0
                        ? "今日已了"
                        : "今日待完成 \(entry.pendingCount) 项"
                )
                .font(.caption)
                .foregroundStyle(.secondary)
            }

            WidgetInkStroke(accent: .planPrimary)
                .frame(height: 5)

            if entry.plans.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    Text("今天还没有计划")
                        .font(.headline)
                    Text("点下面添一项，或长按小组件钉住常看的计划。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            } else {
                // A 4×4 widget is much taller than three compact rows, so let the
                // rows share the slack instead of leaving a hole above the
                // quick-add strip.
                VStack(spacing: 10) {
                    ForEach(entry.plans) { plan in
                        planRow(plan)
                            .frame(maxHeight: .infinity)
                    }
                }
                .frame(maxHeight: .infinity)
            }

            HStack(spacing: 7) {
                ForEach(entry.quickPlanPresets) { preset in
                    quickAddButton(preset: preset)
                }
            }
        }
        .padding(16)
    }

    /// Two targets in one row: the circle completes without leaving the Home
    /// Screen, the rest of the row opens that plan's editor in the app. A
    /// widget cannot host a text field, so "编辑" has to mean "开到那一页".
    private func planRow(_ plan: CheckInItem) -> some View {
        HStack(spacing: 10) {
            Button(intent: ToggleQuickPlanIntent(planID: plan.id)) {
                Image(
                    systemName: plan.isCompleted
                        ? "checkmark.circle.fill"
                        : (plan.status == .inProgress ? "play.circle.fill" : "circle")
                )
                .font(.title3)
                .foregroundStyle(plan.category.color)
                .frame(width: 30, height: 30)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(plan.title)，\(plan.isCompleted ? "点击恢复" : "点击完成")")

            planLink(plan)
        }
        .padding(.vertical, 7)
        .padding(.horizontal, 10)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .background {
            WidgetBrushButtonBackground(accent: plan.category.color)
        }
    }

    @ViewBuilder
    private func planLink(_ plan: CheckInItem) -> some View {
        let content = HStack(spacing: 6) {
            VStack(alignment: .leading, spacing: 2) {
                Text(plan.title)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(plan.isCompleted ? Color.secondary : Color.primary)
                    .strikethrough(plan.isCompleted, color: .secondary)
                    .lineLimit(1)
                Text(plan.isCompleted ? "已完成 · 点开可改" : plan.timingSummaryText)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 4)
            // Same mark the checklist uses for a plan that has been waiting
            // since an earlier day: the seal carries the day count, the plan's
            // own line stays untouched.
            if
                !plan.isCompleted,
                let days = CarryOverSeal.daysLate(
                    scheduledStart: plan.scheduledStart,
                    on: entry.date
                )
            {
                CarryOverDaySeal(daysLate: days, size: 18, animated: false)
            }
            Image(systemName: "square.and.pencil")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .contentShape(Rectangle())

        if let url = MojiDeepLink.planEditor(plan.id) {
            Link(destination: url) { content }
                .accessibilityLabel("编辑 \(plan.title)")
        } else {
            content
        }
    }

    private func quickAddButton(preset: QuickPlanPreset) -> some View {
        Button(intent: AddQuickPlanIntent(presetID: preset.id)) {
            Label(preset.buttonTitle, systemImage: preset.symbolName)
                .font(.caption.weight(.semibold))
                .foregroundStyle(preset.category?.color ?? Color.planPrimary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .background {
                    WidgetBrushButtonBackground(
                        accent: preset.category?.color ?? .planPrimary
                    )
                }
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Shared ink components

struct WidgetInkBackground: View {
    var body: some View {
        GeometryReader { proxy in
            ZStack {
                Color.planBackground
                Canvas { context, size in
                    context.drawLayer { ink in
                        ink.addFilter(.blur(radius: 18))
                        ink.fill(
                            Path(
                                ellipseIn: CGRect(
                                    x: size.width * 0.58,
                                    y: -size.height * 0.28,
                                    width: size.width * 0.70,
                                    height: size.width * 0.62
                                )
                            ),
                            with: .color(Color.planPrimary.opacity(0.09))
                        )
                        ink.fill(
                            Path(
                                ellipseIn: CGRect(
                                    x: -size.width * 0.32,
                                    y: size.height * 0.66,
                                    width: size.width * 0.62,
                                    height: size.width * 0.56
                                )
                            ),
                            with: .color(Color.planPrimary.opacity(0.07))
                        )
                    }
                }
                RoundedRectangle(cornerRadius: 4)
                    .stroke(Color.planVermilion.opacity(0.10), lineWidth: 1.5)
                    .frame(width: 22, height: 22)
                    .rotationEffect(.degrees(-7))
                    .position(
                        x: proxy.size.width - 18,
                        y: proxy.size.height - 18
                    )
            }
        }
    }
}

struct WidgetInkGlyph: View {
    let symbol: String
    var accent: Color = .planPrimary

    var body: some View {
        ZStack {
            Circle()
                .trim(from: 0.05, to: 0.84)
                .stroke(
                    accent.opacity(0.72),
                    style: StrokeStyle(lineWidth: 2.2, lineCap: .round)
                )
                .rotationEffect(.degrees(-72))
            Circle()
                .trim(from: 0.70, to: 0.94)
                .stroke(
                    accent.opacity(0.22),
                    style: StrokeStyle(lineWidth: 1.0, lineCap: .round)
                )
                .rotationEffect(.degrees(-64))
            Image(systemName: symbol)
                .font(.system(size: 8, weight: .semibold))
                .foregroundStyle(accent)
        }
    }
}

struct WidgetInkStroke: View {
    var accent: Color = .planPrimary

    var body: some View {
        Canvas { context, size in
            var path = Path()
            path.move(to: CGPoint(x: 0, y: size.height * 0.62))
            path.addCurve(
                to: CGPoint(x: size.width, y: size.height * 0.42),
                control1: CGPoint(x: size.width * 0.28, y: size.height * 0.06),
                control2: CGPoint(x: size.width * 0.66, y: size.height * 0.92)
            )
            context.stroke(
                path,
                with: .color(accent.opacity(0.23)),
                style: StrokeStyle(
                    lineWidth: max(1, size.height * 0.34),
                    lineCap: .round
                )
            )
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

struct WidgetSealMark: View {
    let character: String
    var style: InkSealStyle = .square
    var size: CGFloat = 19

    var body: some View {
        ZStack {
            InkSealBorder(style: style)
            SealScriptText(text: character, size: size * 0.47)
                .padding(size * (style == .doubleSquare ? 0.19 : 0.13))
        }
        .frame(width: size, height: size)
        .rotationEffect(.degrees(sealTilt))
        .accessibilityHidden(true)
    }

    private var sealTilt: Double {
        switch style {
        case .round: return 3
        case .doubleSquare: return -2
        case .weathered: return -5
        case .tall: return 2
        case .square: return -4
        }
    }
}

struct WidgetBrushButtonBackground: View {
    var accent: Color = .planPrimary
    var filled = false

    var body: some View {
        ZStack {
            UnevenRoundedRectangle(
                topLeadingRadius: 10,
                bottomLeadingRadius: 8,
                bottomTrailingRadius: 11,
                topTrailingRadius: 7,
                style: .continuous
            )
            .fill(filled ? accent : accent.opacity(0.08))

            UnevenRoundedRectangle(
                topLeadingRadius: 10,
                bottomLeadingRadius: 8,
                bottomTrailingRadius: 11,
                topTrailingRadius: 7,
                style: .continuous
            )
            .stroke(accent.opacity(filled ? 0.34 : 0.16), lineWidth: 0.8)

            WidgetInkStroke(accent: filled ? .white : accent)
                .frame(height: 4)
                .padding(.horizontal, 8)
                .frame(maxHeight: .infinity, alignment: .bottom)
                .padding(.bottom, 3)
        }
    }
}

struct WidgetInkRing: View {
    var accent: Color = .planPrimary

    var body: some View {
        ZStack {
            Circle()
                .trim(from: 0.04, to: 0.91)
                .stroke(
                    accent.opacity(0.72),
                    style: StrokeStyle(lineWidth: 3.5, lineCap: .round)
                )
                .rotationEffect(.degrees(-82))
            Circle()
                .trim(from: 0.66, to: 0.88)
                .stroke(
                    Color.planPrimary.opacity(0.17),
                    style: StrokeStyle(lineWidth: 1.2, lineCap: .round)
                )
                .rotationEffect(.degrees(-76))
        }
    }
}

private extension PlanSnapshot {
    static var preview: PlanSnapshot {
        PlanSnapshot(
            records: [],
            checkInItems: [
                CheckInItem(
                    title: "完成报告第一章",
                    category: .work,
                    scheduledStart: Date().addingTimeInterval(30 * 60),
                    plannedMinutes: 45
                ),
                CheckInItem(
                    title: "读二十页",
                    category: .study,
                    scheduledStart: Date().addingTimeInterval(3 * 60 * 60),
                    plannedMinutes: 30
                ),
                CheckInItem(
                    title: "晚间散步",
                    category: .study,
                    scheduledStart: Date().addingTimeInterval(6 * 60 * 60),
                    plannedMinutes: 25
                )
            ],
            countdowns: []
        )
    }
}
