package com.raydon.moji.android.platform

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.raydon.moji.android.MainActivity
import com.raydon.moji.android.MojiApplication
import com.raydon.moji.android.data.AndroidSettings
import com.raydon.moji.android.R
import com.raydon.moji.android.data.PomodoroState
import com.raydon.moji.core.CheckInItem
import com.raydon.moji.core.CountdownEvent
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object NotificationChannels {
    const val REMINDERS = "moji.reminders"
    const val FOCUS = "moji.focus"
    const val SILENT = "moji.silent"

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(REMINDERS, "计划提醒", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(FOCUS, "番茄专注", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(SILENT, "静音提醒", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) },
            )
        )
    }
}

object AlarmScheduler {
    const val ACTION_PLAN = "com.raydon.moji.PLAN_ALARM"
    const val ACTION_POMODORO = "com.raydon.moji.POMODORO_ALARM"

    fun schedulePlan(context: Context, item: CheckInItem) {
        val settings = (context.applicationContext as MojiApplication).settings
        if (!settings.notificationsEnabled || item.isArchived == true || item.status == "completed" || item.status == "skipped") return
        val before = item.reminderMinutesBefore ?: return
        val at = reminderTime(item, settings)
        if (at <= System.currentTimeMillis()) return
        schedule(context, at, ACTION_PLAN, item.id.hashCode(), item.title, item.id)
    }

    fun reminderTime(item: CheckInItem, settings: AndroidSettings): Long {
        val scheduled = Instant.parse(item.scheduledStart).atZone(ZoneId.systemDefault())
        val base = if (item.scheduleKind == "allDay") scheduled.toLocalDate().atTime(settings.allDayReminderHour, 0).atZone(scheduled.zone) else scheduled
        return base.toInstant().toEpochMilli() - (item.reminderMinutesBefore ?: 0) * 60_000L
    }

    @Synchronized
    fun reconcilePlans(context: Context, plans: List<CheckInItem>) {
        val prefs = context.getSharedPreferences("moji.alarms", Context.MODE_PRIVATE)
        val oldIds = prefs.getStringSet("planIds", emptySet()).orEmpty().toSet()
        val currentIds = plans.map { it.id }.toSet()
        (oldIds + currentIds).forEach { cancelPlan(context, it) }
        plans.forEach { schedulePlan(context, it) }
        prefs.edit().putStringSet("planIds", currentIds).apply()
    }

