import SwiftUI

enum PlanQuickField: String, Identifiable {
    case content, date, schedule, category, recurrence, reminder
    var id: String { rawValue }
    var title: String {
        switch self {
        case .content: return "编辑内容"
        case .date: return "修改日期"
        case .schedule: return "修改安排"
        case .category: return "修改类型"
        case .recurrence: return "重复计划"
        case .reminder: return "计划提醒"
        }
    }
    var height: CGFloat {
        switch self {
        case .content: return 500
        case .date: return 300
        case .schedule: return 540
        case .category: return 380
        case .recurrence, .reminder: return 430
        }
    }
}

struct PlanQuickEditor: View {
    @ObservedObject var store: PlanStore
    let item: CheckInItem
    let field: PlanQuickField
    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var note: String
    @State private var day: Date
    @State private var time: Date
    @State private var schedule: ScheduleKind
    @State private var category: RecordCategory
    @State private var durationEnabled: Bool
    @State private var minutes: Int
    @State private var recurrence: PlanRepeatRule
    @State private var weekdays: Set<Int>
    @State private var reminder: Int
    @State private var isTitleFocused = false
    @State private var addingCategory = false
    @State private var categoryName = ""
    @State private var discard = false
    @State private var error: String?

    init(store: PlanStore, item: CheckInItem, field: PlanQuickField) {
        self.store = store; self.item = item; self.field = field
        _title = State(initialValue: item.title); _note = State(initialValue: item.note)
        _day = State(initialValue: item.scheduledStart); _time = State(initialValue: item.scheduledStart)
        _schedule = State(initialValue: item.effectiveScheduleKind); _category = State(initialValue: item.category)
        _durationEnabled = State(initialValue: item.hasPlannedDuration); _minutes = State(initialValue: item.plannedMinutes)
        _recurrence = State(initialValue: item.effectiveRepeatRule)
        _weekdays = State(initialValue: Set(item.effectiveRepeatWeekdays.isEmpty ? [Calendar.current.component(.weekday, from: item.scheduledStart)] : item.effectiveRepeatWeekdays))
        _reminder = State(initialValue: item.reminderMinutesBefore ?? -1)
#if DEBUG
        let route = ProcessInfo.processInfo.environment["MOJI_QA_SCENARIO"] ?? ""
        if route == "workflow-plan-quick-recurrence-custom" {
            _recurrence = State(initialValue: .customWeekdays); _weekdays = State(initialValue: [2, 4, 6])
        }
        if route == "workflow-plan-quick-category-new" {
            _addingCategory = State(initialValue: true); _categoryName = State(initialValue: "阅读与运动")
        }
#endif
    }

    private var change: PlanQuickChange {
        switch field {
        case .content: return .content(title: title, note: note)
        case .date: return .date(day)
        case .schedule: return .schedule(schedule, hour: Calendar.current.component(.hour, from: time), minute: Calendar.current.component(.minute, from: time), estimatedMinutes: durationEnabled ? minutes : nil)
        case .category: return .category(category)
        case .recurrence: return .recurrence(recurrence, weekdays: weekdays)
        case .reminder: return .reminder(reminder < 0 ? nil : reminder)
        }
    }
    private var isDirty: Bool {
        switch field {
        case .content: return title != item.title || note != item.note
        case .date: return !Calendar.current.isDate(day, inSameDayAs: item.scheduledStart)
        case .schedule: return schedule != item.effectiveScheduleKind || durationEnabled != item.hasPlannedDuration || (durationEnabled && minutes != item.plannedMinutes) || (schedule == .exactTime && Calendar.current.dateComponents([.hour, .minute], from: time) != Calendar.current.dateComponents([.hour, .minute], from: item.scheduledStart))
        case .category: return category != item.category || !categoryName.isEmpty
        case .recurrence: return recurrence != item.effectiveRepeatRule || (recurrence == .customWeekdays && weekdays != Set(item.effectiveRepeatWeekdays))
        case .reminder: return reminder != (item.reminderMinutesBefore ?? -1)
        }
    }
    private var canSave: Bool { change.applying(to: item) != nil }
    private var categories: [RecordCategory] {
        PlanCategoryLibrary.availableCategories(customJSON: SharedPersistence.sharedDefaults.string(forKey: PlanSettingsKeys.customCategories) ?? "[]", including: category)
    }

