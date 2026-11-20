import SwiftUI

private func workflowCategories(_ current: RecordCategory? = nil) -> [RecordCategory] {
    PlanCategoryLibrary.availableCategories(customJSON: SharedPersistence.sharedDefaults.string(forKey: PlanSettingsKeys.customCategories) ?? "[]", including: current)
}

private struct WorkflowPlanRoute: Identifiable { let id: UUID; let item: CheckInItem }

struct PlanCopySheet: View {
    @ObservedObject var store: PlanStore
    let item: CheckInItem
    @Environment(\.dismiss) private var dismiss
    @State private var day = Date()
    var body: some View {
        NavigationStack {
            Form {
                Section { Text(item.title); DatePicker("目标日期", selection: $day, displayedComponents: .date) }
                Section { Text("复制为独立的新计划，保留类型、说明、时段和预计投入，不复制完成状态、计时记录或重复序列。").font(.footnote).foregroundStyle(.secondary) }
            }
            .scrollContentBackground(.hidden).background { InkWashBackground() }
            .navigationTitle("复制计划").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("复制") { store.copyPlan(item.id, to: day); dismiss() }.fontWeight(.semibold) }
            }
        }
    }
}

struct PlanLibraryView: View {
    @ObservedObject var store: PlanStore
    @Environment(\.dismiss) private var dismiss
    @State private var mode = 0
    @State private var query = ""
    @State private var category = "all"
    @State private var status = "all"
    @State private var period = "all"
    @State private var from = Date()
    @State private var to = Date()
    @State private var selected = Set<UUID>()
    @State private var selecting = false
    @State private var showBatch = false
    @State private var editing: WorkflowPlanRoute?
    @State private var copying: WorkflowPlanRoute?
    @State private var templateDay = Date()
    @State private var filtersExpanded = false
    @ObservedObject private var ai = AISettingsStore.shared
    @State private var isReadingFilter = false
    @State private var filterFailure: String?
    /// The sentence the filters were last set from, and what they were before.
    @State private var appliedFilterSentence: String?
    @State private var filterBeforeSentence: LibraryFilter?

    private var plans: [CheckInItem] {
        let today = Calendar.current.startOfDay(for: Date())
        return store.checkInItems.filter { item in
            guard item.kind == .planned,
                  query.isEmpty || item.title.localizedCaseInsensitiveContains(query) || item.note.localizedCaseInsensitiveContains(query),
                  category == "all" || item.category.rawValue == category else { return false }
            let day = Calendar.current.startOfDay(for: item.scheduledStart)
            let matchesStatus = status == "archived" ? item.isArchived == true : item.isArchived != true && (status == "all" || item.status.rawValue == status)
            let matchesPeriod = period == "all" || (period == "past" && day < today) || (period == "today" && day == today) || (period == "future" && day > today) || (period == "range" && day >= Calendar.current.startOfDay(for: from) && day <= Calendar.current.startOfDay(for: to))
            return matchesStatus && matchesPeriod
        }.sorted { $0.scheduledStart > $1.scheduledStart }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Picker("内容", selection: $mode) { Text("全部计划").tag(0); Text("计划模板").tag(1) }.pickerStyle(.segmented)
                    TextField("搜索标题或说明", text: $query).autocorrectionDisabled()
                    if mode == 0 { sentenceFilterRow }
                }
                if mode == 0 { filtersSection; plansSection } else { templatesSection }
                if let notice = store.workflowNotice { Section { Text(notice).font(.footnote).foregroundStyle(.secondary) } }
                if store.workflowUndo != nil { Section { Button("撤销上次整理") { store.undoWorkflow() } } }
            }
            .scrollContentBackground(.hidden).background { InkWashBackground() }
            .navigationTitle("计划库").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
            .sheet(isPresented: $showBatch) { PlanBatchSheet(store: store, ids: selected) { selected.removeAll(); selecting = false } }
            .sheet(item: $editing) { CheckInEditorView(store: store, item: $0.item) }
            .sheet(item: $copying) { PlanCopySheet(store: store, item: $0.item) }
            .onChange(of: mode) { _, _ in selected.removeAll(); selecting = false }
            .onChange(of: plans.filter { $0.status != .inProgress }.map(\.id)) { _, ids in selected.formIntersection(ids) }
            .onChange(of: from) { _, day in if to < day { to = day } }
