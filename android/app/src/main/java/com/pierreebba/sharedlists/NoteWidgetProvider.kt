package com.pierreebba.sharedlists

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.widget.RemoteViews
import android.widget.Toast
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
        val appCtx = context.applicationContext
        when (intent.action) {
            ACTION_REFRESH_NOTE -> {
                val pending = goAsync()
                executor.execute {
                    try {
                        NoteFetcher.refreshLatestNote(appCtx)
                        updateAllWidgets(appCtx)
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_TOGGLE_LIKE -> {
                val pending = goAsync()
                executor.execute {
                    try {
                        val res = AppPrefs.toggleLikePartnerNote(appCtx)
                        if (res == null) {
                            val partner = AppPrefs.getPartnerName(appCtx)
                            mainHandler.post {
                                Toast.makeText(appCtx, "No note from $partner to like yet", Toast.LENGTH_SHORT).show()
                            }
                            return@execute
                        }
                        val (newState, detail) = res
                        val (updatedPartnerNote, likedNow) = detail
                        updateAllWidgets(appCtx)
                        if (likedNow) {
                            val partner = AppPrefs.getPartnerName(appCtx)
                            mainHandler.post {
                                Toast.makeText(appCtx, "Sent ♥ to $partner", Toast.LENGTH_SHORT).show()
                            }
                        }
                        NoteFetcher.syncLikedPartnerNote(appCtx, newState, updatedPartnerNote, likedNow)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_REFRESH_NOTE = "com.pierreebba.sharedlists.ACTION_REFRESH_NOTE"
        const val ACTION_TOGGLE_LIKE = "com.pierreebba.sharedlists.ACTION_TOGGLE_LIKE"
        private val executor = Executors.newSingleThreadExecutor()
        private val mainHandler = Handler(Looper.getMainLooper())

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
            val activeSnap = if (AppPrefs.isNoteFromToday(snap)) snap else null
            val partner = AppPrefs.getPartnerName(context)
            val me = AppPrefs.getCurrentUser(context)
            val views = RemoteViews(context.packageName, R.layout.widget_note)

            if (activeSnap != null && activeSnap.text.isNotBlank()) {
                val authorUpper = activeSnap.author.ifBlank { partner }.uppercase()
                views.setTextViewText(R.id.widgetHeader, "✦ NOTE FROM $authorUpper")
                views.setTextViewText(R.id.widgetNoteText, activeSnap.text)
                val rel = if (activeSnap.updatedAt > 0L) {
                    DateUtils.getRelativeTimeSpanString(
                        activeSnap.updatedAt,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS
                    ).toString()
                } else {
                    "Synced"
                }
                views.setTextViewText(R.id.widgetMeta, rel)
            } else {
                views.setTextViewText(R.id.widgetHeader, "✦ NOTE OF THE DAY")
                views.setTextViewText(
                    R.id.widgetNoteText,
                    "New day! Write today’s Note of the Day ✨"
                )
                views.setTextViewText(R.id.widgetMeta, "Tap ✎ Note to write")
            }

            val likedByMe = activeSnap != null && activeSnap.heartedBy.contains(me)
            val heartsCount = activeSnap?.hearts ?: 0
            views.setTextViewText(R.id.widgetHearts, if (likedByMe) "♥ $heartsCount" else "♡ $heartsCount")
            views.setInt(
                R.id.widgetHearts,
                "setBackgroundResource",
                if (likedByMe) R.drawable.widget_pill_liked_bg else R.drawable.widget_pill_bg
            )
            views.setTextColor(
                R.id.widgetHearts,
                if (likedByMe) 0xFFFFB4A2.toInt() else 0xFFE09F87.toInt()
            )

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

            val likeIntent = Intent(context, NoteWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_LIKE
            }
            val likePi = PendingIntent.getBroadcast(
                context,
                12,
                likeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetHearts, likePi)

            val writeIntent = Intent(context, QuickNoteActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val writePi = PendingIntent.getActivity(
                context,
                13,
                writeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetWriteBtn, writePi)

            manager.updateAppWidget(widgetId, views)
        }
    }
}
