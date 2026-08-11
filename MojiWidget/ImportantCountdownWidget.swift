import AppIntents
import SwiftUI
import WidgetKit

// MARK: - Pickable dates

/// One entry in the widget's date picker.
///
/// 倒数日 and 纪念日 are two entity types rather than one filtered list because
/// an `AppEntity` carries exactly one query, and a picker on the 纪念日 widget
/// that offered future dates would let the user build a widget that can never
/// show anything.
struct UpcomingCountdownEntity: AppEntity {
    static var typeDisplayRepresentation = TypeDisplayRepresentation(name: "倒数日")
    static var defaultQuery = UpcomingCountdownQuery()

    var id: UUID
    var title: String
    var detail: String

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(title)", subtitle: "\(detail)")
    }
}

struct AnniversaryEntity: AppEntity {
    static var typeDisplayRepresentation = TypeDisplayRepresentation(name: "纪念日")
    static var defaultQuery = AnniversaryQuery()

    var id: UUID
    var title: String
    var detail: String

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(title)", subtitle: "\(detail)")
    }
}

enum CountdownEntityCatalog {
    /// Date order, closest to today first — the same order the app's 按时间排序
    /// produces, so the picker reads like the list the user just left.
    static func events(past: Bool, now: Date = Date()) -> [CountdownEvent] {
        let matching = SharedPersistence.load().countdowns.filter {
            $0.hasPassed(relativeTo: now) == past
        }
        return CountdownOrdering.sorted(matching, mode: .date, relativeTo: now)
    }

    static func detail(for event: CountdownEvent, now: Date = Date()) -> String {
        let count = CountdownDayCalculator.count(
            to: event.occurrenceDate(relativeTo: now),
            from: now,
            includesToday: event.countsToday
        )
        return "\(event.dateSummaryText) · \(count.fullText)"
    }
}

struct UpcomingCountdownQuery: EntityQuery {
    func entities(for identifiers: [UUID]) async throws -> [UpcomingCountdownEntity] {
        let now = Date()
        return CountdownEntityCatalog.events(past: false, now: now)
            .filter { identifiers.contains($0.id) }
            .map {
                UpcomingCountdownEntity(
                    id: $0.id,
                    title: $0.title,
                    detail: CountdownEntityCatalog.detail(for: $0, now: now)
                )
            }
    }

    func suggestedEntities() async throws -> [UpcomingCountdownEntity] {
        let now = Date()
        return CountdownEntityCatalog.events(past: false, now: now).map {
            UpcomingCountdownEntity(
                id: $0.id,
                title: $0.title,
                detail: CountdownEntityCatalog.detail(for: $0, now: now)
            )
        }
    }
}

struct AnniversaryQuery: EntityQuery {
    func entities(for identifiers: [UUID]) async throws -> [AnniversaryEntity] {
        let now = Date()
        return CountdownEntityCatalog.events(past: true, now: now)
            .filter { identifiers.contains($0.id) }
            .map {
                AnniversaryEntity(
                    id: $0.id,
                    title: $0.title,
                    detail: CountdownEntityCatalog.detail(for: $0, now: now)
                )
            }
    }

    func suggestedEntities() async throws -> [AnniversaryEntity] {
        let now = Date()
        return CountdownEntityCatalog.events(past: true, now: now).map {
            AnniversaryEntity(
                id: $0.id,
                title: $0.title,
                detail: CountdownEntityCatalog.detail(for: $0, now: now)
            )
        }
    }
}

// MARK: - Configuration

/// Three fixed slots rather than a multi-select list: the widget shows three
/// rows in a fixed order, and slots make that order the user's to decide.
/// Leaving them all empty is the useful default — the widget then tracks
/// whatever is nearest, which is what a freshly placed widget should do.
struct SelectCountdownsIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "选择倒数日"
    static var description = IntentDescription("最多选三个要盯着的日子；留空则自动显示离今天最近的三个。")

    @Parameter(title: "第一个")
    var first: UpcomingCountdownEntity?

    @Parameter(title: "第二个")
    var second: UpcomingCountdownEntity?

    @Parameter(title: "第三个")
    var third: UpcomingCountdownEntity?

    var selectedIdentifiers: [UUID] {
        [first?.id, second?.id, third?.id].compactMap { $0 }
    }
}

struct SelectAnniversariesIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "选择纪念日"
    static var description = IntentDescription("最多选三个要回望的日子；留空则自动显示最近发生的三个。")

    @Parameter(title: "第一个")
    var first: AnniversaryEntity?

    @Parameter(title: "第二个")
    var second: AnniversaryEntity?

    @Parameter(title: "第三个")
    var third: AnniversaryEntity?

    var selectedIdentifiers: [UUID] {
        [first?.id, second?.id, third?.id].compactMap { $0 }
    }
}

// MARK: - Timeline

struct CountdownBoardEntry: TimelineEntry {
    let date: Date
    let events: [CountdownEvent]
    let isPastBoard: Bool
}

