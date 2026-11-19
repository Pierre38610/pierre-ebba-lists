package com.pierreebba.sharedlists

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.widget.RemoteViews
import java.util.concurrent.Executors

class NoteWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            bindWidget(context, appWidgetManager, id)
        }
        refreshAsync(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH_NOTE) {
            refreshAsync(context)
        }
    }

    companion object {
        const val ACTION_REFRESH_NOTE = "com.pierreebba.sharedlists.ACTION_REFRESH_NOTE"
        private val executor = Executors.newSingleThreadExecutor()

        fun refreshAsync(context: Context) {
            val appCtx = context.applicationContext
            executor.execute {
                NoteFetcher.refreshLatestNote(appCtx)
                updateAllWidgets(appCtx)
            }
        }

        fun updateAllWidgets(context: Context) {
            val appCtx = context.applicationContext
            val manager = AppWidgetManager.getInstance(appCtx)
            val ids = manager.getAppWidgetIds(ComponentName(appCtx, NoteWidgetProvider::class.java))
            for (id in ids) {
                bindWidget(appCtx, manager, id)
            }
        }

        private fun bindWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val snap = AppPrefs.getPartnerNote(context)
            val partner = AppPrefs.getPartnerName(context)
            val views = RemoteViews(context.packageName, R.layout.widget_note)

            val authorUpper = snap.author.ifBlank { partner }.uppercase()
            views.setTextViewText(R.id.widgetHeader, "✦ NOTE FROM $authorUpper")

            if (snap.text.isNotBlank()) {
                views.setTextViewText(R.id.widgetNoteText, snap.text)
                val rel = if (snap.updatedAt > 0L) {
                    DateUtils.getRelativeTimeSpanString(
                        snap.updatedAt,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS
                    ).toString()
                } else {
                    "Synced"
                }
                views.setTextViewText(R.id.widgetMeta, rel)
            } else {
                views.setTextViewText(
                    R.id.widgetNoteText,
                    "No note from $partner yet — tap to send one ♥"
                )
                views.setTextViewText(R.id.widgetMeta, "Pierre & Ebba")
            }
            views.setTextViewText(R.id.widgetHearts, "♥ ${snap.hearts}")

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_TAB, "lists")
            }
            val openPi = PendingIntent.getActivity(
                context,
                10,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, openPi)

            val refreshIntent = Intent(context, NoteWidgetProvider::class.java).apply {
                action = ACTION_REFRESH_NOTE
            }
            val refreshPi = PendingIntent.getBroadcast(
                context,
                11,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetRefreshBtn, refreshPi)

            manager.updateAppWidget(widgetId, views)
        }
    }
}
