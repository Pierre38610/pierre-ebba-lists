package com.pierreebba.sharedlists

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class LiveNotificationService : Service() {

    @Volatile
    private var running = false
    private var workerThread: Thread? = null

    @Volatile
    private var activeConn: HttpURLConnection? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!AppPrefs.isLivePushEnabled(this) || !NotificationHelper.hasPostPermission(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val notif = NotificationHelper.buildLiveServiceNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.LIVE_STATUS_NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            )
        } else {
            startForeground(NotificationHelper.LIVE_STATUS_NOTIF_ID, notif)
        }

        if (!running) {
            running = true
            workerThread = Thread(::streamLoop, "pe-live-ntfy").apply {
                isDaemon = true
                start()
            }
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        running = false
        try { activeConn?.disconnect() } catch (_: Exception) {}
        stopSelf(startId)
    }

    override fun onDestroy() {
        running = false
        try { activeConn?.disconnect() } catch (_: Exception) {}
        workerThread?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun streamLoop() {
        var backoffMs = 2000L
        while (running) {
            var conn: HttpURLConnection? = null
            try {
                // First pull any brief missed updates & refresh widget
                NoteFetcher.pollMissedNotifications(this)
                if (NoteFetcher.refreshLatestNote(this)) {
                    NoteWidgetProvider.updateAllWidgets(this)
                }

                val myTopic = AppPrefs.getMyNotifTopic(this)
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
                        while (running) {
                            val line = reader.readLine() ?: break
                            val trimmed = line.trim()
                            if (trimmed.isEmpty()) continue
                            handleStreamLine(trimmed, myTopic)
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
                activeConn = null
            }

            if (!running) break
            try {
                Thread.sleep(backoffMs)
            } catch (_: InterruptedException) {
                break
            }
            backoffMs = (backoffMs * 2).coerceAtMost(60000L)
        }
    }

    private fun handleStreamLine(line: String, myNotifTopic: String) {
        try {
            val evt = JSONObject(line)
            if (evt.optString("event") != "message") return
            val topic = evt.optString("topic")
            if (topic == myNotifTopic) {
                val id = evt.optString("id")
                val title = evt.optString("title", "Pierre & Ebba")
                val msg = evt.optString("message", "")
                val tab = NoteFetcher.extractTabFromClickUrl(evt.optString("click"))
                if (msg.isNotBlank()) {
                    NotificationHelper.showPartnerNotification(
                        context = this,
                        notifId = id,
                        title = title,
                        body = msg,
                        tab = tab,
                        deduplicate = true
                    )
                }
            } else if (topic == AppPrefs.NTFY_WIDGET_TOPIC) {
                if (NoteFetcher.applyWidgetSnapshotJson(this, evt.optString("message"))) {
                    NoteWidgetProvider.updateAllWidgets(this)
                }
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        fun startIfAllowed(context: Context) {
            if (!AppPrefs.isLivePushEnabled(context) || !NotificationHelper.hasPostPermission(context)) {
                return
            }
            try {
                val intent = Intent(context, LiveNotificationService::class.java)
                context.startForegroundService(intent)
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, LiveNotificationService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