enum CountdownBoardComposer {
    static func events(
        selecting identifiers: [UUID],
        past: Bool,
        now: Date
    ) -> [CountdownEvent] {
        WidgetSelectionEngine.countdowns(
            selecting: identifiers,
            from: SharedPersistence.load().countdowns,
            past: past,
            now: now
        )
    }

    static func previewEvents(past: Bool, now: Date) -> [CountdownEvent] {
        past
            ? [
                CountdownEvent(
                    title: "初次相遇",
                    targetDate: now.addingTimeInterval(-365 * 24 * 60 * 60),
                    symbolName: "heart.fill"
                ),
                CountdownEvent(
                    title: "搬进新家",
                    targetDate: now.addingTimeInterval(-96 * 24 * 60 * 60),
                    symbolName: "house.fill"
                )
            ]
            : [
                CountdownEvent(
                    title: "项目交付",
                    targetDate: now.addingTimeInterval(2 * 24 * 60 * 60),
                    symbolName: "flag.fill",
                    isPinned: true
                ),
                CountdownEvent(
                    title: "旅行出发",
                    targetDate: now.addingTimeInterval(30 * 24 * 60 * 60),
                    symbolName: "airplane"
                ),
                CountdownEvent(
                    title: "母亲生日",
                    targetDate: now.addingTimeInterval(96 * 24 * 60 * 60),
                    symbolName: "gift.fill"
                )
            ]
    }

}

struct CountdownBoardProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> CountdownBoardEntry {
        let now = Date()
        return CountdownBoardEntry(
            date: now,
            events: CountdownBoardComposer.previewEvents(past: false, now: now),
            isPastBoard: false
        )
    }

    func snapshot(
        for configuration: SelectCountdownsIntent,
        in context: Context
    ) async -> CountdownBoardEntry {
        let now = Date()
        guard !context.isPreview else {
            return CountdownBoardEntry(
                date: now,
                events: CountdownBoardComposer.previewEvents(past: false, now: now),
                isPastBoard: false
            )
        }
        return entry(for: configuration, at: now)
    }

    func timeline(
        for configuration: SelectCountdownsIntent,
        in context: Context
    ) async -> Timeline<CountdownBoardEntry> {
        let now = Date()
        return Timeline(
            entries: [entry(for: configuration, at: now)],
            policy: .after(WidgetSelectionEngine.refreshDate(after: now))
        )
    }

    private func entry(
        for configuration: SelectCountdownsIntent,
        at now: Date
    ) -> CountdownBoardEntry {
        CountdownBoardEntry(
            date: now,
            events: CountdownBoardComposer.events(
                selecting: configuration.selectedIdentifiers,
                past: false,
                now: now
            ),
            isPastBoard: false
        )
    }
}

struct AnniversaryBoardProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> CountdownBoardEntry {
        let now = Date()
        return CountdownBoardEntry(
            date: now,
            events: CountdownBoardComposer.previewEvents(past: true, now: now),
            isPastBoard: true
        )
    }

    func snapshot(
        for configuration: SelectAnniversariesIntent,
        in context: Context
    ) async -> CountdownBoardEntry {
        let now = Date()
        guard !context.isPreview else {
            return CountdownBoardEntry(
                date: now,
                events: CountdownBoardComposer.previewEvents(past: true, now: now),
                isPastBoard: true
            )
        }
        return entry(for: configuration, at: now)
    }

    func timeline(
        for configuration: SelectAnniversariesIntent,
        in context: Context
    ) async -> Timeline<CountdownBoardEntry> {
        let now = Date()
        return Timeline(
            entries: [entry(for: configuration, at: now)],
            policy: .after(WidgetSelectionEngine.refreshDate(after: now))
        )
    }

    private func entry(
        for configuration: SelectAnniversariesIntent,
        at now: Date
    ) -> CountdownBoardEntry {
        CountdownBoardEntry(
            date: now,
            events: CountdownBoardComposer.events(
                selecting: configuration.selectedIdentifiers,
                past: true,
                now: now
            ),
            isPastBoard: true
        )
    }
}

// MARK: - Widgets

struct CountdownBoardWidget: Widget {
    // Kept from the single-date widget it replaces so widgets already on a Home
    // Screen keep their place instead of disappearing on update.
    let kind = "MinutePlanCountdownWidget"

    var body: some WidgetConfiguration {
        AppIntentConfiguration(
            kind: kind,
            intent: SelectCountdownsIntent.self,
            provider: CountdownBoardProvider()
        ) { entry in
            CountdownBoardView(entry: entry)
                .containerBackground(for: .widget) {
                    WidgetInkBackground()
                }
                .widgetURL(MojiDeepLink.countdowns)
        }
        .configurationDisplayName("Moji · 倒数日")
        .description("显示最多三个还没到的日子，锁定屏幕上也能看。")
        .supportedFamilies([
            .systemMedium,
            .accessoryRectangular,
            .accessoryCircular,
            .accessoryInline
        ])
    }
}

struct AnniversaryBoardWidget: Widget {
    let kind = "MojiAnniversaryWidget"

