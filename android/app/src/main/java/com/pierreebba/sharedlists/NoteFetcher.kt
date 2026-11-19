package com.pierreebba.sharedlists

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

object NoteFetcher {

    private fun httpGetString(urlStr: String, timeoutMs: Int = 8000): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                setRequestProperty("Cache-Control", "no-store")
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun unquoteJsonString(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val trimmed = raw.trim()
        return if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length >= 2) {
            try {
                JSONObject("{\"v\":$trimmed}").optString("v", "")
            } catch (_: Exception) {
                trimmed.trim('"')
            }
        } else {
            trimmed
        }
    }

    private fun decodeBase64Url(input: String): ByteArray {
        val rem = input.length % 4
        val padded = if (rem == 0) input else input + "=".repeat(4 - rem)
        return Base64.decode(padded, Base64.URL_SAFE)
    }

    private fun decodePayload(raw: String?): JSONObject? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()
        return try {
            when {
                s.startsWith("{") -> JSONObject(s)
                s.startsWith("RAW_") -> {
                    val bytes = decodeBase64Url(s.substring(4))
                    JSONObject(String(bytes, Charsets.UTF_8))
                }
                s.startsWith("GZ_") -> {
                    val bytes = decodeBase64Url(s.substring(3))
                    val text = GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    JSONObject(text)
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun applyWidgetSnapshotJson(context: Context, payloadStr: String?): Boolean {
        val obj = decodePayload(payloadStr) ?: return false
        val me = AppPrefs.getCurrentUser(context)
        val partner = if (me == "Ebba") "Pierre" else "Ebba"

        val partnerObj = if (me == "Pierre") obj.optJSONObject("e") else obj.optJSONObject("p")
        val fallbackMot = obj.optJSONObject("m")
        val chosen = if (partnerObj != null && partnerObj.optString("text").isNotBlank()) partnerObj else fallbackMot
        if (chosen == null || chosen.optString("text").isBlank()) return false

        val snap = PartnerNoteSnapshot(
            text = chosen.optString("text"),
            author = chosen.optString("author").ifBlank { partner },
            hearts = chosen.optInt("hearts", 0),
            updatedAt = chosen.optLong("updatedAt", System.currentTimeMillis())
        )
        return AppPrefs.savePartnerNoteIfNewer(context, snap)
    }

    fun refreshLatestNote(context: Context): Boolean {
        var updated = false

        // 1. Permanent snapshot from KV
        val rawMeta = unquoteJsonString(httpGetString("${AppPrefs.KV_GET_BASE}/ebba_v2_wn_meta"))
        if (rawMeta.contains("-")) {
            val dash = rawMeta.lastIndexOf('-')
            val gen = rawMeta.substring(0, dash)
            val count = rawMeta.substring(dash + 1).toIntOrNull() ?: 0
            if (gen.isNotBlank() && count in 1..40) {
                val sb = StringBuilder()
                var ok = true
                for (i in 0 until count) {
                    val part = unquoteJsonString(httpGetString("${AppPrefs.KV_GET_BASE}/ebba_v2_wn_${gen}_$i"))
                    if (part.isBlank()) {
                        ok = false
                        break
                    }
                    sb.append(part)
                }
                if (ok && applyWidgetSnapshotJson(context, sb.toString())) {
                    updated = true
                }
            }
        }

        // 2. Latest real-time messages from ntfy widget channel
        val ntfyLines = httpGetString("https://ntfy.sh/${AppPrefs.NTFY_WIDGET_TOPIC}/json?poll=1&since=all")
        if (!ntfyLines.isNullOrBlank()) {
            for (line in ntfyLines.split('\n')) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                try {
                    val evt = JSONObject(trimmed)
                    if (evt.optString("event") == "message") {
                        if (applyWidgetSnapshotJson(context, evt.optString("message"))) {
                            updated = true
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        return updated
    }

    fun extractTabFromClickUrl(clickUrl: String?): String {
        if (clickUrl.isNullOrBlank()) return "lists"
        return try {
            val tab = Uri.parse(clickUrl).getQueryParameter("tab")
            if (tab == "recipes" || tab == "words" || tab == "lists") tab else "lists"
        } catch (_: Exception) {
            "lists"
        }
    }

    fun pollMissedNotifications(context: Context) {
        val topic = AppPrefs.getMyNotifTopic(context)
        val raw = httpGetString("https://ntfy.sh/$topic/json?poll=1&since=12h") ?: return
        for (line in raw.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            try {
                val evt = JSONObject(trimmed)
                if (evt.optString("event") != "message") continue
                val id = evt.optString("id")
                val title = evt.optString("title", "Pierre & Ebba")
                val message = evt.optString("message", "")
                val tab = extractTabFromClickUrl(evt.optString("click"))
                if (message.isNotBlank()) {
                    NotificationHelper.showPartnerNotification(
                        context = context,
                        notifId = id,
                        title = title,
                        body = message,
                        tab = tab,
                        deduplicate = true
                    )
                }
            } catch (_: Exception) {
            }
        }
    }
}
