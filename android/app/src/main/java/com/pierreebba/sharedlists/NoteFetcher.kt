package com.pierreebba.sharedlists

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
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

    private fun httpPost(urlStr: String, body: String?, contentType: String?, timeoutMs: Int = 8000): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "POST"
                doOutput = true
                if (!contentType.isNullOrBlank()) {
                    setRequestProperty("Content-Type", contentType)
                }
                if (body != null) {
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    setFixedLengthStreamingMode(bytes.size)
                    outputStream.use { it.write(bytes) }
                } else {
                    setFixedLengthStreamingMode(0)
                }
            }
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun kvPostWithRetry(key: String, value: String, retries: Int = 2): Boolean {
        val url = "${AppPrefs.KV_UPDATE_BASE}/$key/$value"
        for (i in 0..retries) {
            if (httpPost(url, null, null)) return true
            if (i < retries) {
                try {
                    Thread.sleep(180L * (i + 1))
                } catch (_: InterruptedException) {
                    return false
                }
            }
        }
        return false
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
        val p = AppPrefs.parseNoteEntry(obj.optJSONObject("p"))
        val e = AppPrefs.parseNoteEntry(obj.optJSONObject("e"))
        val m = AppPrefs.parseNoteEntry(obj.optJSONObject("m"))
        return AppPrefs.mergeNotesState(context, p, e, m)
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

    fun publishWidgetSnapshot(state: WidgetNotesState): Boolean {
        val root = JSONObject()
        state.pierre?.takeIf { it.text.isNotBlank() }?.let { root.put("p", it.toJson()) }
        state.ebba?.takeIf { it.text.isNotBlank() }?.let { root.put("e", it.toJson()) }
        state.petitMot?.takeIf { it.text.isNotBlank() }?.let { root.put("m", it.toJson()) }
        val snapJson = root.toString()

        val ntfyPayload = JSONObject().apply {
            put("topic", AppPrefs.NTFY_WIDGET_TOPIC)
            put("message", snapJson)
        }
        val ntfyOk = httpPost("https://ntfy.sh/", ntfyPayload.toString(), "application/json; charset=utf-8")

        var kvOk = false
        try {
            val encoded = "RAW_" + Base64.encodeToString(
                snapJson.toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            val chunkSize = 175
            val chunks = ArrayList<String>()
            var idx = 0
            while (idx < encoded.length) {
                val end = (idx + chunkSize).coerceAtMost(encoded.length)
                chunks.add(encoded.substring(idx, end))
                idx = end
            }
            val gen = java.lang.Long.toString(System.currentTimeMillis(), 36)
            var allChunksOk = true
            for (i in chunks.indices) {
                if (!kvPostWithRetry("ebba_v2_wn_${gen}_$i", chunks[i])) {
                    allChunksOk = false
                    break
                }
            }
            if (allChunksOk) {
                kvOk = kvPostWithRetry("ebba_v2_wn_meta", "$gen-${chunks.size}")
            }
        } catch (_: Exception) {
        }

        return ntfyOk || kvOk
    }

    fun publishPartialNoteState(
        actor: String,
        petitMot: NoteEntry?,
        updatedOwner: String,
        updatedOwnerNote: NoteEntry,
        activityTitle: String?,
        activityBody: String?
    ): Boolean {
        val now = System.currentTimeMillis()
        val rand = ((Math.random() * 90000).toInt() + 10000)

        val stObj = JSONObject().apply {
            val pnObj = JSONObject()
            pnObj.put(updatedOwner, updatedOwnerNote.toJson())
            put("partnerNotes", pnObj)
            if (petitMot != null) {
                put("petitMot", petitMot.toJson())
            }
        }

        val env = JSONObject().apply {
            put("v", 1)
            put("sender", "and_wid_${java.lang.Long.toString(now, 36)}_$rand")
            put("actor", actor)
            put("ts", now)
            if (!activityTitle.isNullOrBlank()) {
                val act = JSONObject().apply {
                    put("id", "act_${java.lang.Long.toString(now, 36)}_$rand")
                    put("actor", actor)
                    put("category", "mot")
                    put("title", activityTitle)
                    put("body", activityBody ?: "")
                    put("tab", "lists")
                }
                put("activity", act)
            } else {
                put("activity", JSONObject.NULL)
            }
            put("state", stObj)
        }

        return httpPost(
            "https://ntfy.sh/${AppPrefs.NTFY_STATE_TOPIC}",
            env.toString(),
            "text/plain; charset=utf-8"
        )
    }

    fun sendPartnerPushNotification(
        context: Context,
        title: String,
        body: String,
        tag: String
    ): Boolean {
        val payload = JSONObject().apply {
            put("topic", AppPrefs.getPartnerNotifTopic(context))
            put("title", title)
            put("message", body)
            put("priority", 4)
            put("tags", JSONArray().apply { put(tag) })
            put("click", "${AppPrefs.APP_URL}?tab=lists")
            put("icon", "${AppPrefs.APP_URL}icon-192.png")
        }
        return httpPost("https://ntfy.sh/", payload.toString(), "application/json; charset=utf-8")
    }

    fun syncWrittenNote(context: Context, state: WidgetNotesState, myNote: NoteEntry): Boolean {
        val me = AppPrefs.getCurrentUser(context)
        val snapOk = publishWidgetSnapshot(state)
        val stateOk = publishPartialNoteState(
            actor = me,
            petitMot = myNote,
            updatedOwner = me,
            updatedOwnerNote = myNote,
            activityTitle = "💌 New Note of the Day from $me",
            activityBody = myNote.text
        )
        sendPartnerPushNotification(
            context = context,
            title = "💌 New Note of the Day from $me",
            body = myNote.text,
            tag = "love_letter"
        )
        return snapOk || stateOk
    }

    fun syncLikedPartnerNote(
        context: Context,
        state: WidgetNotesState,
        partnerNote: NoteEntry,
        likedNow: Boolean
    ): Boolean {
        val me = AppPrefs.getCurrentUser(context)
        val partner = AppPrefs.getPartnerName(context)
        val matchingMot = state.petitMot?.takeIf {
            it.author == partner && it.text == partnerNote.text
        }
        val actTitle = if (likedNow) "♥ $me liked your Note of the Day" else null
        val actBody = if (likedNow) "\"${partnerNote.text}\"" else null

        val snapOk = publishWidgetSnapshot(state)
        val stateOk = publishPartialNoteState(
            actor = me,
            petitMot = matchingMot,
            updatedOwner = partner,
            updatedOwnerNote = partnerNote,
            activityTitle = actTitle,
            activityBody = actBody
        )
        if (likedNow) {
            sendPartnerPushNotification(
                context = context,
                title = "♥ $me liked your Note of the Day",
                body = "\"${partnerNote.text}\"",
                tag = "heart"
            )
        }
        return snapOk || stateOk
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
        if (!AppPrefs.isLivePushEnabled(context)) return
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
