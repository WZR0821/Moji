package com.raydon.moji.android.platform

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.raydon.moji.android.MainActivity
import com.raydon.moji.android.MojiApplication
import com.raydon.moji.android.R
import com.raydon.moji.android.ai.AIQAFixture
import com.raydon.moji.android.ai.AISettingsStore
import com.raydon.moji.android.ai.AIWeeklyLetter
import com.raydon.moji.android.ai.AIWeeklyLetterState
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The one Sunday 20:00 notification for 本周来信 (iOS `AIWeeklyLetterNotifier`).
 * An exact alarm when Android allows one, otherwise an inexact one; the
 * receiver reads the letter's title when it fires and puts the next one.
 */
object AIWeeklyLetterNotifier {
    const val ACTION = "com.raydon.moji.WEEKLY_LETTER"
    private const val alarmCode = 7101
    private const val notificationId = 7102
    private const val openCode = 7103

    /** Wanted when 本周来信 and AI 整理 are on and notifications are allowed; otherwise taken away. */
    fun reschedule(context: Context, now: ZonedDateTime = ZonedDateTime.now()) {
        val app = context.applicationContext
        val manager = app.getSystemService(AlarmManager::class.java) ?: return
        val operation = pending(app)
        manager.cancel(operation)
        // The photographed debug runs leave nothing behind on the device.
        if (AIQAFixture.isActive) return
        val ai = AISettingsStore.shared
        if (!ai.isReady || !ai.settings.value.weeklyLetter) return
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return
        val at = AIWeeklyLetter.nextNotification(now).toInstant().toEpochMilli()
        try {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        } catch (_: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        }
    }

    /** Posts 本周来信 for the week that has just ended, when it is still wanted. */
    internal fun post(context: Context, now: ZonedDateTime = ZonedDateTime.now()) {
        val app = context.applicationContext
        val ai = AISettingsStore.shared
        if (!ai.isReady || !ai.settings.value.weeklyLetter) return
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val title = AIWeeklyLetterState.shared.letter(AIWeeklyLetter.letterWeek(now))?.title
        val open = PendingIntent.getActivity(
            app, openCode, Intent(app, MainActivity::class.java).setData(Uri.parse("moji://review")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val quiet = (app as? MojiApplication)?.settings?.soundEnabled == false
        val notification = NotificationCompat.Builder(app, if (quiet) NotificationChannels.SILENT else NotificationChannels.WEEKLY_LETTER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("本周来信")
            .setContentText(AIWeeklyLetter.notificationBody(title))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        // Tagged with the identifier the two platforms share, so it replaces itself week to week.
        NotificationManagerCompat.from(app).notify(AIWeeklyLetter.notificationIdentifier, notificationId, notification)
    }

    private fun pending(context: Context): PendingIntent {
        val intent = Intent(context, WeeklyLetterReceiver::class.java)
            .setAction(ACTION)
            .setData(Uri.parse("moji://alarm/${AIWeeklyLetter.notificationIdentifier}"))
        return PendingIntent.getBroadcast(context, alarmCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

class WeeklyLetterReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AIWeeklyLetterNotifier.ACTION) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val now = ZonedDateTime.now()
                AIWeeklyLetterNotifier.post(context, now)
                // A minute on, so an alarm that arrives a moment early does not put this Sunday again.
                AIWeeklyLetterNotifier.reschedule(context, now.plusMinutes(1))
            } finally {
                pending.finish()
            }
        }
    }
}