#if DEBUG
            .onAppear {
                let scenario = ProcessInfo.processInfo.environment["MOJI_QA_SCENARIO"]
                if scenario == "workflow-library-filters" { filtersExpanded = true }
                if scenario == "ai-library-filter" || scenario == "ai-library-filter-applied" {
                    query = "上周没做完的学习计划"
                    if scenario == "ai-library-filter-applied" {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { applySentenceFilter() }
                    }
                }
                if scenario == "workflow-templates" { mode = 1 }
                if scenario == "workflow-library-batch" {
                    selected = Set(plans.prefix(2).map(\.id)); selecting = true
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { showBatch = true }
                }
            }
#endif
        }
    }

    /// A whole sentence in the search field can set the three filters at once.
    /// The row only exists while a model is set up and the text reads like one.
    @ViewBuilder
    private var sentenceFilterRow: some View {
        if isReadingFilter {
            InkDraftWaiting(text: "正在读这句话")
        } else if let sentence = appliedFilterSentence {
            HStack(spacing: 8) {
                Text("已按“\(sentence)”设好筛选").font(.footnote).foregroundStyle(.secondary).lineLimit(2)
                Spacer(minLength: 4)
                Button("还原") { restoreFilterBeforeSentence() }.font(.footnote.weight(.semibold)).buttonStyle(.borderless)
            }
        } else if ai.isReady, AIDrafting.looksLikeFilterSentence(query) {
            Button("按这句话筛选") { applySentenceFilter() }
                .font(.subheadline.weight(.medium))
                .accessibilityIdentifier("library.sentenceFilter")
            if let filterFailure { Text(filterFailure).font(.footnote).foregroundStyle(.secondary) }
        }
    }

    private var currentFilter: LibraryFilter {
        LibraryFilter(keyword: query, category: category, status: status, period: period, from: from, to: to)
    }

    private func setFilter(_ filter: LibraryFilter) {
        query = filter.keyword; category = filter.category; status = filter.status; period = filter.period
        if let start = filter.from, let end = filter.to { from = start; to = end }
    }

    private func applySentenceFilter() {
        let sentence = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let before = currentFilter
        isReadingFilter = true
        filterFailure = nil
        Task { @MainActor in
            defer { isReadingFilter = false }
            do {
                let filter = try await AIAssistant.shared.libraryFilter(sentence, categories: workflowCategories())
                filterBeforeSentence = before
                setFilter(filter)
                appliedFilterSentence = sentence
                filtersExpanded = true
            } catch {
                filterFailure = error.draftMessage
            }
        }
    }

    private func restoreFilterBeforeSentence() {
        if let before = filterBeforeSentence { setFilter(before) }
        appliedFilterSentence = nil
        filterBeforeSentence = nil
    }

    private var filtersSection: some View {
        Section {
            DisclosureGroup(isExpanded: $filtersExpanded) {
            Picker("计划类型", selection: $category) {
                Text("全部类型").tag("all")
                ForEach(workflowCategories()) { Text($0.displayName).tag($0.rawValue) }
            }
            Picker("状态", selection: $status) {
                Text("全部状态").tag("all"); Text("待完成").tag("planned"); Text("进行中").tag("inProgress")
                Text("已完成").tag("completed"); Text("已跳过").tag("skipped"); Text("已归档").tag("archived")
            }
            Picker("日期", selection: $period) {
                Text("全部日期").tag("all"); Text("过去").tag("past"); Text("今天").tag("today")
                Text("未来").tag("future"); Text("日期范围").tag("range")
            }
            if period == "range" {
                DatePicker("开始日期", selection: $from, displayedComponents: .date)
                DatePicker("结束日期", selection: $to, in: from..., displayedComponents: .date)
            }
            if category != "all" || status != "all" || period != "all" {
                Button("清除筛选") { category = "all"; status = "all"; period = "all" }
            }
            } label: {
                VStack(alignment: .leading, spacing: 3) {
                    Text("筛选").foregroundStyle(.primary)
                    Text(filterSummary).font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }

    private var filterSummary: String {
        let statusNames = ["planned": "待完成", "inProgress": "进行中", "completed": "已完成", "skipped": "已跳过", "archived": "已归档"]
        let periodNames = ["past": "过去", "today": "今天", "future": "未来", "range": "日期范围"]
        let parts = [category == "all" ? nil : RecordCategory(rawValue: category)?.displayName,
                     statusNames[status], periodNames[period]].compactMap { $0 }
        return parts.isEmpty ? "全部类型 · 全部状态 · 全部日期" : parts.joined(separator: " · ")
    }

    private var plansSection: some View {
        Section {
            HStack {
                Text("\(plans.count) 个计划").font(.subheadline).foregroundStyle(.secondary)
                Spacer()
                Button(selecting ? "取消选择" : "批量选择") { selecting.toggle(); selected.removeAll() }
            }
            if selecting {
                HStack {
                    Button("全选结果") { selected = Set(plans.filter { $0.status != .inProgress }.map(\.id)) }
                    Spacer(); Button("整理 \(selected.count) 项") { showBatch = true }.disabled(selected.isEmpty)
                }
            }
            if plans.isEmpty { Text("没有符合条件的计划").foregroundStyle(.secondary) }
            ForEach(plans) { item in libraryRow(item) }
        }
    }

    private func libraryRow(_ item: CheckInItem) -> some View {
        Button {
            if selecting { guard item.status != .inProgress else { return }; if !selected.insert(item.id).inserted { selected.remove(item.id) } }
            else { editing = WorkflowPlanRoute(id: item.id, item: item) }
        } label: {
            HStack(spacing: 12) {
                if selecting { Image(systemName: selected.contains(item.id) ? "checkmark.circle.fill" : "circle") }
                VStack(alignment: .leading, spacing: 5) {
                    Text(item.title).font(.body).foregroundStyle(.primary)
                    Text("\(item.scheduledStart.formatted(.dateTime.month().day())) · \(item.category.displayName) · \(item.isArchived == true ? "已归档" : item.status.displayName)")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
            }.padding(.vertical, 4)
        }.buttonStyle(.plain)
        .disabled(selecting && item.status == .inProgress)
        .contextMenu {
            Button("复制到今天") { store.copyPlan(item.id, to: Date()) }
            Button("复制到指定日期") { copying = WorkflowPlanRoute(id: item.id, item: item) }
            Button("保存为模板") { store.saveTemplate(from: item.id) }
            Button(item.isArchived == true ? "取消归档" : "归档") { store.batchPlans([item.id], archived: item.isArchived != true) }
        }
    }

    @ViewBuilder
    private var templatesSection: some View {
        Section { DatePicker("添加到", selection: $templateDay, displayedComponents: .date) }
        Section("计划模板") {
            if store.snapshot.planTemplates.isEmpty { Text("在计划的更多菜单中选择“保存为模板”").font(.subheadline).foregroundStyle(.secondary) }
            ForEach(store.snapshot.planTemplates.filter { query.isEmpty || $0.title.localizedCaseInsensitiveContains(query) || $0.note.localizedCaseInsensitiveContains(query) }) { template in
                templateRow(template)
            }
        }
    }

    private func templateRow(_ template: PlanTemplate) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(template.title).font(.body)
            Text("\(template.category.displayName) · \(template.scheduleKind.displayName)\(template.plannedDurationEnabled ? " · \(template.plannedMinutes) 分钟" : "")").font(.caption).foregroundStyle(.secondary)
            HStack { Button("添加计划") { store.applyTemplate(template.id, on: templateDay) }; Spacer(); Button("删除", role: .destructive) { store.deleteTemplate(template.id) } }
        }.padding(.vertical, 4)
    }
}

struct PlanBatchSheet: View {
    @ObservedObject var store: PlanStore
    let ids: Set<UUID>
    var onDone: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var action = "date"
    @State private var day = Date()
    @State private var category = RecordCategory.study
    @State private var confirming = false
    var body: some View {
        NavigationStack {
            Form {
                Section("已选择 \(ids.count) 个计划") {
                    Picker("整理方式", selection: $action) {
                        Text("修改日期").tag("date"); Text("修改类型").tag("category"); Text("归档").tag("archive"); Text("取消归档").tag("unarchive")
                    }
                    if action == "date" { DatePicker("目标日期", selection: $day, displayedComponents: .date) }
                    if action == "category" { Picker("计划类型", selection: $category) { ForEach(workflowCategories(category)) { Text($0.displayName).tag($0) } } }
                }
                Section { Text("正在专注的计划不会被修改。修改日期只处理待完成计划；归档保留历史记录。整理后可在计划库撤销。").font(.footnote).foregroundStyle(.secondary) }
            }
            .scrollContentBackground(.hidden).background { InkWashBackground() }
            .navigationTitle("批量整理").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("应用") { confirming = true }.fontWeight(.semibold) }
            }
            .alert("整理 \(ids.count) 个计划？", isPresented: $confirming) {
                Button("取消", role: .cancel) {}
                Button("应用") {
                    store.batchPlans(ids, day: action == "date" ? day : nil, category: action == "category" ? category : nil,
                        archived: action == "archive" ? true : action == "unarchive" ? false : nil)
                    onDone(); dismiss()
                }
            } message: { Text("将应用所选整理方式，历史记录不会删除。") }
        }
    }
}

