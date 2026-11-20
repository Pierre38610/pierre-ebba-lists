package com.pierreebba.sharedlists

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object PartnerStreamManager {

    @Volatile
    private var running = false

    @Volatile
    private var currentTopic: String? = null

    @Volatile
    private var activeConn: HttpURLConnection? = null

    private var workerThread: Thread? = null

    @Synchronized
    fun startIfAllowed(context: Context) {
        val appCtx = context.applicationContext
        if (!AppPrefs.isLivePushEnabled(appCtx) || !NotificationHelper.hasPostPermission(appCtx)) {
            stop()
            return
        }
        val targetTopic = AppPrefs.getMyNotifTopic(appCtx)
        if (running && currentTopic == targetTopic && workerThread?.isAlive == true) {
            return
        }
        stop()
        running = true
        currentTopic = targetTopic
        workerThread = Thread({ streamLoop(appCtx, targetTopic) }, "pe-live-ntfy").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun restartIfAllowed(context: Context) {
        stop()
        startIfAllowed(context)
    }

    @Synchronized
    fun stop() {
        running = false
        currentTopic = null
        try { activeConn?.disconnect() } catch (_: Exception) {}
        activeConn = null
        workerThread?.interrupt()
        workerThread = null
    }

    private fun streamLoop(appCtx: Context, myTopic: String) {
        var backoffMs = 2000L
        while (running && currentTopic == myTopic) {
            var conn: HttpURLConnection? = null
            try {
                if (AppPrefs.isLivePushEnabled(appCtx)) {
                    NoteFetcher.pollMissedNotifications(appCtx)
                }
                if (NoteFetcher.refreshLatestNote(appCtx)) {
                    NoteWidgetProvider.updateAllWidgets(appCtx)
                }

                if (!running || currentTopic != myTopic) break

                val streamUrl = "https://ntfy.sh/$myTopic,${AppPrefs.NTFY_WIDGET_TOPIC}/json"
                conn = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 0
                    requestMethod = "GET"
                }
                activeConn = conn

                if (conn.responseCode in 200..299) {
                    backoffMs = 2000L
                    BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                        while (running && currentTopic == myTopic) {
                            val line = reader.readLine() ?: break
                            val trimmed = line.trim()
                            if (trimmed.isEmpty()) continue
                            handleStreamLine(appCtx, trimmed, myTopic)
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
                if (activeConn === conn) {
                    activeConn = null
                }
            }

            if (!running || currentTopic != myTopic) break
            try {
                Thread.sleep(backoffMs)
            } catch (_: InterruptedException) {
                break
            }
            backoffMs = (backoffMs * 2).coerceAtMost(30000L)
        }
    }

    private fun handleStreamLine(context: Context, line: String, myNotifTopic: String) {
        try {
            val evt = JSONObject(line)
            if (evt.optString("event") != "message") return
            val topic = evt.optString("topic")
            if (topic == myNotifTopic) {
                if (!AppPrefs.isLivePushEnabled(context)) return
                val id = evt.optString("id")
                val title = evt.optString("title", "Pierre & Ebba")
                val msg = evt.optString("message", "")
                val tab = NoteFetcher.extractTabFromClickUrl(evt.optString("click"))
                if (msg.isNotBlank()) {
                    NotificationHelper.showPartnerNotification(
                        context = context,
                        notifId = id,
                        title = title,
                        body = msg,
                        tab = tab,
                        deduplicate = true
                    )
                }
            } else if (topic == AppPrefs.NTFY_WIDGET_TOPIC) {
                if (NoteFetcher.applyWidgetSnapshotJson(context, evt.optString("message"))) {
                    NoteWidgetProvider.updateAllWidgets(context)
                }
            }
        } catch (_: Exception) {
        }
    }
}
