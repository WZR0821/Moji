package com.raydon.moji.android

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.raydon.moji.android.ai.split
import com.raydon.moji.android.data.PomodoroState
import com.raydon.moji.android.platform.AlarmScheduler
import com.raydon.moji.android.platform.AndroidCalendarIntegration
import com.raydon.moji.android.platform.BackupService
import com.raydon.moji.android.widget.updateAll
import com.raydon.moji.core.CheckInItem
import com.raydon.moji.core.CountdownEvent
import com.raydon.moji.core.MemoChecklistItem
import com.raydon.moji.core.MemoItem
import com.raydon.moji.core.PlanSnapshot
import com.raydon.moji.core.MojiJson
import com.raydon.moji.core.PomodoroPolicy
import com.raydon.moji.core.TimeRecord
import com.raydon.moji.core.PlanTemplate
import com.raydon.moji.core.PlanWorkflow
import com.raydon.moji.core.PlanWorkflowUndo
import com.raydon.moji.core.WeeklyGoal
import com.raydon.moji.core.WeeklyReflection
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BackupPreview(val json: String, val snapshot: PlanSnapshot)

class MojiViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MojiApplication
    val snapshot: StateFlow<PlanSnapshot> = app.repository.snapshot
    val pomodoro: StateFlow<PomodoroState> = app.settings.pomodoro
    val tickMillis = MutableStateFlow(System.currentTimeMillis())
    val message = MutableStateFlow<String?>(null)
    val appearanceMode = MutableStateFlow(app.settings.appearance)
    val settingsRevision = app.settings.revision
    val selectedTab = MutableStateFlow(0)
    val showFocus = MutableStateFlow(false)
    val workflowUndo = MutableStateFlow<PlanWorkflowUndo?>(null)
    private var finishingPomodoro = false
    private var startingPomodoro = false
    private val pendingSaveIDs = mutableSetOf<String>()
    val backupBusy = MutableStateFlow(false)
    val backupNotice = MutableStateFlow<String?>(null)
    val pendingBackup = MutableStateFlow<BackupPreview?>(null)
    val storageError = app.repository.loadError
    private var automaticBackupJob: Job? = null
    private val mutationErrors = CoroutineExceptionHandler { _, error ->
        message.value = "操作未完成：${error.message ?: "请稍后重试"}"
    }

    fun openFocus() { showFocus.value = true; selectedTab.value = 2 }
    fun openPlans() { selectedTab.value = 0 }
    fun openReview() { selectedTab.value = 3 }

    /** 说一句添加 (`moji://say`): waiting for the plan page to focus the field and start the microphone. */
    val sayRequested = MutableStateFlow(false)
    /** 分享 (`moji://share`): bumped so the plan page looks into the inbox even if it stayed on screen. */
    val shareRequests = MutableStateFlow(0)

    fun startSaying() { selectedTab.value = 0; sayRequested.value = true }
    fun checkShareInbox() { selectedTab.value = 0; shareRequests.value += 1 }
    val customCategories: List<String> get() = app.settings.customCategories
    fun addCategory(name: String) {
        val normalized = name.trim().take(24)
        if (normalized.isBlank()) return
        app.settings.customCategories = (customCategories + normalized).distinct()
        app.settings.defaultCategory = "custom:$normalized"
    }
    fun removeCategory(name: String) {
        app.settings.customCategories = customCategories.filterNot { it == name }
        if (app.settings.defaultCategory == "custom:$name") app.settings.defaultCategory = "study"
    }

    init {
        viewModelScope.launch(mutationErrors) {
            while (true) {
                tickMillis.value = System.currentTimeMillis()
                settlePomodoroIfNeeded()
                delay(1_000)
            }
        }
    }

    val carriesOver: Boolean get() = app.settings.carriesOver
    val appearance: String get() = app.settings.appearance
    val notificationsEnabled: Boolean get() = app.settings.notificationsEnabled
    val defaultFocusMinutes: Int get() = app.settings.focusMinutes
    val shortBreakMinutes: Int get() = app.settings.shortBreakMinutes
    val longBreakMinutes: Int get() = app.settings.longBreakMinutes
    val longBreakInterval: Int get() = app.settings.longBreakInterval
    val longBreakEnabled: Boolean get() = app.settings.longBreakEnabled
    val autoStartBreaks: Boolean get() = app.settings.autoStartBreaks
    val autoStartFocus: Boolean get() = app.settings.autoStartFocus
    val soundEnabled: Boolean get() = app.settings.soundEnabled
    val hapticsEnabled: Boolean get() = app.settings.hapticsEnabled
    val keepScreenAwake: Boolean get() = app.settings.keepScreenAwake
    val defaultCategory: String get() = app.settings.defaultCategory
    val defaultScheduleKind: String get() = app.settings.defaultScheduleKind
    val defaultPlannedDurationEnabled: Boolean get() = app.settings.defaultPlannedDurationEnabled
    val defaultPlannedMinutes: Int get() = app.settings.defaultPlannedMinutes
    val backupRetentionDays: Int get() = app.settings.backupRetentionDays
    val backupFolderName: String? get() = app.settings.backupTreeUri?.let { Uri.parse(it).lastPathSegment?.substringAfter(':')?.substringAfterLast('/') ?: "已选择文件夹" }
    val lastBackupAt: Long get() = app.settings.lastBackupAt
    val allDayReminderHour: Int get() = app.settings.allDayReminderHour
    val paperTextureEnabled: Boolean get() = app.settings.paperTextureEnabled
    val inkMotionLevel: String get() = app.settings.inkMotionLevel
    fun durationForPhase(phase: String): Int = if (phase == "focus" && pomodoro.value.phase == "focus" && pomodoro.value.durationIsPlanOwned) pomodoro.value.phaseDurationSeconds else app.settings.durationFor(phase)

    fun savePlan(
        existingId: String? = null,
        title: String,
        note: String,
        date: LocalDate,
        time: LocalTime,
        scheduleKind: String,
        category: String,
        plannedMinutes: Int,
        repeatRule: String,
        weekdays: List<Int>,
        reminderMinutes: Int?,
        calendarSync: Boolean,
        kind: String = "planned",
        actualStart: java.time.LocalDateTime? = null,
        actualEnd: java.time.LocalDateTime? = null,
        plannedDurationEnabled: Boolean = true,
        /** Applied to the plan before it is written, in the same save (the quick-entry chips). */
        transform: ((CheckInItem) -> CheckInItem)? = null,
        onSaved: (() -> Unit)? = null,
        /** The plan as written, after [onSaved]. */
        onSavedPlan: ((CheckInItem) -> Unit)? = null,
    ) {
        if (title.isBlank()) return
        if (kind == "completed" && (actualStart == null || actualEnd == null || (scheduleKind == "exactTime" && !actualEnd.isAfter(actualStart)) || actualEnd.isBefore(actualStart))) {
            message.value = "结束时间必须晚于开始时间"; return
        }
        saveOperation(existingId ?: UUID.randomUUID().toString()) {
            val now = Instant.now()
            val old = existingId?.let { id -> app.repository.freshSnapshot().checkInItems.firstOrNull { it.id == id } }
            var item = CheckInItem(
                id = existingId ?: UUID.randomUUID().toString(),
                title = title.trim(),
                category = category,
                kind = kind,
                scheduledStart = if (kind == "completed") actualStart!!.atZone(ZoneId.systemDefault()).toInstant().toString() else ZonedDateTime.of(date, if (scheduleKind == "exactTime") time else LocalTime.of(when (scheduleKind) {
                    "morning" -> 9; "afternoon" -> 14; "evening" -> 19; else -> 0
                }, 0), ZoneId.systemDefault()).toInstant().toString(),
                plannedMinutes = plannedMinutes.coerceAtLeast(1),
                note = note,
                status = if (kind == "completed") "completed" else old?.status ?: "planned",
                actualStartDate = actualStart?.atZone(ZoneId.systemDefault())?.toInstant()?.toString() ?: old?.actualStartDate,
                actualEndDate = actualEnd?.atZone(ZoneId.systemDefault())?.toInstant()?.toString() ?: old?.actualEndDate,
                completedAt = if (kind == "completed") (actualEnd ?: actualStart)?.atZone(ZoneId.systemDefault())?.toInstant()?.toString() ?: now.toString() else old?.completedAt,
                calendarSyncEnabled = calendarSync,
                calendarEventIdentifier = old?.calendarEventIdentifier,
                createdAt = old?.createdAt ?: now.toString(),
                scheduleKind = scheduleKind,
                detailsConfigured = true,
                repeatRule = repeatRule,
                repeatWeekdays = weekdays,
                seriesID = old?.seriesID,
                reminderMinutesBefore = reminderMinutes,
                generatedFromOccurrenceID = old?.generatedFromOccurrenceID,
                plannedDurationEnabled = plannedDurationEnabled,
                isArchived = old?.isArchived ?: false,
                sourceMemoID = old?.sourceMemoID,
                sourceMemoChecklistItemID = old?.sourceMemoChecklistItemID,
                aiOrigin = old?.aiOrigin,
            )
            transform?.let { item = it(item) }
            if (calendarSync && item.calendarEventIdentifier == null) {
                val eventId = withContext(Dispatchers.IO) { AndroidCalendarIntegration.addPlan(app, item) }
                item = item.copy(calendarEventIdentifier = eventId)
            }
            if (kind == "completed" && actualStart != null && actualEnd != null) {
                val oldRecord = snapshot.value.records.firstOrNull { it.checkInItemID == item.id }
                app.repository.saveCompletedPlan(item,
                    TimeRecord(
                        id = oldRecord?.id ?: UUID.randomUUID().toString(),
                        title = item.title,
                        category = item.category,
                        startDate = actualStart.atZone(ZoneId.systemDefault()).toInstant().toString(),
                        endDate = actualEnd.atZone(ZoneId.systemDefault()).toInstant().toString(),
                        note = item.note,
                        checkInItemID = item.id,
                        scheduleKind = scheduleKind,
                    )
                )
            } else app.repository.savePlan(item)
            afterMutation()
            onSaved?.invoke()
            onSavedPlan?.invoke(item)
        }
    }

    fun togglePlan(id: String) = launchMutation { app.repository.togglePlan(id) }
    fun quickUpdatePlan(id: String, change: com.raydon.moji.android.data.PlanQuickChange, onSaved: () -> Unit) = saveOperation(id) {
        app.repository.mutate { state ->
            val original = state.checkInItems.firstOrNull { it.id == id } ?: error("这项计划已被删除")
            val updated = change.applyTo(original) ?: error("请检查填写的内容")
            state.copy(checkInItems = state.checkInItems.map { if (it.id == id) updated else it })
        }
        afterMutation()
        onSaved()
        if (change is com.raydon.moji.android.data.PlanQuickChange.Date && change.day != LocalDate.now()) {
            message.value = "已移到 ${change.day.monthValue}月${change.day.dayOfMonth}日，可在日历或计划库查看"
        }
    }
    fun skipPlan(id: String) = launchMutation { app.repository.skipPlan(id) }
    fun postponePlan(id: String) = launchMutation { app.repository.postponePlan(id) }
    fun deletePlan(id: String) = viewModelScope.launch(mutationErrors) {
        app.repository.deletePlan(id)
        if (pomodoro.value.linkedPlanId == id) app.settings.updatePomodoro(pomodoro.value.copy(linkedPlanId = ""))
        afterMutation()
    }

    fun convertMemo(memoId: String, itemId: String?, day: LocalDate, category: String) = launchMutation {
        app.repository.mutate { state -> PlanWorkflow.convertMemo(state, memoId, itemId, UUID.randomUUID().toString(),
            day.atStartOfDay(ZoneId.systemDefault()).toInstant().toString(), Instant.now().toString(), category) }
    }

    fun copyPlan(id: String, day: LocalDate, onSaved: (() -> Unit)? = null) = saveOperation("copy:$id") {
        app.repository.mutate { state ->
            val item = state.checkInItems.firstOrNull { it.id == id } ?: return@mutate state
            val time = Instant.parse(item.scheduledStart).atZone(ZoneId.systemDefault()).toLocalTime()
            state.copy(checkInItems = state.checkInItems + PlanWorkflow.copied(item, UUID.randomUUID().toString(),
                day.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toString(), Instant.now().toString()))
        }
        afterMutation()
        onSaved?.invoke()
        message.value = "已复制为新的计划"
    }

    fun saveTemplate(id: String) = launchMutation {
        app.repository.mutate { state ->
            val item = state.checkInItems.firstOrNull { it.id == id } ?: return@mutate state
            val date = Instant.parse(item.scheduledStart).atZone(ZoneId.systemDefault())
            val candidate = PlanTemplate(UUID.randomUUID().toString(), item.title, item.category, item.note,
                item.scheduleKind ?: "exactTime", date.hour, date.minute, item.plannedMinutes,
                item.plannedDurationEnabled ?: (item.scheduleKind == "exactTime" && item.detailsConfigured != false),
                item.reminderMinutesBefore, Instant.now().toString())
            val duplicate = state.planTemplates.any { it.copy(id = candidate.id, createdAt = candidate.createdAt) == candidate }
            if (duplicate) state else state.copy(planTemplates = state.planTemplates + candidate)
        }
        message.value = "已保存到计划模板"
    }

    fun applyTemplate(id: String, day: LocalDate) = launchMutation {
        app.repository.mutate { state ->
            val template = state.planTemplates.firstOrNull { it.id == id } ?: return@mutate state
            val item = CheckInItem(id = UUID.randomUUID().toString(), title = template.title, category = template.category,
                scheduledStart = day.atTime(template.hour, template.minute).atZone(ZoneId.systemDefault()).toInstant().toString(),
                createdAt = Instant.now().toString(), note = template.note, scheduleKind = template.scheduleKind,
                plannedMinutes = template.plannedMinutes, plannedDurationEnabled = template.plannedDurationEnabled,
                reminderMinutesBefore = template.reminderMinutesBefore)
            state.copy(checkInItems = state.checkInItems + item)
        }
        message.value = "已从模板添加计划"
    }

    fun deleteTemplate(id: String) = launchMutation { app.repository.mutate { it.copy(planTemplates = it.planTemplates.filterNot { t -> t.id == id }) } }

    fun batchPlans(ids: Set<String>, day: LocalDate? = null, category: String? = null,
                   archived: Boolean? = null, nextWeekOf: LocalDate? = null) = launchMutation {
        var undo: PlanWorkflowUndo? = null
        app.repository.mutate { state ->
            val result = PlanWorkflow.batch(state, ids, ZoneId.systemDefault().id,
                day?.let { kotlinx.datetime.LocalDate.parse(it.toString()) }, category, archived,
                nextWeekOf?.let { kotlinx.datetime.LocalDate.parse(it.toString()) },
                pomodoro.value.linkedPlanId.takeIf { pomodoro.value.phase == "focus" && (pomodoro.value.running || pomodoro.value.accumulatedSeconds > 0) })
            undo = result.second; result.first
        }
        workflowUndo.value = undo?.takeIf { it.before.isNotEmpty() }
        message.value = if (undo?.before?.isNotEmpty() == true) "已整理 ${undo?.before?.size} 个计划，可撤销" else "没有可整理的计划，专注中的计划不会移动"
    }

    fun undoWorkflow() = launchMutation {
        workflowUndo.value?.let { undo -> app.repository.mutate { undo.apply(it, pomodoro.value.linkedPlanId.takeIf { pomodoro.value.phase == "focus" && (pomodoro.value.running || pomodoro.value.accumulatedSeconds > 0) }) } }
        workflowUndo.value = null
        message.value = "已撤销整理；后续已修改的计划保持不变"
    }

    /** The plan a running focus session holds; drafts never move or remove it. */
    private val focusBlockedPlanId: String?
        get() = pomodoro.value.linkedPlanId.takeIf { it.isNotBlank() && pomodoro.value.phase == "focus" && (pomodoro.value.running || pomodoro.value.accumulatedSeconds > 0) }

    val draftUndo = MutableStateFlow<com.raydon.moji.android.ai.PlanDraftUndo?>(null)
    val draftNotice = MutableStateFlow<String?>(null)
    /** A new number for every notice, so the same words twice still get their full time on screen. */
    val draftNoticeSerial = MutableStateFlow(0L)
    private fun showDraftNotice(text: String?) { draftNotice.value = text; draftNoticeSerial.value += 1 }

    /**
     * Writes accepted drafts in one step and remembers how to take them back.
     * [changed] replaces plans that already exist; [added] appends new ones.
     */
    /** The 今天的整理 entry of the write the undo bar can still take back. */
    private var draftWriteId: String? = null

    fun commitDrafts(added: List<CheckInItem> = emptyList(), changed: List<CheckInItem> = emptyList(), notice: String,
                     summary: String? = null, addedMemos: List<MemoItem> = emptyList(),
                     /** The notice and summary from how many plans were really changed (a plan being timed is not). */
                     describe: ((changedCount: Int) -> Pair<String, String?>)? = null,
                     onSaved: (() -> Unit)? = null) = launchMutation {
        commitDraftsNow(added, changed, notice, summary, addedMemos, describe, onSaved)
    }

    private suspend fun commitDraftsNow(added: List<CheckInItem>, changed: List<CheckInItem>, notice: String, summary: String?,
                                        addedMemos: List<MemoItem> = emptyList(),
                                        describe: ((Int) -> Pair<String, String?>)? = null, onSaved: (() -> Unit)? = null) {
        val changedIds = mutableListOf<String>()
        val before = mutableListOf<CheckInItem>()
        val insertedIds = mutableListOf<String>()
        val insertedMemoIds = mutableListOf<String>()
        app.repository.mutate { state ->
            changedIds.clear(); before.clear(); insertedIds.clear(); insertedMemoIds.clear()
            val items = state.checkInItems.toMutableList()
            for (item in changed) {
                val index = items.indexOfFirst { it.id == item.id }
                if (index < 0 || items[index] == item || state.activeSession?.checkInItemID == item.id || item.id == focusBlockedPlanId) continue
                before += items[index]; changedIds += item.id; items[index] = item
            }
            for (item in added) if (items.none { it.id == item.id }) { items += item; insertedIds += item.id }
            val memos = state.memos.toMutableList()
            for (memo in addedMemos) if (memos.none { it.id == memo.id }) { memos += memo; insertedMemoIds += memo.id }
            state.copy(checkInItems = items, memos = memos)
        }
        if (insertedIds.isEmpty() && changedIds.isEmpty() && insertedMemoIds.isEmpty()) return
        // Remember the plans as they were stored, so a later edit is told apart from the draft itself.
        val fresh = app.repository.freshSnapshot()
        val stored = fresh.checkInItems.associateBy { it.id }
        val storedMemos = fresh.memos.associateBy { it.id }
        val addedPlans = insertedIds.mapNotNull { stored[it] }
        val afterPlans = changedIds.mapNotNull { stored[it] }
        val memos = insertedMemoIds.mapNotNull { storedMemos[it] }
        val (text, line) = describe?.invoke(changedIds.size) ?: (notice to summary)
        draftUndo.value = com.raydon.moji.android.ai.PlanDraftUndo(added = addedPlans, changed = PlanWorkflowUndo(before.toList(), afterPlans), addedMemos = memos)
        showDraftNotice(text)
        // 今天的整理 keeps every write, so it can be taken back later in the day too.
        draftWriteId = com.raydon.moji.android.ai.AIWriteLog.shared.record(line ?: text, addedPlans, before.toList(), afterPlans, addedMemos = memos)
        onSaved?.invoke()
    }

    fun undoDrafts() = launchMutation {
        val undo = draftUndo.value ?: return@launchMutation
        var count = 0
        app.repository.mutate { state -> undo.apply(state, focusBlockedPlanId).let { (next, undone) -> count = undone; next } }
        draftUndo.value = null
        draftWriteId?.let { com.raydon.moji.android.ai.AIWriteLog.shared.markUndone(it) }
        draftWriteId = null
        showDraftNotice(com.raydon.moji.android.ai.AIWriteText.undoResult(count, undo.total))
    }

    /**
     * Takes back one write from 今天的整理, by the same rule as the undo bar:
     * plans changed since stay as they are. [onResult] gets the sentence to show.
     */
    fun undoWrite(id: String, onResult: (String) -> Unit) = launchMutation {
        val log = com.raydon.moji.android.ai.AIWriteLog.shared
        val entry = log.entry(id)?.takeIf { !it.undone } ?: return@launchMutation
        var count = 0
        app.repository.mutate { state -> entry.undo.apply(state, focusBlockedPlanId).let { (next, undone) -> count = undone; next } }
        log.markUndone(id)
        if (draftWriteId == id) {
            draftUndo.value = null
            draftNotice.value = null
            draftWriteId = null
        }
        onResult(com.raydon.moji.android.ai.AIWriteText.undoResult(count, entry.total))
    }

    fun clearDraftNotice() { draftNotice.value = null; draftUndo.value = null; draftWriteId = null }

    // MARK: - 1.10.0: what the plan and moments pages show for a while, kept here so a tab switch
    // or a rotation does not lose it (spec A10). Nothing of it is saved; a killed process forgets it.

    /** What was read out of plans typed a moment ago, by plan id, with what each plan said then. */
    val entrySuggestions = MutableStateFlow<Map<String, com.raydon.moji.android.ai.EntrySuggestionRecord>>(emptyMap())
    /** Plans whose sentence the model is reading. */
    val readingEntryIds = MutableStateFlow<Set<String>>(emptySet())
    /** Plans that were given a strip read on the device; once it is gone the model's reading is not shown either. */
    private val localEntryIds = mutableSetOf<String>()
    /** 存为模板 offered under plans just written by hand: how many times each one's title was written, by plan id. */
    val templateHints = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** 时刻: a day just added, offered a preparation plan. */
    val preparationOffer = MutableStateFlow<CountdownEvent?>(null)
    /** Photographed QA screens start from nothing every launch, as iOS's QA suite does. */
    private val qaTemplateHintDismissed = mutableSetOf<String>()
    private val templateHintDismissed: Set<String>
        get() = if (com.raydon.moji.android.ai.AIQAFixture.scenario.isNotEmpty()) qaTemplateHintDismissed
            else app.settings.templateHintDismissed.toSet()

    init {
        // A suggestion for a plan deleted or changed since (date, time, length, type, repeat, title, status) is dropped.
        viewModelScope.launch {
            snapshot.collect { state ->
                val records = entrySuggestions.value
                val stale = records.filter { (id, record) -> state.checkInItems.firstOrNull { it.id == id }?.let { !record.isCurrent(it) } ?: true }.keys
                // A changed plan's reading would be dropped when it comes back: no wait shown for it either.
                if (stale.isNotEmpty()) {
                    entrySuggestions.value = records - stale
                    readingEntryIds.value = readingEntryIds.value - stale
                    localEntryIds -= stale
                }
                // A hint goes with its plan once that is deleted or archived.
                val hints = templateHints.value
                val gone = hints.keys.filter { id -> state.checkInItems.firstOrNull { it.id == id }?.isArchived != false }
                if (gone.isNotEmpty()) templateHints.value = hints - gone.toSet()
            }
        }
    }

    /** The strip goes; if it was read on the device, so does the wait for the model's reading, and its answer. */
    fun dropEntrySuggestion(id: String) {
        entrySuggestions.value = entrySuggestions.value - id
        if (id in localEntryIds) readingEntryIds.value = readingEntryIds.value - id
    }

    /** The usual length of a [category] plan from its history (N1), or null. */
    fun historyMinutes(category: String): Int? = com.raydon.moji.android.ai.PlanDurationHistory.minutes(category, snapshot.value)

    /**
     * 首页输入框 › 回车: the plan is saved with the chips tapped ([chosen]). When none was
     * tapped but the device read something, [local] goes under the new row at once; the
     * model reads the sentence too when it is set up. [onSaved] runs after the save.
     */
    fun addQuickPlan(id: String, text: String, today: LocalDate, chosen: com.raydon.moji.android.ai.EntrySuggestion?,
                     local: com.raydon.moji.android.ai.EntrySuggestion?, categories: List<String>, onSaved: () -> Unit) {
        val movedTo = chosen?.day?.takeIf { it != today }
        savePlan(existingId = id, title = text, note = "", date = today, time = LocalTime.MIDNIGHT,
            scheduleKind = "allDay", category = defaultCategory, plannedMinutes = 25,
            repeatRule = "never", weekdays = emptyList(), reminderMinutes = null, calendarSync = false, plannedDurationEnabled = false,
            transform = { plan ->
                var item = chosen?.applied(plan) ?: plan
                // No length given: the usual one for its type, when there is one (N1).
                if (com.raydon.moji.android.ai.AIDrafting.plannedDurationMinutes(item) == null) historyMinutes(item.category)?.let { item = item.copy(plannedMinutes = it) }
                item
            },
            onSaved = onSaved,
            onSavedPlan = { plan ->
                movedTo?.let { message.value = "已加到${com.raydon.moji.android.ai.AIDrafting.dayText(it, LocalDate.now())}，可在日历查看" }
                // A plan moved off today has no row here to hang a suggestion under.
                if (movedTo == null) {
                    local?.removingMatches(plan)?.takeIf { !it.isEmpty }?.let { suggestion ->
                        localEntryIds += plan.id
                        entrySuggestions.value = entrySuggestions.value + (plan.id to com.raydon.moji.android.ai.EntrySuggestionRecord(
                            suggestion, com.raydon.moji.android.ai.EntryStamp.of(plan), isLocal = true))
                    }
                    readQuickEntry(plan, text, categories)
                    // The hint hangs under the new row too, so not for a plan moved off today (N3).
                    offerTemplate(plan)
                }
            })
    }

    /**
     * If the sentence carries a time, a length or several things and a model is set up,
     * ask what it says and offer what would still change the plan. The answer replaces
     * the device's strip while that is still there; a failure leaves it.
     */
    private fun readQuickEntry(plan: CheckInItem, text: String, categories: List<String>) {
        val store = com.raydon.moji.android.ai.AISettingsStore.shared
        if (!store.isReady || !store.settings.value.readsQuickEntry || !com.raydon.moji.android.ai.AIDrafting.isWorthReading(text)) return
        val id = plan.id
        val asAdded = com.raydon.moji.android.ai.EntryStamp.of(plan)
        readingEntryIds.value = readingEntryIds.value + id
        viewModelScope.launch {
            // A failed reading is not worth a message: the plan is there either way.
            val reading = try { com.raydon.moji.android.ai.AIAssistant.shared.readEntry(text, categories) }
                catch (cancelled: CancellationException) { throw cancelled } catch (error: Throwable) { null }
            readingEntryIds.value = readingEntryIds.value - id
            if (id in localEntryIds && id !in entrySuggestions.value) return@launch
            val current = snapshot.value.checkInItems.firstOrNull { it.id == id } ?: return@launch
            val stamp = com.raydon.moji.android.ai.EntryStamp.of(current)
            if (stamp != asAdded) { dropEntrySuggestion(id); return@launch }
            val suggestion = reading?.removingMatches(current) ?: return@launch
            if (suggestion.isEmpty) return@launch
            entrySuggestions.value = entrySuggestions.value + (id to com.raydon.moji.android.ai.EntrySuggestionRecord(suggestion, stamp, isLocal = false))
        }
    }

    fun applyEntrySuggestion(id: String) {
        val record = entrySuggestions.value[id] ?: return
        dropEntrySuggestion(id)
        val plan = snapshot.value.checkInItems.firstOrNull { it.id == id } ?: return
        val suggestion = record.suggestion
        val updated = suggestion.applied(plan) ?: return
        val notice = suggestion.day?.let { "已移到${com.raydon.moji.android.ai.AIDrafting.dayText(it, LocalDate.now())}，可在日历查看" } ?: "已按建议调整"
        val fields = com.raydon.moji.android.ai.AIWriteText.changedFields(plan, updated)
        commitDrafts(changed = listOf(updated), notice = notice,
            summary = if (fields.isEmpty()) null else com.raydon.moji.android.ai.AIWriteText.entryChange(plan.title, fields))
    }

    /** 拆成 N 条: one write, undoable; the fields read go on every part (A2). */
    fun splitEntry(id: String) {
        val record = entrySuggestions.value[id] ?: return
        dropEntrySuggestion(id)
        val plan = snapshot.value.checkInItems.firstOrNull { it.id == id && it.kind == "planned" } ?: return
        val parts = record.suggestion.parts
        val (renamed, rest) = record.suggestion.split(plan) ?: return
        commitDrafts(added = rest, changed = listOf(renamed), notice = "已拆成 ${parts.size} 条",
            summary = com.raydon.moji.android.ai.AIWriteText.entrySplit(parts.size, plan.title))
    }

    /** 存为模板 under a plan written by hand whose title keeps coming back (N3). */
    fun offerTemplate(plan: CheckInItem) = viewModelScope.launch(mutationErrors) {
        val state = app.repository.freshSnapshot()
        val count = com.raydon.moji.android.ai.TemplateHint.count(plan, state, templateHintDismissed, handWritten = true) ?: return@launch
        templateHints.value = templateHints.value + (plan.id to count)
    }

    fun saveHintTemplate(id: String) {
        templateHints.value = templateHints.value - id
        saveTemplate(id)
    }

    fun dismissTemplateHint(id: String) {
        templateHints.value = templateHints.value - id
        val title = snapshot.value.checkInItems.firstOrNull { it.id == id }?.title ?: return
        if (com.raydon.moji.android.ai.AIQAFixture.scenario.isNotEmpty()) qaTemplateHintDismissed += com.raydon.moji.android.ai.TemplateHint.normalized(title)
        else app.settings.dismissTemplateHint(title)
    }

    /** Offered once, a moment after a day is added, and only when there is time to prepare. */
    fun offerPreparation(event: CountdownEvent, daysLeft: Long) {
        if (!com.raydon.moji.android.ai.AISettingsStore.shared.isReady || daysLeft < 2) return
        viewModelScope.launch { delay(450); preparationOffer.value = event }
    }

    /** Being timed right now, by its own session or by the pomodoro: a draft does not archive or rewrite it. */
    fun isBeingFocused(id: String): Boolean = snapshot.value.activeSession?.checkInItemID == id || focusBlockedPlanId == id

    /** 顺延印 › 放下: archived in one write, undoable and kept in 今天的整理, as 晚间收尾 does (A7). */
    fun dropPlan(id: String) {
        // Only a plan still waiting; one being timed stays as it is, and says why.
        val plan = snapshot.value.checkInItems.firstOrNull { it.id == id && it.kind == "planned" && it.status == "planned" } ?: return
        if (isBeingFocused(id)) { message.value = "这条计划正在专注，先结束再放下。"; return }
        commitDrafts(changed = listOf(plan.copy(isArchived = true)), notice = "已放下，可撤销",
            summary = com.raydon.moji.android.ai.AIWriteText.dropped(plan.title))
    }

    /** The pomodoro with [plan], the way a row's 去专注 opens it. */
    fun focusOn(plan: CheckInItem) {
        if (!pomodoro.value.hasActiveFocus()) { selectPomodoroPhase("focus"); linkPomodoro(plan) }
        openFocus()
    }

    /**
     * 备忘转计划 with a row's own (or edited) date and type: one write, undoable, marked
     * 按备忘预填 (B5). The same row is never turned into a plan twice.
     */
    fun convertMemoWithHint(memoId: String, itemId: String?, day: LocalDate, category: String) = saveOperation("convert:$memoId:$itemId") {
        val state = app.repository.freshSnapshot()
        val converted = PlanWorkflow.convertMemo(state, memoId, itemId, UUID.randomUUID().toString(),
            day.atStartOfDay(ZoneId.systemDefault()).toInstant().toString(), Instant.now().toString(), category)
        val plan = converted.checkInItems.lastOrNull()?.takeIf {
            converted.checkInItems.size > state.checkInItems.size && it.sourceMemoID == memoId && it.sourceMemoChecklistItemID == itemId
        } ?: return@saveOperation
        commitDraftsNow(listOf(plan.copy(aiOrigin = com.raydon.moji.android.ai.AIWriteText.memoHintOrigin)), emptyList(),
            "已转成计划，可撤销", com.raydon.moji.android.ai.AIWriteText.memoHinted(plan.title))
        afterMutation()
    }

    fun saveWeeklyGoal(goal: WeeklyGoal, onSaved: (() -> Unit)? = null) = saveOperation(goal.id) {
        if ((goal.targetCount == null && goal.targetMinutes == null) || goal.targetCount?.let { it < 1 } == true || goal.targetMinutes?.let { it < 1 } == true) return@saveOperation
        app.repository.mutate { state -> state.copy(weeklyGoals = state.weeklyGoals.filterNot { it.id == goal.id || (it.weekStart == goal.weekStart && it.category == goal.category) } + goal) }
        afterMutation(); onSaved?.invoke()
    }

    fun deleteWeeklyGoal(id: String) = launchMutation { app.repository.mutate { it.copy(weeklyGoals = it.weeklyGoals.filterNot { goal -> goal.id == id }) } }

    fun saveWeeklyReflection(reflection: WeeklyReflection, onSaved: (() -> Unit)? = null) = saveOperation(reflection.id) {
        app.repository.mutate { it.copy(weeklyReflections = it.weeklyReflections.filterNot { old -> old.weekStart == reflection.weekStart } + reflection) }
        afterMutation(); onSaved?.invoke()
    }

    fun saveMemo(
        existingId: String?,
        title: String,
        content: String,
        checklist: Boolean,
        oldItems: List<MemoChecklistItem> = emptyList(),
        checklistItems: List<MemoChecklistItem>? = null,
        isPinned: Boolean? = null,
        onSaved: (() -> Unit)? = null,
    ) {
        saveOperation(existingId ?: UUID.randomUUID().toString()) {
            val now = Instant.now().toString()
            val old = existingId?.let { id -> snapshot.value.memos.firstOrNull { it.id == id } }
            val items = if (checklist && checklistItems != null) checklistItems.filter { it.text.isNotBlank() } else if (checklist) {
                content.lines().filter { it.isNotBlank() }.mapIndexed { index, line ->
                    oldItems.getOrNull(index)?.copy(text = line) ?: MemoChecklistItem(UUID.randomUUID().toString(), line)
                }.ifEmpty { listOf(MemoChecklistItem(UUID.randomUUID().toString())) }
            } else emptyList()
            app.repository.saveMemo(
                MemoItem(
                    id = existingId ?: UUID.randomUUID().toString(),
                    title = title,
                    // A checklist drafted from the text keeps that text as 原文 (1.10.0); one built from lines here has none.
                    content = if (!checklist) content else if (checklistItems == null) "" else com.raydon.moji.android.ai.MemoTextRules.checklistContent(content),
                    mode = if (checklist) "checklist" else "note",
                    checklistItems = items,
                    isPinned = isPinned ?: old?.isPinned ?: false,
                    createdAt = old?.createdAt ?: now,
                    updatedAt = now,
                )
            )
            afterMutation()
            onSaved?.invoke()
        }
    }

    fun toggleChecklistItem(memoId: String, itemId: String) = launchMutation {
        app.repository.toggleChecklistItem(memoId, itemId)
    }

    fun toggleMemoPin(id: String) = launchMutation { app.repository.toggleMemoPin(id) }
    fun deleteMemo(id: String) = launchMutation { app.repository.deleteMemo(id) }
    fun countdownSort(scope: String): String = app.settings.countdownSort(scope)
    fun setCountdownSort(scope: String, value: String) = app.settings.setCountdownSort(scope, value)
    fun reorderCountdowns(ids: List<String>) = launchMutation {
        app.repository.mutate { state ->
            val ordered = state.countdowns.sortedBy { it.sortOrder ?: Int.MAX_VALUE }.toMutableList()
            val positions = ordered.indices.filter { ordered[it].id in ids }
            val selected = ids.mapNotNull { id -> ordered.firstOrNull { it.id == id } }
            if (positions.size == selected.size) positions.forEachIndexed { index, position -> ordered[position] = selected[index] }
            state.copy(countdowns = ordered.mapIndexed { index, event -> event.copy(sortOrder = index) })
        }
    }

    fun saveCountdown(
        existingId: String?,
        title: String,
        date: LocalDate,
        repeatRule: String,
        includesToday: Boolean,
        pinned: Boolean,
        calendarSync: Boolean,
        symbolName: String = "flag.fill",
        colorName: String = "ink",
        onCreated: ((CountdownEvent) -> Unit)? = null,
        onSaved: (() -> Unit)? = null,
    ) {
        if (title.isBlank()) return
        saveOperation(existingId ?: UUID.randomUUID().toString()) {
            val now = Instant.now()
            val old = existingId?.let { id -> snapshot.value.countdowns.firstOrNull { it.id == id } }
            var event = CountdownEvent(
                    id = existingId ?: UUID.randomUUID().toString(),
                    title = title.trim(),
                    targetDate = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toString(),
                    symbolName = symbolName,
                    colorName = colorName,
                    isPinned = pinned,
                    createdAt = old?.createdAt ?: now.toString(),
                    repeatRule = repeatRule,
                    includesToday = includesToday,
                    calendarSyncEnabled = calendarSync,
                    calendarEventIdentifier = old?.calendarEventIdentifier,
                    sortOrder = old?.sortOrder ?: snapshot.value.countdowns.size,
                )
            if (calendarSync && event.calendarEventIdentifier == null) {
                event = event.copy(calendarEventIdentifier = withContext(Dispatchers.IO) { AndroidCalendarIntegration.addCountdown(app, event) })
            }
            app.repository.saveCountdown(event)
            afterMutation()
            if (old == null) onCreated?.invoke(event)
            onSaved?.invoke()
        }
    }

    fun toggleCountdownPin(id: String) = launchMutation { app.repository.toggleCountdownPin(id) }
    fun deleteCountdown(id: String) = viewModelScope.launch(mutationErrors) {
        app.repository.deleteCountdown(id)
        afterMutation()
    }
    fun deleteRecord(id: String) = launchMutation { app.repository.deleteRecord(id) }

    fun saveRecord(existingId: String?, title: String, category: String, date: LocalDate, start: LocalTime, end: LocalTime, note: String, endDate: LocalDate = date, scheduleKind: String = "exactTime", onSaved: (() -> Unit)? = null) {
        if (title.isBlank() || (scheduleKind == "exactTime" && !endDate.atTime(end).isAfter(date.atTime(start)))) return
        saveOperation(existingId ?: UUID.randomUUID().toString()) {
            val old = existingId?.let { id -> snapshot.value.records.firstOrNull { it.id == id } }
            app.repository.saveRecord(
                TimeRecord(
                    id = existingId ?: UUID.randomUUID().toString(),
                    title = title.trim(),
                    category = category,
                    startDate = date.atTime(if (scheduleKind == "exactTime") start else LocalTime.of(when (scheduleKind) { "morning" -> 9; "afternoon" -> 14; "evening" -> 19; else -> 0 }, 0)).atZone(ZoneId.systemDefault()).toInstant().toString(),
                    endDate = (if (scheduleKind == "exactTime") endDate.atTime(end) else date.atTime(LocalTime.of(when (scheduleKind) { "morning" -> 9; "afternoon" -> 14; "evening" -> 19; else -> 0 }, 0))).atZone(ZoneId.systemDefault()).toInstant().toString(),
                    note = note,
                    checkInItemID = old?.checkInItemID,
                    scheduleKind = scheduleKind,
                )
            )
            afterMutation()
            onSaved?.invoke()
        }
    }

    fun linkPomodoro(plan: CheckInItem?) {
        val current = pomodoro.value
        if (current.running || current.accumulatedSeconds > 0 || current.pendingNextPhase != null || finishingPomodoro || startingPomodoro) {
            message.value = "请先结束当前专注，再更换关联计划"; return
        }
        app.settings.updatePomodoro(
            current.copy(
                title = plan?.title ?: "番茄专注",
                category = plan?.category ?: "study",
                linkedPlanId = plan?.id ?: "",
                remainingSeconds = plan?.takeIf { it.plannedDurationEnabled ?: ((it.scheduleKind ?: "exactTime") == "exactTime" && it.detailsConfigured != false) }?.plannedMinutes?.coerceIn(1, 120)?.times(60) ?: app.settings.durationFor(current.phase),
                phaseDurationSeconds = plan?.takeIf { it.plannedDurationEnabled ?: ((it.scheduleKind ?: "exactTime") == "exactTime" && it.detailsConfigured != false) }?.plannedMinutes?.coerceIn(1, 120)?.times(60) ?: app.settings.durationFor(current.phase),
                durationIsPlanOwned = plan?.let { it.plannedDurationEnabled ?: ((it.scheduleKind ?: "exactTime") == "exactTime" && it.detailsConfigured != false) } == true,
                pendingNextPhase = null, completedPhase = null,
            )
        )
    }

    fun startPomodoro() {
        if (finishingPomodoro || startingPomodoro) return
        pomodoro.value.pendingNextPhase?.let { selectPomodoroPhase(it) }
        val current = settled(pomodoro.value)
        if (current.running || current.remainingSeconds <= 0) return
        startingPomodoro = true
        viewModelScope.launch(mutationErrors) {
            try {
                if (current.phase == "focus") app.repository.beginPomodoroFocus(current.linkedPlanId.ifBlank { null })
                val running = current.copy(running = true,
                    targetEpochMillis = System.currentTimeMillis() + current.remainingSeconds * 1000L)
                app.settings.updatePomodoro(running)
                AlarmScheduler.schedulePomodoro(app, running.targetEpochMillis, running.title)
                AlarmScheduler.showRunningPomodoro(app, running)
                afterMutation()
            } finally { startingPomodoro = false }
        }
    }

    fun pausePomodoro() {
        if (finishingPomodoro) return
        val current = pomodoro.value
        if (!current.running) return
        val remaining = remainingSeconds(current)
        if (remaining == 0) {
            viewModelScope.launch(mutationErrors) { finishCurrentPomodoro(true, true) }
            return
        }
        val elapsed = (current.remainingSeconds - remaining).coerceAtLeast(0)
        val paused = current.copy(
            running = false,
            remainingSeconds = remaining,
            targetEpochMillis = 0,
            accumulatedSeconds = current.accumulatedSeconds + elapsed,
        )
        app.settings.updatePomodoro(paused)
        AlarmScheduler.cancelPomodoro(app)
        AlarmScheduler.showRunningPomodoro(app, paused)
    }

    fun resetPomodoro() {
        val current = pomodoro.value
        app.settings.updatePomodoro(
            current.copy(
                running = false,
                remainingSeconds = app.settings.durationFor(current.phase),
                targetEpochMillis = 0,
                accumulatedSeconds = 0,
                title = "番茄专注",
                linkedPlanId = "",
                phaseDurationSeconds = app.settings.durationFor(current.phase),
                durationIsPlanOwned = false,
                pendingNextPhase = null,
                completedPhase = null,
            )
        )
        AlarmScheduler.cancelPomodoro(app)
    }

    fun finishPomodoro(keepRecord: Boolean) {
        viewModelScope.launch(mutationErrors) { finishCurrentPomodoro(keepRecord, false) }
    }

    fun selectPomodoroPhase(phase: String) {
        if (pomodoro.value.running || pomodoro.value.accumulatedSeconds > 0 || finishingPomodoro || startingPomodoro) {
            message.value = "请先结束当前专注，再切换阶段"; return
        }
        val current = pomodoro.value
        app.settings.updatePomodoro(
            current.copy(phase = phase, remainingSeconds = app.settings.durationFor(phase), accumulatedSeconds = 0,
                phaseDurationSeconds = app.settings.durationFor(phase), durationIsPlanOwned = false,
                pendingNextPhase = null, completedPhase = null,
                linkedPlanId = if (phase == "focus") current.linkedPlanId else "")
        )
    }

    fun setPhaseMinutes(phase: String, value: Int, defaultsOnly: Boolean = false) {
        if (!defaultsOnly && (finishingPomodoro || startingPomodoro || pomodoro.value.running)) {
            message.value = "请先结束当前计时，再修改时长"; return
        }
        val minutes = value.coerceIn(1, when (phase) { "shortBreak" -> 30; "longBreak" -> 60; else -> 120 })
        when (phase) {
            "shortBreak" -> app.settings.shortBreakMinutes = minutes
            "longBreak" -> app.settings.longBreakMinutes = minutes
            else -> app.settings.focusMinutes = minutes
        }
        if (pomodoro.value.phase == phase && !pomodoro.value.running && pomodoro.value.pendingNextPhase == null && (!defaultsOnly || !pomodoro.value.durationIsPlanOwned)) {
            app.settings.updatePomodoro(pomodoro.value.copy(remainingSeconds = (minutes * 60 - pomodoro.value.accumulatedSeconds).coerceAtLeast(1), phaseDurationSeconds = minutes * 60, durationIsPlanOwned = false))
        }
        message.value = "时长已保存"
    }

    fun switchPomodoroPhase(phase: String, keepRecord: Boolean) = viewModelScope.launch(mutationErrors) {
        if (finishingPomodoro) return@launch
        if (pomodoro.value.running || pomodoro.value.accumulatedSeconds > 0) finishCurrentPomodoro(keepRecord, false)
        selectPomodoroPhase(phase)
    }

    fun setCarryOver(value: Boolean) { app.settings.carriesOver = value; updateAll(app); message.value = "设置已保存" }
    fun setAppearance(value: String) { app.settings.appearance = value; appearanceMode.value = value }
    fun setFocusMinutes(value: Int) { setPhaseMinutes("focus", value) }
    fun setNotifications(value: Boolean) { app.settings.notificationsEnabled = value; viewModelScope.launch(mutationErrors) { reconcileReminders() } }
    fun setSound(value: Boolean) { app.settings.soundEnabled = value }
    fun setHaptics(value: Boolean) { app.settings.hapticsEnabled = value }
    fun setDefaultCategory(value: String) { app.settings.defaultCategory = value }
    fun setDefaultSchedule(value: String) { app.settings.defaultScheduleKind = value }
    fun setDefaultDurationEnabled(value: Boolean) { app.settings.defaultPlannedDurationEnabled = value }
    fun setDefaultMinutes(value: Int) { app.settings.defaultPlannedMinutes = value }
    fun setAllDayReminderHour(value: Int) { app.settings.allDayReminderHour = value; viewModelScope.launch(mutationErrors) { reconcileReminders() } }
    fun setLongBreakEnabled(value: Boolean) { app.settings.longBreakEnabled = value }
    fun setLongBreakInterval(value: Int) { app.settings.longBreakInterval = value.coerceIn(1, 8) }
    fun setAutoStartBreaks(value: Boolean) { app.settings.autoStartBreaks = value }
    fun setAutoStartFocus(value: Boolean) { app.settings.autoStartFocus = value }
    fun setKeepScreenAwake(value: Boolean) { app.settings.keepScreenAwake = value; settingsRevision.value++ }
    fun setPaperTexture(value: Boolean) { app.settings.paperTextureEnabled = value; settingsRevision.value++ }
    fun setInkMotion(value: String) { app.settings.inkMotionLevel = value; settingsRevision.value++ }
    fun presetButton(index: Int) = app.settings.presetButton(index)
    fun presetTitle(index: Int) = app.settings.presetTitle(index)
    fun presetCategory(index: Int) = app.settings.presetCategory(index)
    fun savePreset(index: Int, button: String, title: String, category: String) { app.settings.setPreset(index, button, title, category); updateAll(app); message.value = "预设已保存" }
    fun resetPresets() { app.settings.resetPresets(); updateAll(app); message.value = "已恢复默认预设" }
    fun setBackupRetention(value: Int) { app.settings.backupRetentionDays = value; settingsRevision.value++ }
    fun backupNow() = backupOperation {
        check(BackupService.writeAutomaticBackup(app)) { "请先选择自动备份文件夹" }
        settingsRevision.value++
        backupNotice.value = "已写入最新备份。"
    }
    fun stopAutomaticBackup() = backupOperation {
        val old = app.settings.backupTreeUri
        app.settings.backupTreeUri = null
        app.settings.lastBackupAt = 0L
        if (old != null) withContext(Dispatchers.IO) {
            runCatching { app.contentResolver.releasePersistableUriPermission(Uri.parse(old), IntentFlags.readWrite) }
        }
        settingsRevision.value++
        backupNotice.value = "已停止自动备份，原文件夹中的备份仍然保留。"
    }
    fun setBackupTree(uri: Uri) = backupOperation {
        withContext(Dispatchers.IO) {
            app.contentResolver.takePersistableUriPermission(uri, IntentFlags.readWrite)
            val previous = app.settings.backupTreeUri
            app.settings.backupTreeUri = uri.toString()
            try { check(BackupService.writeAutomaticBackup(app)) { "无法写入该文件夹" } }
            catch (error: Exception) { app.settings.backupTreeUri = previous; throw error }
        }
        settingsRevision.value++
        backupNotice.value = "自动备份文件夹已设置"
    }

    fun exportBackup(uri: Uri) = backupOperation {
        withContext(Dispatchers.IO) {
            // Obtain data first; never truncate the destination before export succeeds.
            val json = BackupService.exportJson(app)
            val output = app.contentResolver.openOutputStream(uri, "wt") ?: error("无法打开目标文件")
            output.bufferedWriter(Charsets.UTF_8).use { it.write(json) }
        }
        backupNotice.value = "备份已导出"
    }

    fun prepareRestore(uri: Uri) = backupOperation {
        pendingBackup.value = null
        pendingBackup.value = readBackupPreview(uri)
    }

    fun prepareRestoreFolder(uri: Uri) = backupOperation {
        pendingBackup.value = null
        val latest = withContext(Dispatchers.IO) {
            val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(app, uri) ?: error("无法读取备份文件夹")
            listOf("Moji-latest.mojibackup", "Moji-自动备份.mojibackup", "墨记-自动备份.mojibackup")
                .firstNotNullOfOrNull { tree.findFile(it)?.takeIf { file -> file.isFile } }
                ?: error("这个文件夹里没有 Moji 最新备份。请选择存放自动备份的文件夹。")
        }
        pendingBackup.value = readBackupPreview(latest.uri)
    }

    private suspend fun readBackupPreview(uri: Uri): BackupPreview = withContext(Dispatchers.IO) {
            val input = app.contentResolver.openInputStream(uri) ?: error("无法读取备份文件")
            val bytes = input.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 32 * 1024 * 1024) { "备份超过 32 MB，请联系维护者处理" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val json = bytes.toString(Charsets.UTF_8)
            BackupPreview(json, MojiJson.decodeBackupOrSnapshot(json).first)
    }

    fun cancelRestore() { if (!backupBusy.value) pendingBackup.value = null }

    fun confirmRestore() = backupOperation {
        val preview = pendingBackup.value ?: return@backupOperation
        check(!finishingPomodoro && !pomodoro.value.running && pomodoro.value.accumulatedSeconds == 0) {
            "请先结束当前计时，再恢复备份"
        }
        BackupService.importJson(app, preview.json)
        appearanceMode.value = app.settings.appearance
        pendingBackup.value = null
        AlarmScheduler.cancelPomodoro(app)
        val restoredTimer = pomodoro.value
        if (restoredTimer.running) {
            AlarmScheduler.schedulePomodoro(app, restoredTimer.targetEpochMillis, restoredTimer.title)
        }
        AlarmScheduler.showRunningPomodoro(app, restoredTimer)
        backupNotice.value = "备份已恢复，恢复前的数据已保留在本机"
        afterMutation()
    }

    private fun backupOperation(block: suspend () -> Unit) {
        if (backupBusy.value) return
        backupBusy.value = true
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { backupNotice.value = "备份操作未完成：${error.message ?: "请检查文件后重试"}" }
            finally { backupBusy.value = false }
        }
    }

    fun clearMessage() { message.value = null }

    val visualQaRoute = MutableStateFlow("")
    /** ai-memo-original's memo, the same id as on iOS. */
    val qaOriginalMemoId = "00000309-0000-4000-8000-000000000000"
    fun seedVisualQa(route: String = "") {
        if (!BuildConfig.DEBUG) return
        viewModelScope.launch(mutationErrors) {
            val json = app.assets.open("workflow-1.6.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            var seeded = MojiJson.decodeBackupOrSnapshot(json).first
            val zone = ZoneId.systemDefault()
            if (route in listOf("ai-brief", "ai-brief-off", "ai-carry-note", "ai-wrap", "ai-wrap-edit", "ai-wrap-off", "ai-writes", "ai-review-next-carry")) {
                // 今日提要, 顺延旁批 and 晚间收尾 need a plan carried over for three days.
                seeded = seeded.copy(checkInItems = seeded.checkInItems.map { item ->
                    if (item.title != "归纳昨日待办") item else runCatching {
                        val start = Instant.parse(item.scheduledStart).atZone(zone)
                        item.copy(scheduledStart = start.with(LocalDate.now(zone).minusDays(3)).toInstant().toString())
                    }.getOrDefault(item)
                })
            }
            // 晚间收尾 and 今天的整理: three writes from earlier today, one of them taken back.
            // The changed plan is 「归纳昨日待办」, the first plan in the iOS store.
            if (route in listOf("ai-wrap", "ai-wrap-edit", "ai-wrap-off", "ai-writes")) {
                val plans = seeded.checkInItems.filter { it.kind == "planned" }.sortedBy { it.title != "归纳昨日待办" }
                com.raydon.moji.android.ai.AIWriteLog.shared.seedForQA(plans, com.raydon.moji.android.ai.AIClock.now())
            }
            if (route == "ai-origin") {
                // Two steps 墨记 split off 「准备季度汇报」, today at 16:00 and 17:00.
                val today = LocalDate.now(zone)
                val created = Instant.now().toString()
                val steps = listOf("列出汇报的三个要点" to 15, "找齐三个月的数据" to 30).mapIndexed { index, (title, minutes) ->
                    CheckInItem(id = UUID.randomUUID().toString(), title = title, category = "work",
                        scheduledStart = today.atTime(16 + index, 0).atZone(zone).toInstant().toString(), plannedMinutes = minutes,
                        createdAt = created, scheduleKind = "exactTime", detailsConfigured = true, plannedDurationEnabled = true,
                        aiOrigin = com.raydon.moji.android.ai.AIWriteText.splitOrigin("准备季度汇报"))
                }
                seeded = seeded.copy(checkInItems = seeded.checkInItems + steps)
            }
            // 1.10.0 screens (spec D).
            if (route == "ai-plan-template-hint") {
                // 「背单词」 written by hand two and four days ago (as on iOS); the third, typed on the page, brings the hint.
                seeded = seeded.copy(checkInItems = seeded.checkInItems + listOf(2L, 4L).map { days ->
                    val start = LocalDate.now(zone).minusDays(days).atTime(7, 30).atZone(zone).toInstant()
                    CheckInItem(id = UUID.randomUUID().toString(), title = "背单词", category = "study",
                        scheduledStart = start.toString(), plannedMinutes = 30, createdAt = start.minusSeconds(3_600).toString(),
                        status = "completed", completedAt = start.plusSeconds(1_800).toString(), scheduleKind = "exactTime",
                        detailsConfigured = true, plannedDurationEnabled = true)
                })
            }
            if (route == "ai-memo-original") {
                // A checklist made by 整理成清单 that kept the text it came from (the same memo as iOS's QA screen).
                val now = Instant.now()
                val memo = com.raydon.moji.android.ai.MemoEditState(false,
                    "下周六考试，前一天把准考证打印出来，查好去考场的路线和要多久，笔带两支黑色签字笔。\n" +
                        "还想再过一遍损害保险的三个判例，尤其是保险利益那一个。\n考完那天晚上和室友去吃火锅。", emptyList())
                    .usingChecklistDraft(listOf("打印准考证", "确认考场路线和到达时间", "准备黑色签字笔两支", "复习损害保险的三个判例"))
                    .memo(qaOriginalMemoId, "考前准备", false, now.minusSeconds(3_600).toString(), now.minusSeconds(600).toString())
                seeded = seeded.copy(memos = seeded.memos.filterNot { it.id == memo.id } + memo)
            }
            if (route == "ai-nextweek-offer") {
                // This week's 周复盘 saved, so the 下周 offer sits under its card.
                val week = LocalDate.now(zone).with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).toString()
                if (seeded.weeklyReflections.none { it.weekStart == week && it.nextFocus.isNotEmpty() }) seeded = seeded.copy(
                    weeklyReflections = seeded.weeklyReflections.filterNot { it.weekStart == week } +
                        WeeklyReflection(UUID.randomUUID().toString(), week, "前半周按计划完成，周四起两件大事一直顺延。",
                            "先把大事拆出能马上开始的第一步。", Instant.now().toString()))
            }
            // 本周来信: the sample week moved so it ends on this week's Sunday, where the clock stands.
            if (route.startsWith("ai-letter")) seeded = com.raydon.moji.android.ai.AIQAFixture.shifted(seeded, com.raydon.moji.android.ai.AIQAFixture.letterShiftDays, zone)
            // 分享: one shared sentence waiting in the inbox (a scratch folder while the fixture is on).
            if (route == "ai-share" || route == "ai-share-off") com.raydon.moji.android.ai.ShareInbox.of(app).let { inbox ->
                inbox.clear()
                inbox.add("周五前交季度报告，周三下午和小王对一下数据，明天上午订会议室", null)
            }
            app.repository.replace(seeded)
            app.settings.customCategories = listOf("生活")
            app.settings.appearance = "system"
            appearanceMode.value = "system"
            resetPomodoro()
            visualQaRoute.value = route
            selectedTab.value = when {
                route.startsWith("ai-memo") -> 1
                route.startsWith("ai-preparation") -> 2
                route == "ai" || route.startsWith("ai:") || route.startsWith("ai-reflection") || route == "ai-next-week" || route.startsWith("ai-letter") ||
                    route == "ai-settings-help" || route == "ai-nextweek-offer" || route == "ai-review-next-carry" -> 3
                route.startsWith("ai-") -> 0
                route.startsWith("memo") -> 1
                route.startsWith("moment") || route.startsWith("focus") -> 2
                route.startsWith("review") || route.startsWith("settings") || route in listOf("goal", "reflection", "next-week", "record-editor", "record-untimed") -> 3
                else -> 0
            }
            if (route.startsWith("focus")) openFocus()
        }
    }

    fun displayMemoTitle(memo: MemoItem): String {
        val explicit = memo.title.trim()
        if (explicit.isNotEmpty()) return explicit
        val first = if (memo.mode == "checklist") memo.checklistItems.firstOrNull { it.text.isNotBlank() }?.text
        else memo.content.lineSequence().firstOrNull { it.isNotBlank() }
        return first?.trim()?.take(32)?.ifEmpty { null } ?: "无标题备忘"
    }

    fun remainingSeconds(state: PomodoroState = pomodoro.value): Int = if (state.running) {
        ((state.targetEpochMillis - System.currentTimeMillis() + 999) / 1000).toInt().coerceAtLeast(0)
    } else state.remainingSeconds

    private suspend fun settlePomodoroIfNeeded() {
        if (backupBusy.value) return
        val current = pomodoro.value
        if (current.running && current.targetEpochMillis <= System.currentTimeMillis()) {
            finishCurrentPomodoro(true, true)
        }
    }

    private suspend fun finishCurrentPomodoro(keepRecord: Boolean, expired: Boolean) {
        if (finishingPomodoro || startingPomodoro) return
        finishingPomodoro = true
        try {
        val current = pomodoro.value
        val remaining = remainingSeconds(current)
        val segmentElapsed = if (current.running) (current.remainingSeconds - remaining).coerceAtLeast(0) else 0
        val elapsedFocus = current.accumulatedSeconds + if (current.phase == "focus") segmentElapsed else 0
        val now = if (expired && current.targetEpochMillis > 0) Instant.ofEpochMilli(current.targetEpochMillis) else Instant.now()
        if (current.phase == "focus" && keepRecord && elapsedFocus > 0) {
            app.repository.recordFocus(
                title = current.title,
                category = current.category,
                linkedPlanId = current.linkedPlanId,
                startedAt = now.minusSeconds(elapsedFocus.toLong()),
                endedAt = now,
                completePlan = expired,
            )
        } else if (current.linkedPlanId.isNotBlank()) {
            app.repository.releasePomodoroFocus(current.linkedPlanId)
        }
        val completed = current.completedFocusCount + if (current.phase == "focus" && expired) 1 else 0
        val nextPhase = if (!expired) current.phase else if (current.phase == "focus") {
            PomodoroPolicy.nextBreak(completed, app.settings.longBreakInterval, app.settings.longBreakEnabled)
        } else "focus"
        val autoStart = expired && if (nextPhase == "focus") app.settings.autoStartFocus else app.settings.autoStartBreaks
        val next = PomodoroState(
            phase = if (expired && !autoStart) current.phase else nextPhase,
            running = autoStart,
            remainingSeconds = if (expired && !autoStart) 0 else app.settings.durationFor(nextPhase),
            targetEpochMillis = if (autoStart) System.currentTimeMillis() + app.settings.durationFor(nextPhase) * 1000L else 0,
            completedFocusCount = completed,
            phaseDurationSeconds = if (expired && !autoStart) current.phaseDurationSeconds else app.settings.durationFor(nextPhase),
            pendingNextPhase = if (expired && !autoStart) nextPhase else null,
            completedPhase = if (expired) current.phase else null,
        )
        app.settings.updatePomodoro(next)
        AlarmScheduler.cancelPomodoro(app)
        if (autoStart) AlarmScheduler.schedulePomodoro(app, next.targetEpochMillis, next.title)
        AlarmScheduler.showRunningPomodoro(app, next)
        afterMutation()
        } finally { finishingPomodoro = false }
    }

    private fun settled(state: PomodoroState): PomodoroState = if (state.running && state.targetEpochMillis <= System.currentTimeMillis()) {
        state.copy(running = false, remainingSeconds = 0, targetEpochMillis = 0)
    } else state

    private fun launchMutation(block: suspend () -> Unit) = viewModelScope.launch(mutationErrors) {
        block()
        afterMutation()
    }

    private fun saveOperation(id: String, block: suspend () -> Unit) {
        if (!pendingSaveIDs.add(id)) return
        viewModelScope.launch(mutationErrors) { try { block() } finally { pendingSaveIDs.remove(id) } }
    }

    private suspend fun afterMutation() {
        reconcileReminders()
        updateAll(app)
        if (app.settings.backupTreeUri != null) {
            automaticBackupJob?.cancel()
            automaticBackupJob = viewModelScope.launch {
                delay(1_500) // Coalesce rapid checklist edits into one external write.
                try {
                    check(BackupService.writeAutomaticBackup(app)) { "无法写入备份文件夹" }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { message.value = "内容已保存，但自动备份失败：${error.message ?: "请检查文件夹权限"}" }
            }
        }
    }

    private suspend fun reconcileReminders() = withContext(Dispatchers.IO) {
        AlarmScheduler.reconcilePlans(app, app.repository.freshSnapshot().checkInItems)
    }
}

private object IntentFlags {
    const val readWrite = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
}