struct MemoConversionView: View {
    @ObservedObject var store: PlanStore
    let memo: MemoItem
    @Environment(\.dismiss) private var dismiss
    @State private var day = Date()
    @State private var category = RecordCategory.study
    @State private var editing: WorkflowPlanRoute?
    @ObservedObject private var ai = AISettingsStore.shared
    /// A date and a type read out of each row, keyed by checklist item (nil for the whole memo).
    @State private var hints: [UUID?: ConversionHint] = [:]
    @State private var isReadingHints = false
    @State private var hintFailure: String?
    private var checklistRows: [MemoChecklistItem] {
        memo.mode == .checklist ? memo.checklistItems.filter { !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty } : []
    }
    var body: some View {
        NavigationStack {
            Form {
                Section { DatePicker("计划日期", selection: $day, displayedComponents: .date)
                    Picker("计划类型", selection: $category) { ForEach(workflowCategories(category)) { Text($0.displayName).tag($0) } }
                    if ai.isReady {
                        if isReadingHints { InkDraftWaiting(text: "正在看每一行") }
                        else if hints.isEmpty {
                            Button("按内容预填每一行") { readHints() }.accessibilityIdentifier("memo.conversion.prefill")
                            if let hintFailure { Text(hintFailure).font(.footnote).foregroundStyle(.secondary) }
                        } else {
                            HStack {
                                Text("带浅色标注的行会用它自己的日期和类型").font(.footnote).foregroundStyle(.secondary)
                                Spacer(minLength: 4)
                                Button("不用") { hints = [:] }.font(.footnote.weight(.semibold)).buttonStyle(.borderless)
                            }
                        }
                    }
                }
                Section("转为计划") {
                    conversionRow(title: "整则备忘", itemID: nil)
                    ForEach(checklistRows) { item in conversionRow(title: item.text, itemID: item.id) }
                }
                Section { Text("保留原备忘，标题和内容带入新计划。同一备忘或清单项不会重复转换；已转换的条目可直接查看计划。").font(.footnote).foregroundStyle(.secondary) }
            }
            .scrollContentBackground(.hidden).background { InkWashBackground() }
            .navigationTitle("备忘转计划").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
            .sheet(item: $editing) { CheckInEditorView(store: store, item: $0.item) }
#if DEBUG
            .onAppear {
                if ProcessInfo.processInfo.environment["MOJI_QA_SCENARIO"] == "ai-memo-convert", hints.isEmpty, !isReadingHints { readHints() }
            }
#endif
        }
    }
    private func conversionRow(title: String, itemID: UUID?) -> some View {
        let plan = store.checkInItems.first { $0.sourceMemoID == memo.id && $0.sourceMemoChecklistItemID == itemID }
        let hint = plan == nil ? hints[itemID] : nil
        return Button {
            if let plan { editing = WorkflowPlanRoute(id: plan.id, item: plan) }
            else { store.convertMemo(id: memo.id, itemID: itemID, day: hint?.day ?? day, category: hint?.category ?? category) }
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text(title).foregroundStyle(.primary)
                    if let hint {
                        HStack(spacing: 5) {
                            DraftSeal(size: 15, seed: 6)
                            Text(hint.text()).font(.caption).foregroundStyle(Color.planSecondary)
                        }
                    }
                }
                Spacer(); Text(plan == nil ? "转换" : "查看计划").font(.subheadline).foregroundStyle(.secondary)
            }.padding(.vertical, 4)
        }
    }
    private func readHints() {
        let rows = checklistRows
        var texts = [[memo.title, memo.mode == .note ? memo.content : ""].filter { !$0.isEmpty }.joined(separator: "：")]
        texts.append(contentsOf: rows.map(\.text))
        isReadingHints = true
        hintFailure = nil
        Task { @MainActor in
            defer { isReadingHints = false }
            do {
                let found = try await AIAssistant.shared.conversionHints(memoTitle: memo.title, rows: texts, categories: workflowCategories())
                var result: [UUID?: ConversionHint] = [:]
                for (index, hint) in found { result[index == 0 ? nil : rows[index - 1].id] = hint }
                if result.isEmpty { hintFailure = "这几行里没有读到日期或类型，沿用上面选的。" }
                hints = result
            } catch {
                hintFailure = error.draftMessage
            }
        }
    }
}