    fun cancelPlan(context: Context, id: String) {
        val manager = context.getSystemService(AlarmManager::class.java)
        manager.cancel(pending(context, ACTION_PLAN, id.hashCode(), "", id))
        // Cancel alarms created by earlier local builds without a URI identity.
        val old = Intent(context, MojiAlarmReceiver::class.java).setAction(ACTION_PLAN)
        manager.cancel(PendingIntent.getBroadcast(context, id.hashCode(), old, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    }

    fun schedulePomodoro(context: Context, at: Long, title: String) {
        schedule(context, at, ACTION_POMODORO, 7001, title, "pomodoro")
    }

    fun cancelPomodoro(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java)
        manager.cancel(pending(context, ACTION_POMODORO, 7001, "", "pomodoro"))
        manager.cancel(PendingIntent.getBroadcast(context, 7001,
            Intent(context, MojiAlarmReceiver::class.java).setAction(ACTION_POMODORO),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        NotificationManagerCompat.from(context).cancel(7001)
    }

    fun showRunningPomodoro(context: Context, state: PomodoroState) {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).setData(android.net.Uri.parse("moji://pomodoro")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val remaining = if (state.running) ((state.targetEpochMillis - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0) else state.remainingSeconds.toLong()
        val notification = NotificationCompat.Builder(context, NotificationChannels.SILENT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(state.title)
            .setContentText("${phaseName(state.phase)} · ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}")
            .setOngoing(state.running)
            .setUsesChronometer(state.running)
            .setChronometerCountDown(true)
            .setWhen(state.targetEpochMillis)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
        if (android.os.Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(context).notify(7001, notification)
        }
    }

    private fun schedule(context: Context, at: Long, action: String, code: Int, title: String, id: String) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val operation = pending(context, action, code, title, id)
        try {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        } catch (_: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        }
    }

    private fun pending(context: Context, action: String, code: Int, title: String, id: String): PendingIntent {
        val intent = Intent(context, MojiAlarmReceiver::class.java)
            .setAction(action)
            .setData(android.net.Uri.parse("moji://alarm/$id"))
            .putExtra("title", title)
            .putExtra("id", id)
        return PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun phaseName(phase: String) = when (phase) {
        "shortBreak" -> "短休息"
        "longBreak" -> "长休息"
        else -> "专注"
    }
}

class MojiAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
        try {
        val app = context.applicationContext as MojiApplication
        if (!app.settings.notificationsEnabled) return@launch
        val title = intent.getStringExtra("title") ?: "Moji"
        val isFocus = intent.action == AlarmScheduler.ACTION_POMODORO
        if (!isFocus) {
            val plan = app.repository.freshSnapshot().checkInItems.firstOrNull { it.id == intent.getStringExtra("id") } ?: return@launch
            if (!app.settings.notificationsEnabled || plan.isArchived == true || plan.status == "completed" || plan.status == "skipped" || plan.reminderMinutesBefore == null) return@launch
            if (AlarmScheduler.reminderTime(plan, app.settings) > System.currentTimeMillis() + 1000) return@launch
        } else if (!app.settings.pomodoro.value.running || app.settings.pomodoro.value.targetEpochMillis > System.currentTimeMillis() + 1000) return@launch
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return@launch
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).setData(android.net.Uri.parse(if (isFocus) "moji://pomodoro" else "moji://plans")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(
            context, if (!app.settings.soundEnabled) NotificationChannels.SILENT else if (isFocus) NotificationChannels.FOCUS else NotificationChannels.REMINDERS
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (isFocus) if (app.settings.pomodoro.value.phase == "focus") "专注时间结束" else "休息时间结束" else "计划提醒")
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        NotificationManagerCompat.from(context).notify(if (isFocus) 7002 else title.hashCode(), notification)
        } finally { pending.finish() }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
            val app = context.applicationContext as MojiApplication
            AlarmScheduler.reconcilePlans(context, app.repository.freshSnapshot().checkInItems)
            val focus = app.settings.pomodoro.value
            if (focus.running && focus.targetEpochMillis > System.currentTimeMillis()) {
                AlarmScheduler.schedulePomodoro(context, focus.targetEpochMillis, focus.title)
                AlarmScheduler.showRunningPomodoro(context, focus)
            }
            } finally { pending.finish() }
        }
    }
}

object AndroidCalendarIntegration {
    fun addPlan(context: Context, item: CheckInItem): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
        val calendarId = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.VISIBLE}=1",
            null,
            "${CalendarContract.Calendars.IS_PRIMARY} DESC",
        )?.use { if (it.moveToFirst()) it.getLong(0) else null } ?: return null
        val start = Instant.parse(item.scheduledStart).toEpochMilli()
        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, start + item.plannedMinutes.coerceAtLeast(1) * 60_000L)
            put(CalendarContract.Events.TITLE, item.title)
            put(CalendarContract.Events.DESCRIPTION, item.note)
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
        }
        return context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)?.lastPathSegment
    }

    fun addCountdown(context: Context, event: CountdownEvent): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
        val calendarId = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.VISIBLE}=1",
            null,
            "${CalendarContract.Calendars.IS_PRIMARY} DESC",
        )?.use { if (it.moveToFirst()) it.getLong(0) else null } ?: return null
        val start = Instant.parse(event.targetDate).toEpochMilli()
        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, start + 86_400_000L)
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            put(CalendarContract.Events.ALL_DAY, 1)
            when (event.repeatRule) {
                "yearly" -> put(CalendarContract.Events.RRULE, "FREQ=YEARLY")
                "monthly" -> put(CalendarContract.Events.RRULE, "FREQ=MONTHLY")
                "weekly" -> put(CalendarContract.Events.RRULE, "FREQ=WEEKLY")
            }
        }
        return context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)?.lastPathSegment
    }

    fun delete(context: Context, eventId: String?) {
        val id = eventId?.toLongOrNull() ?: return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) return
        context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null)
    }
}
