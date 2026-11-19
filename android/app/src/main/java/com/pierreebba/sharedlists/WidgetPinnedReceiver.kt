package com.pierreebba.sharedlists

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class WidgetPinnedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            NoteWidgetProvider.refreshAsync(context)
            Toast.makeText(context, "Note of the Day widget added! ✦", Toast.LENGTH_SHORT).show()
        }
    }
}