struct WorkflowWeekSection: View {
    @ObservedObject var store: PlanStore
    let week: Date
    @State private var goalEditor = false
    @State private var reflectionEditor = false
    @State private var carryEditor = false
    @State private var editingGoal: WeeklyGoal?
    @ObservedObject private var ai = AISettingsStore.shared
    @State private var nextWeekDrafts = false
    private var key: String { PlanWorkflow.weekKey(week) }
    private var summary: WorkflowWeekSummary { .make(snapshot: store.snapshot, week: week) }
    private var reflection: WeeklyReflection? { store.snapshot.weeklyReflections.first { $0.weekStart == key } }
    var body: some View {
        VStack(spacing: 16) {
            VStack(alignment: .leading, spacing: 14) {
                Text("计划 / 实际").font(.headline)
                HStack { metric("待完成", "\(summary.pending.count) 项"); metric("结转待办", "\(summary.carryover.count) 项"); metric("未记录", "\(summary.untrackedCount) 项") }
                HStack { metric("预计投入", "\(summary.estimatedMinutes) 分钟"); metric("实际专注", "\(summary.actualMinutes) 分钟") }
                Text("勾选完成不等于已计时；未计时的完成计划显示“未记录”，不计入专注时长。").font(.caption).foregroundStyle(.secondary)
                ForEach(summary.scheduled) { item in
                    let timed = store.records.filter { $0.checkInItemID == item.id && $0.hasPreciseTime }
                    let actual = WorkflowWeekSummary.make(snapshot: PlanSnapshot(records: timed), week: week).actualMinutes
                    HStack(alignment: .top) {
                        Text(item.title).font(.subheadline).lineLimit(2); Spacer()
                        VStack(alignment: .trailing, spacing: 3) {
                            Text(item.plannedDurationMinutes.map { "预计 \($0) 分钟" } ?? "未估算")
                            Text(timed.isEmpty ? "未记录" : "实际 \(actual) 分钟")
                        }.font(.caption).foregroundStyle(.secondary)
                    }
                }
            }.planCard()
            VStack(alignment: .leading, spacing: 14) {
                HStack { Text("周目标").font(.headline); Spacer(); Button("设置目标") { editingGoal = nil; goalEditor = true }.font(.subheadline) }
                let goals = store.snapshot.weeklyGoals.filter { $0.weekStart == key }
                if goals.isEmpty { Text("可选：给这一周一个轻量目标").font(.subheadline).foregroundStyle(.secondary) }
                ForEach(goals) { goal in
                    let progress = WorkflowWeekSummary.make(snapshot: store.snapshot, week: week, category: goal.category)
                    VStack(alignment: .leading, spacing: 8) {
                        Button(goal.category?.displayName ?? "全部类型") { editingGoal = goal; goalEditor = true }.font(.subheadline.weight(.semibold))
                        if let count = goal.targetCount { progressLine("完成计划", value: progress.completed.count, target: count, unit: "项") }
                        if let minutes = goal.targetMinutes { progressLine("实际专注", value: progress.actualMinutes, target: minutes, unit: "分钟") }
                    }
                }
            }.planCard()
            VStack(alignment: .leading, spacing: 14) {
                HStack { Text("周复盘").font(.headline); Spacer(); Button("编辑") { reflectionEditor = true }.font(.subheadline) }
                Text(reflection?.note.isEmpty == false ? reflection!.note : "记录这一周做得好的事、遇到的问题。")
                    .font(.subheadline).foregroundStyle(.secondary)
                Text("下周关注").font(.subheadline.weight(.semibold))
                Text(reflection?.nextFocus.isEmpty == false ? reflection!.nextFocus : "给下周留下一句提醒。")
                    .font(.subheadline).foregroundStyle(.secondary)
                Button("选择待办移至下周") { carryEditor = true }.font(.subheadline.weight(.semibold))
                if ai.isReady {
                    Button("拟下周草稿") { nextWeekDrafts = true }.font(.subheadline.weight(.semibold))
                        .accessibilityIdentifier("week.nextWeekDraft")
                }
            }.planCard()
        }
        .sheet(isPresented: $nextWeekDrafts) {
            PlanDraftSheet(store: store, title: "下周草稿", subject: "按这一周的记录和“下周关注”拟的",
                           waitingText: "正在拟下周",
                           load: { try await AIAssistant.shared.draftNextWeek(snapshot: store.snapshot, week: week, categories: workflowCategories()) })
        }
        .sheet(isPresented: $goalEditor) { WeeklyGoalEditor(store: store, week: week, goal: editingGoal) }
        .sheet(isPresented: $reflectionEditor) { WeeklyReflectionEditor(store: store, week: week, reflection: reflection) }
        .sheet(isPresented: $carryEditor) { NextWeekPlansSheet(store: store, week: week) }
    }
    private func metric(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 3) { Text(value).font(.subheadline.weight(.semibold)); Text(title).font(.caption).foregroundStyle(.secondary) }.frame(maxWidth: .infinity, alignment: .leading)
    }
    private func progressLine(_ title: String, value: Int, target: Int, unit: String) -> some View {
        VStack(spacing: 6) { HStack { Text(title); Spacer(); Text("\(value)/\(target) \(unit)") }.font(.caption); ProgressView(value: Double(min(value, target)), total: Double(target)).tint(.planPrimary) }
    }
}

