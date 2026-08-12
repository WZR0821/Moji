package com.raydon.moji.android

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import com.raydon.moji.core.PomodoroPolicy
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MojiViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MojiApplication
    val snapshot: StateFlow<PlanSnapshot> = app.repository.snapshot
    val pomodoro: StateFlow<PomodoroState> = app.settings.pomodoro
    val tickMillis = MutableStateFlow(System.currentTimeMillis())
    val message = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
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
    fun durationForPhase(phase: String): Int = app.settings.durationFor(phase)

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
    ) {
        if (title.isBlank()) return
        viewModelScope.launch {
            val now = Instant.now()
            val old = existingId?.let { id -> snapshot.value.checkInItems.firstOrNull { it.id == id } }
            var item = CheckInItem(
                id = existingId ?: UUID.randomUUID().toString(),
                title = title.trim(),
                category = category,
                scheduledStart = ZonedDateTime.of(date, time, ZoneId.systemDefault()).toInstant().toString(),
                plannedMinutes = plannedMinutes.coerceAtLeast(1),
                note = note,
                status = old?.status ?: "planned",
                actualStartDate = old?.actualStartDate,
                actualEndDate = old?.actualEndDate,
                completedAt = old?.completedAt,
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
                plannedDurationEnabled = true,
            )
            if (calendarSync && item.calendarEventIdentifier == null) {
                val eventId = withContext(Dispatchers.IO) { AndroidCalendarIntegration.addPlan(app, item) }
                item = item.copy(calendarEventIdentifier = eventId)
            }
            app.repository.savePlan(item)
            if (app.settings.notificationsEnabled) AlarmScheduler.schedulePlan(app, item)
            afterMutation()
        }
    }

    fun togglePlan(id: String) = launchMutation { app.repository.togglePlan(id) }
    fun skipPlan(id: String) = launchMutation { app.repository.skipPlan(id) }
    fun postponePlan(id: String) = launchMutation { app.repository.postponePlan(id) }
    fun deletePlan(id: String) = viewModelScope.launch {
        val plan = snapshot.value.checkInItems.firstOrNull { it.id == id }
        withContext(Dispatchers.IO) { AndroidCalendarIntegration.delete(app, plan?.calendarEventIdentifier) }
        app.repository.deletePlan(id)
        afterMutation()
    }

    fun saveMemo(
        existingId: String?,
        title: String,
        content: String,
        checklist: Boolean,
        oldItems: List<MemoChecklistItem> = emptyList(),
    ) {
        viewModelScope.launch {
            val now = Instant.now().toString()
            val old = existingId?.let { id -> snapshot.value.memos.firstOrNull { it.id == id } }
            val items = if (checklist) {
                content.lines().filter { it.isNotBlank() }.mapIndexed { index, line ->
                    oldItems.getOrNull(index)?.copy(text = line) ?: MemoChecklistItem(UUID.randomUUID().toString(), line)
                }.ifEmpty { listOf(MemoChecklistItem(UUID.randomUUID().toString())) }
            } else emptyList()
            app.repository.saveMemo(
                MemoItem(
                    id = existingId ?: UUID.randomUUID().toString(),
                    title = title,
                    content = if (checklist) "" else content,
                    mode = if (checklist) "checklist" else "note",
                    checklistItems = items,
                    isPinned = old?.isPinned ?: false,
                    createdAt = old?.createdAt ?: now,
                    updatedAt = now,
                )
            )
            afterMutation()
        }
    }

    fun toggleChecklistItem(memoId: String, itemId: String) = viewModelScope.launch {
        val memo = snapshot.value.memos.firstOrNull { it.id == memoId } ?: return@launch
        app.repository.saveMemo(
            memo.copy(
                checklistItems = memo.checklistItems.map {
                    if (it.id == itemId) it.copy(isCompleted = !it.isCompleted) else it
                },
                updatedAt = Instant.now().toString(),
            )
        )
        afterMutation()
    }

    fun toggleMemoPin(id: String) = launchMutation { app.repository.toggleMemoPin(id) }
    fun deleteMemo(id: String) = launchMutation { app.repository.deleteMemo(id) }

    fun saveCountdown(
        existingId: String?,
        title: String,
        date: LocalDate,
        repeatRule: String,
        includesToday: Boolean,
        pinned: Boolean,
        calendarSync: Boolean,
    ) {
        if (title.isBlank()) return
        viewModelScope.launch {
            val now = Instant.now()
            val old = existingId?.let { id -> snapshot.value.countdowns.firstOrNull { it.id == id } }
            var event = CountdownEvent(
                    id = existingId ?: UUID.randomUUID().toString(),
                    title = title.trim(),
                    targetDate = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toString(),
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
        }
    }

    fun toggleCountdownPin(id: String) = launchMutation { app.repository.toggleCountdownPin(id) }
    fun deleteCountdown(id: String) = viewModelScope.launch {
        val event = snapshot.value.countdowns.firstOrNull { it.id == id }
        withContext(Dispatchers.IO) { AndroidCalendarIntegration.delete(app, event?.calendarEventIdentifier) }
        app.repository.deleteCountdown(id)
        afterMutation()
    }
    fun deleteRecord(id: String) = launchMutation { app.repository.deleteRecord(id) }

    fun linkPomodoro(plan: CheckInItem?) {
        val current = pomodoro.value
        if (current.running) return
        app.settings.updatePomodoro(
            current.copy(
                title = plan?.title ?: "番茄专注",
                category = plan?.category ?: "study",
                linkedPlanId = plan?.id ?: "",
                remainingSeconds = plan?.plannedMinutes?.times(60) ?: app.settings.durationFor(current.phase),
            )
        )
    }

    fun startPomodoro() {
        val current = settled(pomodoro.value)
        if (current.running || current.remainingSeconds <= 0) return
        val running = current.copy(
            running = true,
            targetEpochMillis = System.currentTimeMillis() + current.remainingSeconds * 1000L,
        )
        app.settings.updatePomodoro(running)
        AlarmScheduler.schedulePomodoro(app, running.targetEpochMillis, running.title)
        AlarmScheduler.showRunningPomodoro(app, running)
    }

    fun pausePomodoro() {
        val current = pomodoro.value
        if (!current.running) return
        val remaining = remainingSeconds(current)
        val elapsed = (current.remainingSeconds - remaining).coerceAtLeast(0)
        val paused = current.copy(
            running = false,
            remainingSeconds = remaining,
            targetEpochMillis = 0,
            accumulatedSeconds = current.accumulatedSeconds + if (current.phase == "focus") elapsed else 0,
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
            )
        )
        AlarmScheduler.cancelPomodoro(app)
    }

    fun finishPomodoro(keepRecord: Boolean) {
        viewModelScope.launch { finishCurrentPomodoro(keepRecord, false) }
    }

    fun selectPomodoroPhase(phase: String) {
        if (pomodoro.value.running) return
        val current = pomodoro.value
        app.settings.updatePomodoro(
            current.copy(phase = phase, remainingSeconds = app.settings.durationFor(phase), accumulatedSeconds = 0)
        )
    }

    fun setCarryOver(value: Boolean) { app.settings.carriesOver = value; message.value = "设置已保存" }
    fun setAppearance(value: String) { app.settings.appearance = value; message.value = "重新打开后完整应用外观" }
    fun setFocusMinutes(value: Int) { app.settings.focusMinutes = value; resetPomodoro() }
    fun setNotifications(value: Boolean) { app.settings.notificationsEnabled = value }
    fun setBackupTree(uri: Uri) {
        app.contentResolver.takePersistableUriPermission(uri, IntentFlags.readWrite)
        app.settings.backupTreeUri = uri.toString()
        viewModelScope.launch(Dispatchers.IO) {
            val ok = BackupService.writeAutomaticBackup(app)
            withContext(Dispatchers.Main) { message.value = if (ok) "自动备份文件夹已设置" else "无法写入该文件夹" }
        }
    }

    fun exportJson(): String = BackupService.exportJson(app)

    fun importJson(json: String) {
        viewModelScope.launch {
            runCatching { BackupService.importJson(app, json) }
                .onSuccess { message.value = "备份已恢复"; afterMutation() }
                .onFailure { message.value = "无法恢复：${it.message ?: "备份无效"}" }
        }
    }

    fun clearMessage() { message.value = null }

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
        val current = pomodoro.value
        if (current.running && current.targetEpochMillis <= System.currentTimeMillis()) {
            finishCurrentPomodoro(true, true)
        }
    }

    private suspend fun finishCurrentPomodoro(keepRecord: Boolean, expired: Boolean) {
        val current = pomodoro.value
        val remaining = remainingSeconds(current)
        val segmentElapsed = if (current.running) (current.remainingSeconds - remaining).coerceAtLeast(0) else 0
        val elapsedFocus = current.accumulatedSeconds + if (current.phase == "focus") segmentElapsed else 0
        val now = Instant.now()
        if (current.phase == "focus" && keepRecord && elapsedFocus > 0) {
            app.repository.recordFocus(
                title = current.title,
                category = current.category,
                linkedPlanId = current.linkedPlanId,
                startedAt = now.minusSeconds(elapsedFocus.toLong()),
                endedAt = now,
                completePlan = expired || remaining == 0,
            )
        }
        val completed = current.completedFocusCount + if (current.phase == "focus" && expired) 1 else 0
        val nextPhase = if (current.phase == "focus") {
            PomodoroPolicy.nextBreak(completed, app.settings.longBreakInterval, app.settings.longBreakEnabled)
        } else "focus"
        val autoStart = if (nextPhase == "focus") app.settings.autoStartFocus else app.settings.autoStartBreaks
        val next = PomodoroState(
            phase = nextPhase,
            running = autoStart,
            remainingSeconds = app.settings.durationFor(nextPhase),
            targetEpochMillis = if (autoStart) System.currentTimeMillis() + app.settings.durationFor(nextPhase) * 1000L else 0,
            completedFocusCount = completed,
        )
        app.settings.updatePomodoro(next)
        AlarmScheduler.cancelPomodoro(app)
        if (autoStart) AlarmScheduler.schedulePomodoro(app, next.targetEpochMillis, next.title)
        AlarmScheduler.showRunningPomodoro(app, next)
        afterMutation()
    }

    private fun settled(state: PomodoroState): PomodoroState = if (state.running && state.targetEpochMillis <= System.currentTimeMillis()) {
        state.copy(running = false, remainingSeconds = 0, targetEpochMillis = 0)
    } else state

    private fun launchMutation(block: suspend () -> Unit) = viewModelScope.launch {
        block()
        afterMutation()
    }

    private suspend fun afterMutation() {
        updateAll(app)
        if (app.settings.backupTreeUri != null) withContext(Dispatchers.IO) { BackupService.writeAutomaticBackup(app) }
    }
}

private object IntentFlags {
    const val readWrite = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
}
