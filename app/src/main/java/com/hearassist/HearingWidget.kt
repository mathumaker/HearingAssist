package com.hearassist

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Home-screen widget: one tap turns hearing assist on or off. */
class HearingWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, buildViews(context))
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, HearingWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, buildViews(context))
        }

        private fun buildViews(context: Context): RemoteViews {
            val on = HearingService.isRunning
            val tap = PendingIntent.getActivity(
                context, 0,
                Intent(context, ToggleActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                ),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return RemoteViews(context.packageName, R.layout.widget_hearing).apply {
                setImageViewResource(R.id.widget_icon, if (on) R.drawable.ic_ear_on else R.drawable.ic_ear_off)
                setTextViewText(R.id.widget_label, if (on) "Hearing ON" else "Hearing OFF")
                setInt(R.id.widget_root, "setBackgroundResource", if (on) R.drawable.widget_bg_on else R.drawable.widget_bg_off)
                setOnClickPendingIntent(R.id.widget_root, tap)
            }
        }
    }
}