struct WeeklyGoalEditor: View {
    @ObservedObject var store: PlanStore
    let week: Date
    let goal: WeeklyGoal?
    @Environment(\.dismiss) private var dismiss
    @State private var category: String
    @State private var count: String
    @State private var minutes: String
    init(store: PlanStore, week: Date, goal: WeeklyGoal?) {
        self.store = store; self.week = week; self.goal = goal
        _category = State(initialValue: goal?.category?.rawValue ?? "all")
        _count = State(initialValue: goal?.targetCount.map(String.init) ?? "")
        _minutes = State(initialValue: goal?.targetMinutes.map(String.init) ?? "")
    }
    private var valid: Bool { (count.isEmpty || (Int(count).map { (1...100000).contains($0) } ?? false)) && (minutes.isEmpty || (Int(minutes).map { (1...100000).contains($0) } ?? false)) && (!count.isEmpty || !minutes.isEmpty) }
    @State private var confirmsDiscard = false
    private var hasEdits: Bool { category != (goal?.category?.rawValue ?? "all") || count != (goal?.targetCount.map(String.init) ?? "") || minutes != (goal?.targetMinutes.map(String.init) ?? "") }
    var body: some View {
        NavigationStack {
            Form {
                Section("\(PlanWorkflow.weekKey(week)) 起的一周") {
                    Picker("目标范围", selection: $category) { Text("全部类型").tag("all"); ForEach(workflowCategories(goal?.category)) { Text($0.displayName).tag($0.rawValue) } }
                    HStack {
                        Text("完成计划（项）")
                        Spacer()
                        TextField("可选", text: $count).keyboardType(.numberPad)
                            .multilineTextAlignment(.trailing).frame(width: 100)
                    }
                    HStack {
                        Text("实际专注（分钟）")
                        Spacer()
                        TextField("可选", text: $minutes).keyboardType(.numberPad)
                            .multilineTextAlignment(.trailing).frame(width: 100)
                    }
                }
                Section { Text("至少填写一项正整数目标（1–100000）。专注目标仅统计真实计时，不把勾选完成换算成分钟。").font(.footnote).foregroundStyle(.secondary) }
                if let goal { Section { Button("删除目标", role: .destructive) { store.deleteWeeklyGoal(goal.id); dismiss() } } }
            }
            .scrollContentBackground(.hidden).background { InkWashBackground() }
            .navigationTitle("周目标").navigationBarTitleDisplayMode(.inline)
            .confirmDiscardEdits(isDirty: hasEdits, isPresented: $confirmsDiscard)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { IMETextInput.commit { if hasEdits { confirmsDiscard = true } else { dismiss() } } } }
                ToolbarItem(placement: .confirmationAction) { Button("保存") {
                    store.saveWeeklyGoal(WeeklyGoal(id: goal?.id ?? UUID(), weekStart: PlanWorkflow.weekKey(week), category: category == "all" ? nil : RecordCategory(rawValue: category), targetCount: Int(count), targetMinutes: Int(minutes))); dismiss()
                }.fontWeight(.semibold).disabled(!valid) }
            }
        }
    }
}

