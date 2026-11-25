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
    /// The search field right after a sentence set the filters; editing it lets them go.
    @State private var appliedQuery: String?
    @State private var showsAIIntro = false

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
                        .submitLabel(.search)
                        .onSubmit(submitQuery)
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
            .aiIntroSheet(isPresented: $showsAIIntro)
            .onChange(of: mode) { _, _ in selected.removeAll(); selecting = false }
            .onChange(of: plans.filter { $0.status != .inProgress }.map(\.id)) { _, ids in selected.formIntersection(ids) }
            .onChange(of: from) { _, day in if to < day { to = day } }
            .onChange(of: query) { _, value in
                // New words after a sentence set the filters: the filters stay,
                // the line and the way back go, and the new words can be read again.
                if appliedFilterSentence != nil, value != appliedQuery { forgetSentenceFilter() }
                if filterFailure != nil { filterFailure = nil }
            }
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
        } else if ai.showsEntries, AIDrafting.looksLikeFilterSentence(query) {
            Button("按这句话筛选") { if ai.isReady { applySentenceFilter() } else { showsAIIntro = true } }
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

    /// Return in the search field reads a sentence the way 按这句话筛选 does.
    private func submitQuery() {
        guard mode == 0, ai.showsEntries, appliedFilterSentence == nil, !isReadingFilter,
              AIDrafting.looksLikeFilterSentence(query) else { return }
        if ai.isReady { applySentenceFilter() } else { showsAIIntro = true }
    }

    private func applySentenceFilter() {
        let sentence = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !sentence.isEmpty, !isReadingFilter else { return }
        let before = currentFilter
        isReadingFilter = true
        filterFailure = nil
        Task { @MainActor in
            defer { isReadingFilter = false }
            do {
                let filter = try await AIAssistant.shared.libraryFilter(sentence, categories: workflowCategories())
                // The field was changed while reading: the answer is for words no longer there.
                guard query.trimmingCharacters(in: .whitespacesAndNewlines) == sentence else { return }
                filterBeforeSentence = before
                setFilter(filter)
                appliedQuery = query
                appliedFilterSentence = sentence
                filtersExpanded = true
            } catch {
                guard query.trimmingCharacters(in: .whitespacesAndNewlines) == sentence else { return }
                filterFailure = error.draftMessage
            }
        }
    }

    /// 还原: the three filters as they were; the search field empty, not the sentence again.
    private func restoreFilterBeforeSentence() {
        let before = filterBeforeSentence
        forgetSentenceFilter()
        if let before {
            category = before.category; status = before.status; period = before.period
            if let start = before.from, let end = before.to { from = start; to = end }
        }
        query = ""
    }

    private func forgetSentenceFilter() {
        appliedFilterSentence = nil
        filterBeforeSentence = nil
        appliedQuery = nil
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
                Button("清除筛选") { category = "all"; status = "all"; period = "all"; forgetSentenceFilter() }
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

    /// 留痕: ` · 墨记拆自「…」` in vermilion after the small line.
    private func originMark(_ item: CheckInItem) -> Text {
        guard let origin = item.aiOrigin, !origin.isEmpty else { return Text("") }
        return Text(" · ") + Text(AIWriteText.mark(origin)).foregroundStyle(Color.planVermilion)
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
                    (Text("\(item.scheduledStart.formatted(.dateTime.month().day())) · \(item.category.displayName) · \(item.isArchived == true ? "已归档" : item.status.displayName)")
                        + originMark(item))
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
    @State private var didStartHints = false
    @State private var editingHint: ConversionHintRoute?
    @State private var showsAIIntro = false
    private var checklistRows: [MemoChecklistItem] {
        memo.mode == .checklist ? memo.checklistItems.filter { !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty } : []
    }
    var body: some View {
        NavigationStack {
            Form {
                Section { DatePicker("计划日期", selection: $day, displayedComponents: .date)
                    Picker("计划类型", selection: $category) { ForEach(workflowCategories(category)) { Text($0.displayName).tag($0) } }
                    if ai.showsEntries {
                        if isReadingHints { InkDraftWaiting(text: "正在看每一行") }
                        else if hints.isEmpty {
                            if let hintFailure {
                                Text(hintFailure).font(.footnote).foregroundStyle(.secondary)
                                Button("再试一次") { if ai.isReady { readHints() } else { showsAIIntro = true } }
                                    .accessibilityIdentifier("memo.conversion.retry")
                            } else {
                                Button("按内容预填每一行") { if ai.isReady { readHints() } else { showsAIIntro = true } }.accessibilityIdentifier("memo.conversion.prefill")
                            }
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
            .sheet(item: $editingHint) { route in
                let hint = hints[route.itemID] ?? ConversionHint()
                ConversionHintEditor(
                    title: route.title,
                    day: hint.day ?? day,
                    category: hint.category ?? category,
                    categories: workflowCategories(hint.category ?? category),
                    save: { changedDay, changedCategory in
                        // Only what was changed is kept for the row; the rest still follows the shared choice.
                        var updated = hints[route.itemID] ?? ConversionHint()
                        if let changedDay { updated.day = changedDay }
                        if let changedCategory { updated.category = changedCategory }
                        hints[route.itemID] = updated
                    },
                    clear: { hints[route.itemID] = nil }
                )
            }
            .aiIntroSheet(isPresented: $showsAIIntro)
            // A prefilled row is written as a draft: its undo bar shows here, above this page.
            .overlay(alignment: .bottom) { DraftUndoToast(store: store) }
            .onAppear {
                // Set up: the rows are read at once; otherwise the button explains how.
                guard ai.isReady, !didStartHints, hints.isEmpty, !isReadingHints else { return }
                didStartHints = true
                readHints()
            }
        }
    }
    private func conversionRow(title: String, itemID: UUID?) -> some View {
        let plan = store.checkInItems.first { $0.sourceMemoID == memo.id && $0.sourceMemoChecklistItemID == itemID }
        let hint = plan == nil ? hints[itemID] : nil
        let convert: () -> Void = {
            if let plan { editing = WorkflowPlanRoute(id: plan.id, item: plan) }
            else { convertRow(itemID: itemID, hint: hint) }
        }
        return HStack {
            VStack(alignment: .leading, spacing: 3) {
                Button(action: convert) {
                    Text(title).foregroundStyle(.primary).frame(maxWidth: .infinity, alignment: .leading)
                }
                .buttonStyle(.borderless)
                if let hint {
                    // The prefill of this row: tap to change its day or type, or not use it.
                    Button {
                        editingHint = ConversionHintRoute(itemID: itemID, title: title)
                    } label: {
                        HStack(spacing: 5) {
                            DraftSeal(size: 15, seed: 6)
                            Text(hint.text()).font(.caption).foregroundStyle(Color.planSecondary)
                            Image(systemName: "chevron.right").font(.system(size: 8, weight: .semibold))
                                .foregroundStyle(Color.planSecondary.opacity(0.6))
                        }
                        .frame(minHeight: 28)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel("改这一行的预填：\(hint.text())")
                    .accessibilityIdentifier("memo.conversion.hint")
                }
            }
            Spacer()
            Button(action: convert) {
                Text(plan == nil ? "转换" : "查看计划").font(.subheadline).foregroundStyle(.secondary)
            }
            .buttonStyle(.borderless)
        }
        .padding(.vertical, 4)
    }
    /// A prefilled row is written as a draft (undoable, kept in 今天的整理);
    /// a row without one is converted as before.
    private func convertRow(itemID: UUID?, hint: ConversionHint?) {
        guard let hint else {
            store.convertMemo(id: memo.id, itemID: itemID, day: day, category: category)
            return
        }
        guard var plan = PlanWorkflow.memoPlan(in: store.snapshot, memoID: memo.id, itemID: itemID,
                                               day: hint.day ?? day, category: hint.category ?? category) else { return }
        plan.aiOrigin = AIWriteText.prefillOrigin
        store.commitDrafts(adding: [plan], notice: "已转成计划，可撤销",
                           summary: AIWriteText.prefillConversion(title: plan.title))
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
#if DEBUG
                if AIQAFixture.scenario == "ai-memo-convert-edit" {
                    let order: [(UUID?, String)] = [(nil, "整则备忘")] + rows.map { ($0.id, $0.text) }
                    if let first = order.first(where: { result[$0.0] != nil }) {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
                            editingHint = ConversionHintRoute(itemID: first.0, title: first.1)
                        }
                    }
                }
#endif
            } catch {
                hintFailure = error.draftMessage
            }
        }
    }
}

private struct ConversionHintRoute: Identifiable {
    let itemID: UUID?
    let title: String
    var id: String { itemID?.uuidString ?? "memo" }
}

/// 这一行转成计划: the day and type of one prefilled row.
private struct ConversionHintEditor: View {
    let title: String
    let categories: [RecordCategory]
    /// The day and type changed here; nil for one left as it was.
    let save: (_ day: Date?, _ category: RecordCategory?) -> Void
    let clear: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var day: Date
    @State private var category: RecordCategory
    private let initialDay: Date
    private let initialCategory: RecordCategory

    init(title: String, day: Date, category: RecordCategory, categories: [RecordCategory],
         save: @escaping (_ day: Date?, _ category: RecordCategory?) -> Void, clear: @escaping () -> Void) {
        self.title = title
        self.categories = categories
        self.save = save
        self.clear = clear
        initialDay = day
        initialCategory = category
        _day = State(initialValue: day)
        _category = State(initialValue: category)
    }

    private func finish() {
        let changedDay = Calendar.current.isDate(day, inSameDayAs: initialDay) ? nil : Calendar.current.startOfDay(for: day)
        let changedCategory = category == initialCategory ? nil : category
        if changedDay != nil || changedCategory != nil { save(changedDay, changedCategory) }
        dismiss()
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(title).font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
                    DatePicker("日期", selection: $day, displayedComponents: .date)
                    Picker("类型", selection: $category) { ForEach(categories) { Text($0.displayName).tag($0) } }
                }
                Section {
                    Button("不用这行的预填") { clear(); dismiss() }
                        .font(.subheadline)
                        .foregroundStyle(Color.planSecondary)
                        .accessibilityIdentifier("memo.conversion.hint.clear")
                }
            }
            .scrollContentBackground(.hidden).background { InkWashBackground() }
            .navigationTitle("这一行转成计划").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成", action: finish).fontWeight(.semibold)
                        .accessibilityIdentifier("memo.conversion.hint.done")
                }
            }
        }
        .presentationDetents([.medium])
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
    @State private var nextWeekNotice: String?
    @State private var showsAIIntro = false
    private var key: String { PlanWorkflow.weekKey(week) }
    private var showsNextWeekOffer: Bool {
        AIDrafting.showsNextWeekOffer(reflection: reflection, week: week, now: Date(), showsEntries: ai.showsEntries,
                                      handled: ai.isNextWeekOfferHandled(key))
    }
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
                if showsNextWeekOffer {
                    NextWeekOfferStrip(accept: offerNextWeek, dismiss: {
                        withAnimation(.easeOut(duration: 0.2)) { ai.markNextWeekOfferHandled(key) }
                    })
                    .id("week.nextWeekOffer")
                    .transition(.opacity)
                    if let nextWeekNotice {
                        Text(nextWeekNotice).font(.footnote).foregroundStyle(.secondary)
                    }
                }
            }.planCard()
        }
        .sheet(isPresented: $nextWeekDrafts) {
            PlanDraftSheet(store: store, title: "下周草稿", subject: "按这一周的记录和“下周关注”拟的",
                           waitingText: "正在拟下周", origin: AIWriteText.nextWeekOrigin,
                           summary: { count, _ in AIWriteText.nextWeek(count: count) },
                           onAccept: { _ in ai.markNextWeekOfferHandled(key) },
                           load: { try await AIAssistant.shared.draftNextWeek(snapshot: store.snapshot, week: week, categories: workflowCategories()) })
        }
        .sheet(isPresented: $goalEditor) { WeeklyGoalEditor(store: store, week: week, goal: editingGoal) }
        .sheet(isPresented: $reflectionEditor) { WeeklyReflectionEditor(store: store, week: week, reflection: reflection) }
        .sheet(isPresented: $carryEditor) { NextWeekPlansSheet(store: store, week: week) }
        .aiIntroSheet(isPresented: $showsAIIntro)
    }
    /// 拟下周草稿 on the offer: the draft sheet, the introduction while nothing
    /// is set up, or the reason there is nothing to draft from.
    private func offerNextWeek() {
        guard ai.isReady else { showsAIIntro = true; return }
        guard !AIDrafting.weekRecord(snapshot: store.snapshot, week: week, now: Date()).plans.isEmpty else {
            nextWeekNotice = AIDraftCheckError.emptyWeek.errorDescription
            return
        }
        nextWeekNotice = nil
        nextWeekDrafts = true
    }
    private func metric(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 3) { Text(value).font(.subheadline.weight(.semibold)); Text(title).font(.caption).foregroundStyle(.secondary) }.frame(maxWidth: .infinity, alignment: .leading)
    }
    private func progressLine(_ title: String, value: Int, target: Int, unit: String) -> some View {
        VStack(spacing: 6) { HStack { Text(title); Spacer(); Text("\(value)/\(target) \(unit)") }.font(.caption); ProgressView(value: Double(min(value, target)), total: Double(target)).tint(.planPrimary) }
    }
}

