import SwiftUI

enum CountdownEditorContext: Equatable {
    case countdown
    case anniversary

    var defaultDate: Date {
        let dayOffset = self == .countdown ? 1 : -1
        let date = Calendar.current.date(
            byAdding: .day,
            value: dayOffset,
            to: Date()
        ) ?? Date()
        return Calendar.current.startOfDay(for: date)
    }

    var addTitle: String {
        switch self {
        case .countdown: return "添加倒数日"
        case .anniversary: return "添加纪念日"
        }
    }

    var defaultSymbolName: String {
        switch self {
        case .countdown: return "hourglass"
        case .anniversary: return "seal"
        }
    }
}

struct CountdownEditorView: View {
    @ObservedObject var store: PlanStore
    let event: CountdownEvent?
    let context: CountdownEditorContext

    @Environment(\.dismiss) private var dismiss
    @State private var isTitleFocused = false

    @State private var title: String
    @State private var targetDate: Date
    @State private var repeatRule: CountdownRepeatRule
    @State private var includesToday: Bool
    @State private var isPinned: Bool
    @State private var calendarSyncEnabled: Bool
    @State private var calendarEventIdentifier: String?
    @State private var isSaving = false
    @State private var calendarError: String?

    init(
        store: PlanStore,
        event: CountdownEvent? = nil,
        context: CountdownEditorContext = .countdown
    ) {
        self.store = store
        self.event = event
        self.context = context

        _title = State(initialValue: event?.title ?? "")
        _targetDate = State(initialValue: event?.targetDate ?? context.defaultDate)
        _repeatRule = State(initialValue: event?.effectiveRepeatRule ?? .never)
        _includesToday = State(initialValue: event?.countsToday ?? false)
        _isPinned = State(initialValue: event?.isPinned ?? false)
        _calendarSyncEnabled = State(initialValue: event?.calendarSyncEnabled ?? false)
        _calendarEventIdentifier = State(initialValue: event?.calendarEventIdentifier)
    }

    private var cleanTitle: String {
        title.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var advancedSummary: String {
        var parts = [repeatRule.displayName]
        if includesToday { parts.append("含当天") }
        if isPinned { parts.append("重点") }
        if calendarSyncEnabled || calendarEventIdentifier != nil {
            parts.append("日历")
        }
        return parts.joined(separator: " · ")
    }

    var body: some View {
        NavigationStack {
            ZStack {
                InkWashBackground()

                Form {
                    Section {
                        IMESafeTextField(
                            placeholder: "这个日子叫什么？",
                            text: $title,
                            isFocused: $isTitleFocused,
                            accessibilityIdentifier: "countdown.title"
                        )
                        DatePicker(
                            "日期",
                            selection: $targetDate,
                            displayedComponents: .date
                        )
                    }

                    Section {
                        NavigationLink {
                            CountdownAdvancedSettingsView(
                                repeatRule: $repeatRule,
                                includesToday: $includesToday,
                                isPinned: $isPinned,
                                calendarSyncEnabled: $calendarSyncEnabled,
                                calendarEventIdentifier: calendarEventIdentifier,
                                targetDate: targetDate,
                                title: cleanTitle
                            )
                        } label: {
                            VStack(alignment: .leading, spacing: 3) {
                                Text("更多设置")
                                Text(advancedSummary)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                .inkFormStyle()
            }
            .navigationTitle(event == nil ? context.addTitle : "编辑这个日子")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        IMETextInput.commit {
                            Task { await save() }
                        }
                    }
                    .fontWeight(.semibold)
                    .disabled((cleanTitle.isEmpty && !isTitleFocused) || isSaving)
                }
            }
            .onAppear {
                if event == nil { isTitleFocused = true }
            }
            .alert("无法写入日历", isPresented: Binding(
                get: { calendarError != nil },
                set: { if !$0 { calendarError = nil } }
            )) {
                Button("好", role: .cancel) {}
            } message: {
                Text(calendarError ?? "")
            }
        }
    }

    @MainActor
    private func save() async {
        guard !cleanTitle.isEmpty else { return }
        isSaving = true

        var updated = CountdownEvent(
            id: event?.id ?? UUID(),
            title: cleanTitle,
            targetDate: Calendar.current.startOfDay(for: targetDate),
            symbolName: event?.symbolName ?? context.defaultSymbolName,
            colorName: event?.colorName ?? "vermilion",
            isPinned: isPinned,
            createdAt: event?.createdAt ?? Date(),
            repeatRule: repeatRule,
            includesToday: includesToday,
            calendarSyncEnabled: calendarSyncEnabled || calendarEventIdentifier != nil,
            calendarEventIdentifier: calendarEventIdentifier,
            sortOrder: event?.sortOrder
        )

        if calendarSyncEnabled && calendarEventIdentifier == nil {
            do {
                updated.calendarEventIdentifier = try await CalendarSyncService.shared.addEvent(for: updated)
                calendarEventIdentifier = updated.calendarEventIdentifier
            } catch {
                calendarError = error.localizedDescription
                isSaving = false
                return
            }
        }

        store.saveCountdown(updated)
        dismiss()
    }
}

private struct CountdownAdvancedSettingsView: View {
    @Binding var repeatRule: CountdownRepeatRule
    @Binding var includesToday: Bool
    @Binding var isPinned: Bool
    @Binding var calendarSyncEnabled: Bool

    let calendarEventIdentifier: String?
    let targetDate: Date
    let title: String

    private var previewCount: CountdownDayCount {
        let previewEvent = CountdownEvent(
            title: title,
            targetDate: targetDate,
            repeatRule: repeatRule,
            includesToday: includesToday
        )
        return CountdownDayCalculator.count(
            to: previewEvent.occurrenceDate(),
            includesToday: includesToday
        )
    }

    var body: some View {
        Form {
            Section {
                Picker("重复", selection: $repeatRule) {
                    ForEach(CountdownRepeatRule.allCases) { rule in
                        Text(rule.displayName).tag(rule)
                    }
                }
                Toggle("包含当天（+1）", isOn: $includesToday)
                Toggle("重点标记", isOn: $isPinned)
            } footer: {
                Text("例如生日可设为每年重复；包含当天适合“第几天”的纪念方式。")
            }

            Section {
                LabeledContent(
                    "方式",
                    value: previewCount.isToday
                        ? "今天"
                        : "\(previewCount.directionText)日"
                )
                LabeledContent("显示") {
                    Text(previewCount.fullText)
                        .fontWeight(.semibold)
                        .monospacedDigit()
                }
            } header: {
                Text("计日预览")
            } footer: {
                Text("未来日期自动倒数，过去日期自动正数；重复纪念日按下一次发生日期倒数。")
            }

            Section {
                if calendarEventIdentifier == nil {
                    Toggle("写入 Apple 日历", isOn: $calendarSyncEnabled)
                } else {
                    Label("已写入 Apple 日历", systemImage: "calendar.badge.checkmark")
                        .foregroundStyle(.secondary)
                }
            } footer: {
                Text(
                    calendarEventIdentifier == nil
                        ? "保存时写入为全天事件，并沿用上方重复规则。"
                        : "日历为单向添加；此处后续修改不会覆盖日历中的事件。"
                )
            }
        }
        .inkFormStyle()
        .background { InkWashBackground() }
        .navigationTitle("更多设置")
        .navigationBarTitleDisplayMode(.inline)
    }
}