struct WeeklyReflectionEditor: View {
    @ObservedObject var store: PlanStore
    let week: Date
    let reflection: WeeklyReflection?
    @Environment(\.dismiss) private var dismiss
    @State private var note: String
    @State private var nextFocus: String
    init(store: PlanStore, week: Date, reflection: WeeklyReflection?) {
        self.store = store; self.week = week; self.reflection = reflection
        _note = State(initialValue: reflection?.note ?? ""); _nextFocus = State(initialValue: reflection?.nextFocus ?? "")
    }
    @State private var confirmsDiscard = false
    @ObservedObject private var ai = AISettingsStore.shared
    @State private var draft: ReflectionDraft?
    @State private var isDrafting = false
    @State private var draftFailure: String?
    /// What the two fields held before a draft was put into them.
    @State private var beforeDraft: (note: String, nextFocus: String)?
    private var hasEdits: Bool { note != (reflection?.note ?? "") || nextFocus != (reflection?.nextFocus ?? "") }
    var body: some View {
        NavigationStack {
            Form {
                if ai.isReady { draftSection }
                Section("本周复盘") { TextField("做得好的事、遇到的问题", text: $note, axis: .vertical).lineLimit(5...12) }
                Section("下周关注") { TextField("给下周留下一句提醒", text: $nextFocus, axis: .vertical).lineLimit(3...8) }
            }
#if DEBUG
            .onAppear {
                let scenario = ProcessInfo.processInfo.environment["MOJI_QA_SCENARIO"] ?? ""
                guard scenario.hasPrefix("ai-reflection"), draft == nil, !isDrafting else { return }
                runDraft(thenApply: scenario == "ai-reflection-applied")
            }
#endif
            .scrollContentBackground(.hidden).background { InkWashBackground() }
            .navigationTitle("周复盘").navigationBarTitleDisplayMode(.inline)
            .confirmDiscardEdits(isDirty: hasEdits, isPresented: $confirmsDiscard)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { IMETextInput.commit { if hasEdits { confirmsDiscard = true } else { dismiss() } } } }
                ToolbarItem(placement: .confirmationAction) { Button("保存") { IMETextInput.commit { store.saveWeeklyReflection(WeeklyReflection(id: reflection?.id ?? UUID(), weekStart: PlanWorkflow.weekKey(week), note: note, nextFocus: nextFocus)); dismiss() } }.fontWeight(.semibold) }
            }
        }
    }
}

