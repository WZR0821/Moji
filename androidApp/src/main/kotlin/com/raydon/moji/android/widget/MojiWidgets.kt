package com.raydon.moji.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.raydon.moji.android.MainActivity
import com.raydon.moji.android.MojiApplication
import com.raydon.moji.android.R
import com.raydon.moji.core.PlanSnapshot
import com.raydon.moji.core.ChecklistPolicy
import com.raydon.moji.android.platform.AlarmScheduler
import com.raydon.moji.core.CheckInItem
import com.raydon.moji.core.CountdownPolicy
import java.time.Instant
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val ACTION_TOGGLE_FIRST = "com.raydon.moji.widget.TOGGLE_FIRST"
private const val ACTION_QUICK_ADD = "com.raydon.moji.widget.QUICK_ADD"

abstract class BaseMojiWidget : AppWidgetProvider() {
    abstract fun render(context: Context, snapshot: PlanSnapshot): RemoteViews

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val snapshot = (context.applicationContext as MojiApplication).repository.freshSnapshot()
                ids.forEach { id ->
                    val views = render(context, snapshot)
                    if (this@BaseMojiWidget is PlanWidgetProvider) {
                        val compact = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) < 180
                        listOf(R.id.widget_secondary_action, R.id.widget_third_action).forEach {
                            views.setViewVisibility(it, if (compact) android.view.View.GONE else android.view.View.VISIBLE)
                        }
                    }
                    manager.updateAppWidget(id, views)
                }
            } finally { pending.finish() }
        }
    }

    protected fun base(context: Context, title: String): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_board)
        views.setTextViewText(R.id.widget_title, title)
        val open = PendingIntent.getActivity(
            context, title.hashCode(), Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_root, open)
        return views
    }
}

class PlanWidgetProvider : BaseMojiWidget() {
    override fun render(context: Context, snapshot: PlanSnapshot): RemoteViews {
        val app = context.applicationContext as MojiApplication
        val plans = snapshot.checkInItems
            .filter { visibleToday(it, app.settings.carriesOver) }.take(3)
        return base(context, "今日计划").apply {
            bindLines(this, plans.map { "□ ${it.title}" })
            setViewVisibility(R.id.widget_actions, android.view.View.VISIBLE)
            val buttons = listOf(R.id.widget_primary_action, R.id.widget_secondary_action, R.id.widget_third_action)
            buttons.forEachIndexed { index, id ->
                setTextViewText(id, app.settings.presetButton(index))
                setOnClickPendingIntent(id, action(context, ACTION_QUICK_ADD, 42 + index, index = index))
            }
            listOf(R.id.widget_line_1, R.id.widget_line_2, R.id.widget_line_3).forEachIndexed { index, id ->
                plans.getOrNull(index)?.let { plan ->
                    setOnClickPendingIntent(id, action(context, ACTION_TOGGLE_FIRST, 50 + index, planId = plan.id))
                }
            }
        }
    }
}

class CountdownWidgetProvider : BaseMojiWidget() {
    override fun render(context: Context, snapshot: PlanSnapshot): RemoteViews {
        val app = context.applicationContext as MojiApplication
        val now = Instant.now().toString()
        val zone = TimeZone.getDefault().id
        val events = snapshot.countdowns
            .map { it to CountdownPolicy.dayCount(it.targetDate, now, it.includesToday ?: false, zone) }
            .filter { it.second >= 0 }
            .sortedWith(compareByDescending<Pair<com.raydon.moji.core.CountdownEvent, Int>> { it.first.isPinned }.thenBy { it.second })
            .take(3)
        return base(context, "倒数日").apply {
            bindLines(this, events.map { "${it.first.title} · ${if (it.second == 0) "今天" else "还有 ${it.second} 天"}" })
        }
    }
}

class AnniversaryWidgetProvider : BaseMojiWidget() {
    override fun render(context: Context, snapshot: PlanSnapshot): RemoteViews {
        val app = context.applicationContext as MojiApplication
        val now = Instant.now().toString()
        val zone = TimeZone.getDefault().id
        val events = snapshot.countdowns
            .map { it to CountdownPolicy.dayCount(it.targetDate, now, it.includesToday ?: false, zone) }
            .filter { it.second < 0 }
            .sortedByDescending { it.second }
            .take(3)
        return base(context, "纪念日").apply {
            bindLines(this, events.map { "${it.first.title} · 已经 ${-it.second} 天" })
        }
    }
}

class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as MojiApplication
                val snapshot = app.repository.freshSnapshot()
                when (intent.action) {
                    ACTION_TOGGLE_FIRST -> snapshot.checkInItems
                        .firstOrNull { it.id == intent.getStringExtra("planId") && visibleToday(it, app.settings.carriesOver) }
                        ?.let { app.repository.togglePlan(it.id) }
                    ACTION_QUICK_ADD -> {
                        val index = intent.getIntExtra("presetIndex", 0).coerceIn(0, 2)
                        val now = Instant.now().toString()
                        app.repository.savePlan(CheckInItem(
                            id = UUID.randomUUID().toString(), title = app.settings.presetTitle(index),
                            category = app.settings.presetCategory(index).ifBlank { app.settings.defaultCategory }, scheduledStart = now, createdAt = now,
                            scheduleKind = "allDay", plannedDurationEnabled = false, detailsConfigured = false))
                    }
                }
                AlarmScheduler.reconcilePlans(context, app.repository.freshSnapshot().checkInItems)
                updateAll(context)
            } finally { pending.finish() }
        }
    }
}

private fun action(context: Context, action: String, code: Int, index: Int = 0, planId: String? = null): PendingIntent = PendingIntent.getBroadcast(
    context,
    code,
    Intent(context, WidgetActionReceiver::class.java).setAction(action).putExtra("presetIndex", index).putExtra("planId", planId),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)

private fun bindLines(views: RemoteViews, lines: List<String>) {
    val ids = listOf(R.id.widget_line_1, R.id.widget_line_2, R.id.widget_line_3)
    ids.forEachIndexed { index, id -> views.setTextViewText(id, lines.getOrNull(index) ?: "") }
}

fun updateAll(context: Context) {
    val manager = AppWidgetManager.getInstance(context)
    listOf(
        PlanWidgetProvider::class.java,
        CountdownWidgetProvider::class.java,
        AnniversaryWidgetProvider::class.java,
    ).forEach { provider ->
        val component = ComponentName(context, provider)
        val ids = manager.getAppWidgetIds(component)
        if (ids.isNotEmpty()) {
            val intent = Intent(context, provider).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            context.sendBroadcast(intent)
        }
    }
}

private fun visibleToday(item: CheckInItem, carriesOver: Boolean): Boolean =
    item.isArchived != true && item.kind == "planned" && item.status != "completed" && item.status != "skipped" && ChecklistPolicy.isVisible(
        item.scheduledStart, item.status, Instant.now().toString(), carriesOver, TimeZone.getDefault().id)