    var body: some WidgetConfiguration {
        AppIntentConfiguration(
            kind: kind,
            intent: SelectAnniversariesIntent.self,
            provider: AnniversaryBoardProvider()
        ) { entry in
            CountdownBoardView(entry: entry)
                .containerBackground(for: .widget) {
                    WidgetInkBackground()
                }
                .widgetURL(MojiDeepLink.countdowns)
        }
        .configurationDisplayName("Moji · 纪念日")
        .description("显示最多三个已经过去的日子，看它们走了多远。")
        .supportedFamilies([.systemMedium])
    }
}

// MARK: - Views

private struct CountdownBoardView: View {
    @Environment(\.widgetFamily) private var family
    let entry: CountdownBoardEntry

    private var heading: String {
        entry.isPastBoard ? "纪念日" : "倒数日"
    }

    private var emptyTitle: String {
        entry.isPastBoard ? "还没有纪念的日期" : "还没有要倒数的日期"
    }

    var body: some View {
        switch family {
        case .accessoryRectangular:
            accessoryRectangularBody
        case .accessoryCircular:
            accessoryCircularBody
        case .accessoryInline:
            accessoryInlineBody
        default:
            mediumBody
        }
    }

    // MARK: Home Screen

    private var mediumBody: some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(spacing: 6) {
                WidgetInkGlyph(
                    symbol: entry.isPastBoard ? "seal" : "hourglass",
                    accent: .planPrimary
                )
                .frame(width: 22, height: 22)
                Text(heading)
                    .font(.caption.weight(.semibold))
                Spacer()
                if let first = entry.events.first, first.isPinned {
                    WidgetSealMark(character: "重", style: .weathered)
                }
            }

            WidgetInkStroke(accent: .planPrimary)
                .frame(height: 5)

            if entry.events.isEmpty {
                Text(emptyTitle)
                    .font(.subheadline)
                Text("打开 Moji 添加，或长按小组件选择要显示的日期。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer(minLength: 0)
            } else {
                VStack(spacing: 5) {
                    ForEach(entry.events) { event in
                        mediumRow(event)
                    }
                }
                Spacer(minLength: 0)
            }
        }
        .padding(16)
    }

    private func mediumRow(_ event: CountdownEvent) -> some View {
        let count = dayCount(for: event)
        let isUrgent = !entry.isPastBoard && event.isUrgent(relativeTo: entry.date)
        return HStack(spacing: 9) {
            Image(systemName: event.symbolName)
                .font(.caption)
                .foregroundStyle(isUrgent ? Color.planVermilion : Color.planPrimary)
                .frame(width: 16)

            VStack(alignment: .leading, spacing: 1) {
                Text(event.title)
                    .font(.subheadline.weight(.medium))
                    .lineLimit(1)
                Text(event.dateSummaryText)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer(minLength: 4)

            VStack(alignment: .trailing, spacing: 0) {
                Text(count.directionText)
                    .font(.system(size: 9))
                    .foregroundStyle(.secondary)
                Text(count.isToday ? "今天" : "\(count.magnitude)")
                    .font(.system(size: 21, weight: .semibold, design: .rounded))
                    .foregroundStyle(isUrgent ? Color.planVermilion : Color.primary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
            }
        }
    }

    // MARK: Lock Screen

    /// Three rows only fit here when each is a single line, so the date drops
    /// away and the day count carries the meaning.
    private var accessoryRectangularBody: some View {
        VStack(alignment: .leading, spacing: 1) {
            if entry.events.isEmpty {
                Text(heading)
                    .font(.headline)
                Text("打开 Moji 添加")
                    .font(.caption2)
            } else {
                ForEach(Array(entry.events.prefix(3))) { event in
                    HStack(spacing: 4) {
                        Text(event.title)
                            .font(.caption.weight(.medium))
                            .lineLimit(1)
                        Spacer(minLength: 2)
                        Text(dayCount(for: event).compactText)
                            .font(.caption.weight(.semibold))
                            .monospacedDigit()
                            .lineLimit(1)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .widgetAccentable()
    }

    private var accessoryCircularBody: some View {
        Group {
            if let event = entry.events.first {
                let count = dayCount(for: event)
                VStack(spacing: -1) {
                    Text(event.title.prefix(2))
                        .font(.system(size: 10, weight: .medium))
                        .lineLimit(1)
                    Text(count.isToday ? "今" : "\(count.magnitude)")
                        .font(.system(size: 20, weight: .semibold, design: .rounded))
                        .minimumScaleFactor(0.5)
                        .lineLimit(1)
                    Text("天")
                        .font(.system(size: 9))
                }
            } else {
                Image(systemName: "hourglass")
            }
        }
        .widgetAccentable()
    }

    private var accessoryInlineBody: some View {
        Group {
            if let event = entry.events.first {
                Text("\(event.title) · \(dayCount(for: event).fullText)")
            } else {
                Text("Moji · 还没有日期")
            }
        }
    }

    private func dayCount(for event: CountdownEvent) -> CountdownDayCount {
        CountdownDayCalculator.count(
            to: event.occurrenceDate(relativeTo: entry.date),
            from: entry.date,
            includesToday: event.countsToday
        )
    }
}