    var body: some View {
        ZStack {
            InkWashBackground()
            VStack(spacing: 0) {
                Capsule().fill(Color.primary.opacity(0.23)).frame(width: 36, height: 5).frame(height: 14)
                ZStack {
                    Text(field.title).font(.system(size: 17, weight: .semibold))
                    HStack {
                        Button("取消") { if isDirty { discard = true } else { dismiss() } }
                        Spacer()
                        Button("保存") { IMETextInput.commit(save) }.fontWeight(.semibold).disabled(!canSave && !isTitleFocused)
                    }.font(.system(size: 17))
                }.frame(height: 44).padding(.horizontal, 18)
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        if field != .content { Text(item.title).font(.system(size: 15)).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true) }
                        editor
                        if item.calendarEventIdentifier != nil, field == .date || field == .schedule {
                            Text("这里修改计划，已写入系统日历的事件需在日历中另行修改。")
                                .font(.system(size: 12)).foregroundStyle(.secondary)
                        }
                    }.padding(.horizontal, 20).padding(.top, 12).padding(.bottom, 20)
                }.scrollDismissesKeyboard(.interactively)
            }
        }
        .presentationDetents([.height(field.height)])
        .presentationDragIndicator(.hidden)
        .interactiveDismissDisabled(isDirty)
        .confirmationDialog("放弃未保存的修改？", isPresented: $discard, titleVisibility: .visible) {
            Button("放弃修改", role: .destructive) { dismiss() }
            Button("继续编辑", role: .cancel) {}
        }
        .alert("无法保存", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("好", role: .cancel) {}
        } message: { Text(error ?? "") }
    }

    @ViewBuilder private var editor: some View {
        switch field {
        case .content:
            caption("标题")
            IMESafeTextField(placeholder: "计划标题", text: $title, isFocused: $isTitleFocused,
                accessibilityIdentifier: "plan.quick.content.title", textStyle: .body)
                .frame(minHeight: 44).padding(.horizontal, 12).quickFieldSurface()
            caption("备注")
            TextField("添加背景、步骤或注意事项", text: $note, axis: .vertical)
                .font(.system(size: 16)).lineLimit(6...14).frame(minHeight: 180, alignment: .topLeading)
                .padding(12).quickFieldSurface().accessibilityIdentifier("plan.quick.content.note")
        case .date:
            HStack {
                ForEach([("今天", 0), ("明天", 1), ("一周后", 7)], id: \.0) { option in
                    Button(option.0) { day = Calendar.current.date(byAdding: .day, value: option.1, to: Date()) ?? Date() }
                        .font(.system(size: 15)).frame(maxWidth: .infinity, minHeight: 44).quickFieldSurface()
                }
            }
            DatePicker("日期", selection: $day, displayedComponents: .date).font(.system(size: 17)).padding(12).quickFieldSurface()
            caption("修改日期会保留原有时段、预计投入和重复规则。")
        case .schedule:
            choiceGroup(ScheduleKind.allCases.map { ($0.rawValue, $0.displayName) }, selected: schedule.rawValue) { schedule = ScheduleKind(rawValue: $0) ?? .allDay }
            if schedule == .exactTime {
                DatePicker("开始时间", selection: $time, displayedComponents: .hourAndMinute)
                    .font(.system(size: 17)).padding(.horizontal, 12).frame(minHeight: 44).quickFieldSurface()
            }
            VStack(spacing: 0) {
                Toggle("预计投入", isOn: $durationEnabled).font(.system(size: 17)).frame(minHeight: 44)
                if durationEnabled {
                    Stepper(DurationText.full(minutes: minutes), value: $minutes, in: 1...1_440).frame(minHeight: 44)
                    HStack { ForEach([15, 25, 45, 60], id: \.self) { amount in
                        Button("\(amount) 分钟") { minutes = amount }.font(.system(size: 13)).frame(maxWidth: .infinity, minHeight: 36)
                    } }
                }
            }.padding(.horizontal, 12).quickFieldSurface()
        case .category:
            choiceGroup(categories.map { ($0.rawValue, $0.displayName) }, selected: category.rawValue) { category = RecordCategory(rawValue: $0) ?? .study }
            if addingCategory {
                HStack {
                    TextField("例如：阅读、运动", text: $categoryName)
                    Button("添加") { addCategory() }.disabled(PlanCategoryLibrary.normalizedName(categoryName).isEmpty)
                }.padding(12).quickFieldSurface()
            } else {
                Button { addingCategory = true } label: { Label("新建类型", systemImage: "plus").frame(minHeight: 44) }
            }
        case .recurrence:
            choiceGroup(PlanRepeatRule.allCases.map { ($0.rawValue, $0.displayName) }, selected: recurrence.rawValue) { recurrence = PlanRepeatRule(rawValue: $0) ?? .never }
            if recurrence == .customWeekdays {
                HStack(spacing: 5) { ForEach([2, 3, 4, 5, 6, 7, 1], id: \.self) { day in
                    Button([1: "日", 2: "一", 3: "二", 4: "三", 5: "四", 6: "五", 7: "六"][day]!) {
                        if weekdays.contains(day) { if weekdays.count > 1 { weekdays.remove(day) } } else { weekdays.insert(day) }
                    }.font(.system(size: 14)).frame(maxWidth: .infinity, minHeight: 36)
                        .foregroundStyle(weekdays.contains(day) ? Color.planBackground : Color.planPrimary)
                        .background(weekdays.contains(day) ? Color.planPrimary : Color.planPrimary.opacity(0.06), in: Capsule())
                } }
            }
        case .reminder:
            let options = [(-1, "不提醒"), (0, "开始时"), (5, "提前 5 分钟"), (15, "提前 15 分钟"), (30, "提前 30 分钟"), (60, "提前 1 小时"), (1440, "提前 1 天")]
            let values = options + (options.contains { $0.0 == reminder } ? [] : [(reminder, "提前 \(reminder) 分钟")])
            choiceGroup(values.map { (String($0.0), $0.1) }, selected: String(reminder)) { reminder = Int($0) ?? -1 }
        }
    }

    private func caption(_ text: String) -> some View { Text(text).font(.system(size: 12)).foregroundStyle(.secondary) }
    private func choiceGroup(_ options: [(String, String)], selected: String, onSelect: @escaping (String) -> Void) -> some View {
        VStack(spacing: 0) {
            ForEach(options, id: \.0) { option in
                Button { onSelect(option.0) } label: {
                    HStack(spacing: 12) {
                        Text(option.1).font(.system(size: 17)).frame(maxWidth: .infinity, alignment: .leading)
                        Image(systemName: "checkmark").font(.system(size: 15, weight: .semibold)).opacity(option.0 == selected ? 1 : 0)
                    }.padding(.horizontal, 12).frame(minHeight: 40).contentShape(Rectangle())
                }.buttonStyle(.plain).accessibilityAddTraits(option.0 == selected ? .isSelected : [])
            }
        }.quickFieldSurface()
    }
    private func addCategory() {
        let old = SharedPersistence.sharedDefaults.string(forKey: PlanSettingsKeys.customCategories) ?? "[]"
        guard let result = PlanCategoryLibrary.adding(name: categoryName, to: old) else { return }
        SharedPersistence.sharedDefaults.set(result.json, forKey: PlanSettingsKeys.customCategories)
        category = result.category; categoryName = ""; addingCategory = false
    }
    private func save() {
        guard canSave else { return }
        if store.quickUpdatePlan(item.id, change: change) { dismiss() }
        else { error = "这项计划已被删除或无法修改，请返回列表重新打开。" }
    }
}

private extension View {
    func quickFieldSurface() -> some View {
        background(Color.planPrimary.opacity(0.045), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}


/// Explicit save/cancel contract, shared by the content editors.
private struct EditorDiscardModifier: ViewModifier {
    let isDirty: Bool
    @Binding var isPresented: Bool
    @Environment(\.dismiss) private var dismiss
    func body(content: Content) -> some View {
        content.interactiveDismissDisabled(isDirty)
            .confirmationDialog("放弃未保存的修改？", isPresented: $isPresented, titleVisibility: .visible) {
                Button("放弃修改", role: .destructive) { dismiss() }
                Button("继续编辑", role: .cancel) {}
            }
    }
}

extension View {
    func confirmDiscardEdits(isDirty: Bool, isPresented: Binding<Bool>) -> some View {
        modifier(EditorDiscardModifier(isDirty: isDirty, isPresented: isPresented))
    }
}
