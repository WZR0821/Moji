import AppIntents
import SwiftUI
import WidgetKit

/// 说一句添加 on the lock screen: one tap opens Moji on today's list with the
/// field focused and the microphone on. Nothing on it changes, so its
/// timeline is a single entry.
struct MojiSayWidget: Widget {
    let kind = "MojiSayWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: MojiSayProvider()) { _ in
            MojiSayWidgetView()
                .containerBackground(for: .widget) { Color.clear }
                .widgetURL(MojiSayRequest.url)
        }
        .configurationDisplayName("说一句添加")
        .description("在锁屏上点一下，直接说一句话添加计划。")
        .supportedFamilies([.accessoryCircular, .accessoryRectangular])
    }
}

struct MojiSayEntry: TimelineEntry {
    let date: Date
}

struct MojiSayProvider: TimelineProvider {
    func placeholder(in context: Context) -> MojiSayEntry { MojiSayEntry(date: Date()) }

    func getSnapshot(in context: Context, completion: @escaping (MojiSayEntry) -> Void) {
        completion(MojiSayEntry(date: Date()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<MojiSayEntry>) -> Void) {
        completion(Timeline(entries: [MojiSayEntry(date: Date())], policy: .never))
    }
}

private struct MojiSayWidgetView: View {
    @Environment(\.widgetFamily) private var family

    var body: some View {
        switch family {
        case .accessoryRectangular:
            HStack(spacing: 8) {
                ZStack {
                    AccessoryWidgetBackground()
                    Image(systemName: "mic.fill")
                        .font(.system(size: 17, weight: .semibold))
                }
                .frame(width: 36, height: 36)
                .clipShape(Circle())
                VStack(alignment: .leading, spacing: 0) {
                    Text("说一句")
                        .font(.headline)
                    Text("添加计划")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
            }
            .widgetAccentable()
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("说一句添加计划")
        default:
            ZStack {
                AccessoryWidgetBackground()
                VStack(spacing: 1) {
                    Image(systemName: "mic.fill")
                        .font(.system(size: 19, weight: .semibold))
                    Text("说一句")
                        .font(.system(size: 10, weight: .semibold))
                }
            }
            .widgetAccentable()
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("说一句添加")
        }
    }
}

/// 说一句添加 in Control Center, from iOS 18. The intent opens the app, which
/// picks the request up as it becomes active.
@available(iOS 18.0, *)
struct MojiSayControl: ControlWidget {
    static let kind = "MojiSayControl"

    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: Self.kind) {
            ControlWidgetButton(action: MojiSayIntent()) {
                Label("说一句添加", systemImage: "mic")
            }
        }
        .displayName("说一句添加")
        .description("打开墨记，直接说一句话添加计划。")
    }
}