/// Under 周复盘 once it is saved: an offer, dashed and stamped like a draft.
private struct NextWeekOfferStrip: View {
    let accept: () -> Void
    let dismiss: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            DraftSeal(size: 18, seed: 21)
            Text("复盘存好了，按「下周关注」拟一份下周计划？")
                .font(.footnote)
                .foregroundStyle(Color.planPrimary)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
            DraftCapsuleButton(title: "拟下周草稿", identifier: "week.nextWeekOffer.accept", action: accept)
            Button(action: dismiss) {
                Image(systemName: "xmark")
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(Color.planSecondary.opacity(0.7))
                    .frame(width: 30, height: 34)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("不用")
        }
        .padding(.leading, 10)
        .padding(.trailing, 2)
        .padding(.vertical, 4)
        .inkDraftSurface(cornerRadius: 12)
        .accessibilityIdentifier("week.nextWeekOffer")
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
    /// `initialDraft` opens the editor with a draft already in the draft area,
    /// as 本周来信's 放进复盘 does; it still has to be put into the two fields.
    init(store: PlanStore, week: Date, reflection: WeeklyReflection?, initialDraft: ReflectionDraft? = nil) {
        self.store = store; self.week = week; self.reflection = reflection
        _note = State(initialValue: reflection?.note ?? ""); _nextFocus = State(initialValue: reflection?.nextFocus ?? "")
        _draft = State(initialValue: initialDraft)
    }
    @State private var confirmsDiscard = false
    @ObservedObject private var ai = AISettingsStore.shared
    @State private var draft: ReflectionDraft?
    @State private var isDrafting = false
    @State private var draftFailure: String?
    @State private var showsAIIntro = false
    /// What the two fields held before a draft was put into them.
    @State private var beforeDraft: (note: String, nextFocus: String)?
    /// The draft was put into the two empty fields as the page opened.
    @State private var autoFilled = false
    @State private var didStartAutoDraft = false
    /// 本周排了…: counted once, from the same snapshot the draft was asked from.
    @State private var draftStats: String?
    private var hasEdits: Bool { note != (reflection?.note ?? "") || nextFocus != (reflection?.nextFocus ?? "") }
    fileprivate var fieldsAreEmpty: Bool {
        note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && nextFocus.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
    var body: some View {
        NavigationStack {
            Form {
                if ai.showsEntries { draftSection }
                Section("本周复盘") { TextField("做得好的事、遇到的问题", text: $note, axis: .vertical).lineLimit(5...12) }
                Section("下周关注") { TextField("给下周留下一句提醒", text: $nextFocus, axis: .vertical).lineLimit(3...8) }
            }
            .onAppear {
                if draft != nil, draftStats == nil {
                    draftStats = AIDrafting.weekStats(snapshot: store.snapshot, week: week).line
                }
#if DEBUG
                // ai-reflection-applied: two fields already written, then 起草复盘.
                if AIQAFixture.scenario == "ai-reflection-applied", fieldsAreEmpty, draft == nil, !isDrafting {
                    note = "周一到周三按计划复习，周四开始有点松。"
                    nextFocus = "先把顺延的事做掉。"
                }
#endif
                startAutoDraftIfNeeded()
#if DEBUG
                let scenario = ProcessInfo.processInfo.environment["MOJI_QA_SCENARIO"] ?? ""
                guard scenario.hasPrefix("ai-reflection"), draft == nil, !isDrafting else { return }
                runDraft(auto: scenario != "ai-reflection-applied")
#endif
            }
            .scrollContentBackground(.hidden).background { InkWashBackground() }
            .navigationTitle("周复盘").navigationBarTitleDisplayMode(.inline)
            .confirmDiscardEdits(isDirty: hasEdits, isPresented: $confirmsDiscard)
            .aiIntroSheet(isPresented: $showsAIIntro)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { IMETextInput.commit { if hasEdits { confirmsDiscard = true } else { dismiss() } } } }
                ToolbarItem(placement: .confirmationAction) { Button("保存") { IMETextInput.commit { store.saveWeeklyReflection(WeeklyReflection(id: reflection?.id ?? UUID(), weekStart: PlanWorkflow.weekKey(week), note: note, nextFocus: nextFocus)); dismiss() } }.fontWeight(.semibold) }
            }
        }
    }

    /// Set up, both fields empty and the week has plans: a draft is started
    /// without a tap. Nothing happens while nothing is set up.
    private func startAutoDraftIfNeeded() {
        guard ai.isReady, !didStartAutoDraft, draft == nil, !isDrafting, fieldsAreEmpty,
              !AIDrafting.weekRecord(snapshot: store.snapshot, week: week, now: Date()).plans.isEmpty else { return }
        didStartAutoDraft = true
        runDraft(auto: true)
    }
}

