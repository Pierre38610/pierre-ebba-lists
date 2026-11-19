package com.pierreebba.sharedlists

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PartnerNoteSnapshot(
    val text: String,
    val author: String,
    val hearts: Int,
    val updatedAt: Long
)

object AppPrefs {
    private const val PREF_NAME = "pierre_ebba_native_prefs"

    const val APP_URL = "https://pierre38610.github.io/pierre-ebba-lists/"
    const val NTFY_NOTIF_PIERRE = "pierre_ebba_notif_pierre_907521e5"
    const val NTFY_NOTIF_EBBA = "pierre_ebba_notif_ebba_907521e5"
    const val NTFY_WIDGET_TOPIC = "pierre_ebba_widget_907521e5"
    const val KV_GET_BASE = "https://keyvalue.immanuel.co/api/KeyVal/GetValue/x0c969je"

    private const val KEY_USER = "current_user"
    private const val KEY_LIVE_PUSH = "live_push_enabled"
    private const val KEY_NOTE_TEXT = "partner_note_text"
    private const val KEY_NOTE_AUTHOR = "partner_note_author"
    private const val KEY_NOTE_HEARTS = "partner_note_hearts"
    private const val KEY_NOTE_UPDATED = "partner_note_updated"
    private const val KEY_SEEN_IDS = "seen_notif_ids"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getCurrentUser(context: Context): String {
        val v = prefs(context).getString(KEY_USER, "Pierre")
        return if (v == "Ebba") "Ebba" else "Pierre"
    }

    fun getPartnerName(context: Context): String {
        return if (getCurrentUser(context) == "Ebba") "Pierre" else "Ebba"
    }

    fun getMyNotifTopic(context: Context): String {
        return if (getCurrentUser(context) == "Ebba") NTFY_NOTIF_EBBA else NTFY_NOTIF_PIERRE
    }

    fun isLivePushEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_LIVE_PUSH, true)
    }

    fun setLivePushEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LIVE_PUSH, enabled).apply()
    }

    fun getPartnerNote(context: Context): PartnerNoteSnapshot {
        val p = prefs(context)
        return PartnerNoteSnapshot(
            text = p.getString(KEY_NOTE_TEXT, "") ?: "",
            author = p.getString(KEY_NOTE_AUTHOR, getPartnerName(context)) ?: getPartnerName(context),
            hearts = p.getInt(KEY_NOTE_HEARTS, 0),
            updatedAt = p.getLong(KEY_NOTE_UPDATED, 0L)
        )
    }

    fun savePartnerNoteIfNewer(context: Context, note: PartnerNoteSnapshot): Boolean {
        if (note.text.isBlank()) return false
        val current = getPartnerNote(context)
        if (note.updatedAt > 0L && current.updatedAt > 0L && note.updatedAt < current.updatedAt) {
            return false
        }
        prefs(context).edit()
            .putString(KEY_NOTE_TEXT, note.text)
            .putString(KEY_NOTE_AUTHOR, note.author)
            .putInt(KEY_NOTE_HEARTS, note.hearts)
            .putLong(KEY_NOTE_UPDATED, note.updatedAt)
            .apply()
        return true
    }

    fun updateFromBridgeJson(context: Context, rawJson: String): Boolean {
        val obj = JSONObject(rawJson)
        val user = if (obj.optString("currentUser") == "Ebba") "Ebba" else "Pierre"
        val p = prefs(context)
        p.edit().putString(KEY_USER, user).apply()

        val partner = if (user == "Ebba") "Pierre" else "Ebba"
        val partnerNotes = obj.optJSONObject("partnerNotes")
        val pObj = partnerNotes?.optJSONObject(partner)
        val motObj = obj.optJSONObject("petitMot")
        val chosen = if (pObj != null && pObj.optString("text").isNotBlank()) pObj else motObj

        if (chosen != null && chosen.optString("text").isNotBlank()) {
            val snap = PartnerNoteSnapshot(
                text = chosen.optString("text"),
                author = chosen.optString("author").ifBlank { partner },
                hearts = chosen.optInt("hearts", 0),
                updatedAt = chosen.optLong("updatedAt", System.currentTimeMillis())
            )
            p.edit()
                .putString(KEY_NOTE_TEXT, snap.text)
                .putString(KEY_NOTE_AUTHOR, snap.author)
                .putInt(KEY_NOTE_HEARTS, snap.hearts)
                .putLong(KEY_NOTE_UPDATED, snap.updatedAt)
                .apply()
            return true
        }
        return false
    }

    @Synchronized
    fun markNotifSeen(context: Context, id: String): Boolean {
        if (id.isBlank()) return true
        val p = prefs(context)
        val raw = p.getString(KEY_SEEN_IDS, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        val list = ArrayList<String>(arr.length() + 1)
        for (i in 0 until arr.length()) {
            val item = arr.optString(i)
            if (item == id) return false
            if (item.isNotBlank()) list.add(item)
        }
        list.add(id)
        while (list.size > 80) {
            list.removeAt(0)
        }
        val out = JSONArray()
        list.forEach { out.put(it) }
        p.edit().putString(KEY_SEEN_IDS, out.toString()).apply()
        return true
    }
}
