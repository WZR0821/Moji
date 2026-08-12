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

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(REMINDERS, "计划提醒", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(FOCUS, "番茄专注", NotificationManager.IMPORTANCE_DEFAULT),
            )
        )
    }
}

object AlarmScheduler {
    const val ACTION_PLAN = "com.raydon.moji.PLAN_ALARM"
    const val ACTION_POMODORO = "com.raydon.moji.POMODORO_ALARM"

    fun schedulePlan(context: Context, item: CheckInItem) {
        val before = item.reminderMinutesBefore ?: return
        val at = Instant.parse(item.scheduledStart).toEpochMilli() - before * 60_000L
        if (at <= System.currentTimeMillis()) return
        schedule(context, at, ACTION_PLAN, item.id.hashCode(), item.title, item.id)
    }

    fun schedulePomodoro(context: Context, at: Long, title: String) {
        schedule(context, at, ACTION_POMODORO, 7001, title, "pomodoro")
    }

    fun cancelPomodoro(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java)
        manager.cancel(pending(context, ACTION_POMODORO, 7001, "", "pomodoro"))
        NotificationManagerCompat.from(context).cancel(7001)
    }

    fun showRunningPomodoro(context: Context, state: PomodoroState) {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).setData(android.net.Uri.parse("moji://pomodoro")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val remaining = if (state.running) ((state.targetEpochMillis - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0) else state.remainingSeconds.toLong()
        val notification = NotificationCompat.Builder(context, NotificationChannels.FOCUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(state.title)
            .setContentText("${phaseName(state.phase)} · ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}")
            .setOngoing(state.running)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
        NotificationManagerCompat.from(context).notify(7001, notification)
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
        val title = intent.getStringExtra("title") ?: "Moji"
        val isFocus = intent.action == AlarmScheduler.ACTION_POMODORO
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(
            context, if (isFocus) NotificationChannels.FOCUS else NotificationChannels.REMINDERS
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (isFocus) "专注时间结束" else "计划提醒")
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        NotificationManagerCompat.from(context).notify(if (isFocus) 7002 else title.hashCode(), notification)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val app = context.applicationContext as MojiApplication
            app.repository.snapshot.value.checkInItems.forEach { AlarmScheduler.schedulePlan(context, it) }
            val focus = app.settings.pomodoro.value
            if (focus.running && focus.targetEpochMillis > System.currentTimeMillis()) {
                AlarmScheduler.schedulePomodoro(context, focus.targetEpochMillis, focus.title)
                AlarmScheduler.showRunningPomodoro(context, focus)
            }
            pending.finish()
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