extension WeeklyReflectionEditor {
    /// The draft sits above the two fields, dashed and stamped. One put in
    /// without a tap can be cleared; one put in by 起草复盘 can be taken back.
    /// Saving is still the editor's own 保存.
    @ViewBuilder
    fileprivate var draftSection: some View {
        Section {
            if isDrafting {
                InkDraftWaiting(text: "正在起草")
            } else if let draft {
                VStack(alignment: .leading, spacing: 10) {
                    HStack(spacing: 7) {
                        DraftSeal(size: 20, seed: 2)
                        Text(autoFilled ? "墨记先放了一份草稿，可以直接改或清空"
                             : beforeDraft == nil ? "草稿，还没有放进两栏" : "已放进下面两栏，保存前可以改")
                            .font(.footnote).foregroundStyle(Color.planSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    if !autoFilled, beforeDraft == nil {
                        Text(draft.note).font(.subheadline).foregroundStyle(Color.planPrimary).fixedSize(horizontal: false, vertical: true)
                        if !draft.nextFocus.isEmpty {
                            Text("下周关注：\(draft.nextFocus)").font(.subheadline).foregroundStyle(Color.planPrimary).fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    // Counted on the device, not by the model.
                    if let draftStats {
                        Text(draftStats)
                            .font(.caption).foregroundStyle(Color.planSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                            .accessibilityIdentifier("reflection.draft.stats")
                    }
                    if !draft.evidence.isEmpty {
                        InkBrushDivider(animated: false, seed: 17).opacity(0.4)
                        ReflectionEvidenceList(evidence: draft.evidence, planLines: evidenceLines,
                                               expandsFirst: expandsFirstEvidence)
                    }
                    HStack(spacing: 18) {
                        if autoFilled {
                            Button("清空") { clearAutoDraft() }.font(.subheadline.weight(.semibold))
                                .accessibilityIdentifier("reflection.draft.clear")
                        } else if beforeDraft == nil {
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
                Button("起草复盘") { if ai.isReady { runDraft() } else { showsAIIntro = true } }.accessibilityIdentifier("reflection.draft.run")
                if let draftFailure { Text(draftFailure).font(.footnote).foregroundStyle(.secondary) }
            }
        } footer: {
            if draft == nil, !isDrafting { Text("按这一周的计划记录起草，并注明依据的是哪几条计划。") }
        }
    }

    /// The plans behind a point as they are now: 背单词 · 11月18日 周三 · 已完成.
    fileprivate func evidenceLines(_ evidence: ReflectionDraft.Evidence) -> [String] {
        let now = Date()
        return evidence.planIDs.compactMap { id in store.checkInItems.first { $0.id == id } }
            .map { AIDrafting.evidencePlanLine($0, now: now) }
    }

    fileprivate var expandsFirstEvidence: Bool {
#if DEBUG
        return AIQAFixture.scenario == "ai-reflection-auto"
#else
        return false
#endif
    }

    /// `auto`: started as the page opened. The draft goes into the two fields
    /// only if they are still empty; typing meanwhile keeps it in the card.
    /// From 起草复盘 it goes straight into the fields and can be taken back.
    fileprivate func runDraft(auto: Bool = false) {
        isDrafting = true
        draftFailure = nil
        Task { @MainActor in
            defer { isDrafting = false }
            do {
                let snapshot = store.snapshot
                let result = try await AIAssistant.shared.draftReflection(snapshot: snapshot, week: week)
                draftStats = AIDrafting.weekStats(snapshot: snapshot, week: week).line
                draft = result
                if auto {
                    if fieldsAreEmpty {
                        fill(result)
                        autoFilled = true
                    }
                } else {
                    applyDraft(result)
                }
            } catch AIRequestError.emptyText {
                draftFailure = "这一周还没有计划记录，没有可以起草的内容。"
            } catch {
                draftFailure = error.draftMessage
            }
        }
    }

    fileprivate func fill(_ draft: ReflectionDraft) {
        note = draft.note
        if !draft.nextFocus.isEmpty { nextFocus = draft.nextFocus }
    }

    fileprivate func applyDraft(_ draft: ReflectionDraft) {
        beforeDraft = (note, nextFocus)
        autoFilled = false
        fill(draft)
    }

    fileprivate func clearAutoDraft() {
        note = ""
        nextFocus = ""
        autoFilled = false
        draft = nil
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