extension WeeklyReflectionEditor {
    /// The draft sits above the two fields, dashed and stamped, until the user
    /// puts it in. Saving is still the editor's own 保存.
    @ViewBuilder
    fileprivate var draftSection: some View {
        Section {
            if isDrafting {
                InkDraftWaiting(text: "正在起草")
            } else if let draft {
                VStack(alignment: .leading, spacing: 10) {
                    HStack(spacing: 7) {
                        DraftSeal(size: 20, seed: 2)
                        Text(beforeDraft == nil ? "草稿，还没有放进两栏" : "已放进下面两栏，保存前可以改")
                            .font(.footnote).foregroundStyle(Color.planSecondary)
                    }
                    if beforeDraft == nil {
                        Text(draft.note).font(.subheadline).foregroundStyle(Color.planPrimary).fixedSize(horizontal: false, vertical: true)
                        if !draft.nextFocus.isEmpty {
                            Text("下周关注：\(draft.nextFocus)").font(.subheadline).foregroundStyle(Color.planPrimary).fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    if !draft.evidence.isEmpty {
                        InkBrushDivider(animated: false, seed: 17).opacity(0.4)
                        ReflectionEvidenceList(evidence: draft.evidence)
                    }
                    HStack(spacing: 18) {
                        if beforeDraft == nil {
                            Button("放进两栏") { applyDraft(draft) }.font(.subheadline.weight(.semibold))
                                .accessibilityIdentifier("reflection.draft.apply")
                            Button("不用") { self.draft = nil }.font(.subheadline).foregroundStyle(Color.planSecondary)
                        } else {
                            Button("还原") { restoreBeforeDraft() }.font(.subheadline.weight(.semibold))
                        }
                    }
                    .buttonStyle(.borderless)
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .inkDraftSurface()
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
            } else {
                Button("起草复盘") { runDraft() }.accessibilityIdentifier("reflection.draft.run")
                if let draftFailure { Text(draftFailure).font(.footnote).foregroundStyle(.secondary) }
            }
        } footer: {
            if draft == nil, !isDrafting { Text("按这一周的计划记录起草，并注明依据的是哪几条计划。") }
        }
    }

    fileprivate func runDraft(thenApply: Bool = false) {
        isDrafting = true
        draftFailure = nil
        Task { @MainActor in
            defer { isDrafting = false }
            do {
                let result = try await AIAssistant.shared.draftReflection(snapshot: store.snapshot, week: week)
                draft = result
                if thenApply { applyDraft(result) }
            } catch AIRequestError.emptyText {
                draftFailure = "这一周还没有计划记录，没有可以起草的内容。"
            } catch {
                draftFailure = error.draftMessage
            }
        }
    }

    fileprivate func applyDraft(_ draft: ReflectionDraft) {
        beforeDraft = (note, nextFocus)
        note = draft.note
        if !draft.nextFocus.isEmpty { nextFocus = draft.nextFocus }
    }

    fileprivate func restoreBeforeDraft() {
        if let beforeDraft { note = beforeDraft.note; nextFocus = beforeDraft.nextFocus }
        beforeDraft = nil
        draft = nil
    }
}

struct NextWeekPlansSheet: View {
    @ObservedObject var store: PlanStore
    let week: Date
    @Environment(\.dismiss) private var dismiss
    @State private var selected = Set<UUID>()
    private var candidates: [CheckInItem] { let s = WorkflowWeekSummary.make(snapshot: store.snapshot, week: week); return (s.carryover + s.pending).sorted { $0.scheduledStart < $1.scheduledStart } }
    var body: some View {
        NavigationStack {
            List {
                Section { Text("移动原计划到下周对应星期，保留时间和说明，不复制、不改变重复序列。专注中的计划不会移动。").font(.footnote).foregroundStyle(.secondary) }
                Section("待办 \(candidates.count) 项") {
                    Button("全选") { selected = Set(candidates.map(\.id)) }
                    if candidates.isEmpty { Text("没有可结转的待办").foregroundStyle(.secondary) }
                    ForEach(candidates) { item in
                        Button { if !selected.insert(item.id).inserted { selected.remove(item.id) } } label: {
                            HStack { Image(systemName: selected.contains(item.id) ? "checkmark.circle.fill" : "circle"); Text(item.title).foregroundStyle(.primary); Spacer(); Text(item.scheduledStart.formatted(.dateTime.month().day())).font(.caption).foregroundStyle(.secondary) }
                        }
                    }
                }
            }
            .scrollContentBackground(.hidden).background { InkWashBackground() }
            .navigationTitle("移至下周").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("移动 \(selected.count) 项") { store.batchPlans(selected, nextWeekOf: week); dismiss() }.disabled(selected.isEmpty).fontWeight(.semibold) }
            }
        }
    }
}
