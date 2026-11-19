package com.pierreebba.sharedlists

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // Android 15 safe: schedule WorkManager only (never start foregroundService directly from boot)
            PartnerSyncWorker.schedule(context)
            NoteWidgetProvider.refreshAsync(context)
        }
    }
}
