package com.pierreebba.sharedlists

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class NoteEntry(
    val text: String,
    val author: String,
    val hearts: Int,
    val heartedBy: List<String>,
    val updatedAt: Long
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("text", text)
        obj.put("author", author)
        obj.put("hearts", hearts)
        val arr = JSONArray()
        heartedBy.forEach { arr.put(it) }
        obj.put("heartedBy", arr)
        obj.put("updatedAt", updatedAt)
        return obj
    }
}

data class WidgetNotesState(
    val pierre: NoteEntry?,
    val ebba: NoteEntry?,
    val petitMot: NoteEntry?
)

object AppPrefs {
    private const val PREF_NAME = "pierre_ebba_native_prefs"

    const val APP_URL = "https://pierre38610.github.io/pierre-ebba-lists/"
    const val NTFY_STATE_TOPIC = "pierre_ebba_shared_v2_907521e5"
    const val NTFY_NOTIF_PIERRE = "pierre_ebba_notif_pierre_907521e5"
    const val NTFY_NOTIF_EBBA = "pierre_ebba_notif_ebba_907521e5"
    const val NTFY_WIDGET_TOPIC = "pierre_ebba_widget_907521e5"
    const val KV_GET_BASE = "https://keyvalue.immanuel.co/api/KeyVal/GetValue/x0c969je"
    const val KV_UPDATE_BASE = "https://keyvalue.immanuel.co/api/KeyVal/UpdateValue/x0c969je"

    private const val KEY_USER = "current_user"
    private const val KEY_LIVE_PUSH = "live_push_enabled"
    private const val KEY_NOTE_TEXT = "partner_note_text"
    private const val KEY_NOTE_AUTHOR = "partner_note_author"
    private const val KEY_NOTE_HEARTS = "partner_note_hearts"
    private const val KEY_NOTE_UPDATED = "partner_note_updated"
    private const val KEY_NOTES_STATE_JSON = "widget_notes_state_v2"
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

    fun getPartnerNotifTopic(context: Context): String {
        return if (getCurrentUser(context) == "Ebba") NTFY_NOTIF_PIERRE else NTFY_NOTIF_EBBA
    }

    fun isLivePushEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_LIVE_PUSH, true)
    }

    fun setLivePushEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LIVE_PUSH, enabled).apply()
    }

    fun parseNoteEntry(obj: JSONObject?): NoteEntry? {
        if (obj == null) return null
        val text = obj.optString("text", "").trim()
        if (text.isBlank()) return null
        val author = obj.optString("author", "").trim()
        val hearts = obj.optInt("hearts", 0).coerceAtLeast(0)
        val heartedArr = obj.optJSONArray("heartedBy")
        val heartedBy = ArrayList<String>()
        if (heartedArr != null) {
            for (i in 0 until heartedArr.length()) {
                val item = heartedArr.optString(i, "").trim()
                if (item.isNotBlank() && !heartedBy.contains(item)) {
                    heartedBy.add(item)
                }
            }
        }
        val updatedAt = obj.optLong("updatedAt", 0L)
        return NoteEntry(
            text = text,
            author = author,
            hearts = hearts,
            heartedBy = heartedBy,
            updatedAt = updatedAt
        )
    }

    private fun seedAuthors(state: WidgetNotesState): WidgetNotesState {
        var pierre = state.pierre
        var ebba = state.ebba
        val mot = state.petitMot
        if (mot != null && mot.text.isNotBlank()) {
            if (mot.author == "Pierre" && (pierre == null || mot.updatedAt > pierre.updatedAt)) {
                pierre = mot
            } else if (mot.author == "Ebba" && (ebba == null || mot.updatedAt > ebba.updatedAt)) {
                ebba = mot
            }
        }
        return WidgetNotesState(pierre, ebba, mot)
    }

    @Synchronized
    fun getNotesState(context: Context): WidgetNotesState {
        val p = prefs(context)
        val raw = p.getString(KEY_NOTES_STATE_JSON, null)
        if (!raw.isNullOrBlank()) {
            try {
                val obj = JSONObject(raw)
                return seedAuthors(
                    WidgetNotesState(
                        pierre = parseNoteEntry(obj.optJSONObject("p")),
                        ebba = parseNoteEntry(obj.optJSONObject("e")),
                        petitMot = parseNoteEntry(obj.optJSONObject("m"))
                    )
                )
            } catch (_: Exception) {
            }
        }

        // Legacy v1.0.0 migration fallback
        val legacyText = p.getString(KEY_NOTE_TEXT, "") ?: ""
        if (legacyText.isNotBlank()) {
            val legacyAuthor = (p.getString(KEY_NOTE_AUTHOR, getPartnerName(context)) ?: getPartnerName(context)).ifBlank {
                getPartnerName(context)
            }
            val legacyEntry = NoteEntry(
                text = legacyText,
                author = legacyAuthor,
                hearts = p.getInt(KEY_NOTE_HEARTS, 0).coerceAtLeast(0),
                heartedBy = emptyList(),
                updatedAt = p.getLong(KEY_NOTE_UPDATED, 0L)
            )
            return seedAuthors(
                WidgetNotesState(
                    pierre = if (legacyAuthor == "Pierre") legacyEntry else null,
                    ebba = if (legacyAuthor == "Ebba") legacyEntry else null,
                    petitMot = legacyEntry
                )
            )
        }
        return WidgetNotesState(null, null, null)
    }

    @Synchronized
    fun saveNotesState(context: Context, state: WidgetNotesState) {
        val seeded = seedAuthors(state)
        val obj = JSONObject()
        seeded.pierre?.let { obj.put("p", it.toJson()) }
        seeded.ebba?.let { obj.put("e", it.toJson()) }
        seeded.petitMot?.let { obj.put("m", it.toJson()) }

        val editor = prefs(context).edit().putString(KEY_NOTES_STATE_JSON, obj.toString())
        val partner = getPartnerName(context)
        val partnerNote = (if (partner == "Pierre") seeded.pierre else seeded.ebba) ?: seeded.petitMot
        if (partnerNote != null) {
            editor.putString(KEY_NOTE_TEXT, partnerNote.text)
                .putString(KEY_NOTE_AUTHOR, partnerNote.author)
                .putInt(KEY_NOTE_HEARTS, partnerNote.hearts)
                .putLong(KEY_NOTE_UPDATED, partnerNote.updatedAt)
        }
        editor.apply()
    }

    private fun pickNewer(current: NoteEntry?, incoming: NoteEntry?): Pair<NoteEntry?, Boolean> {
        if (incoming == null || incoming.text.isBlank()) return Pair(current, false)
        if (current == null || current.text.isBlank()) return Pair(incoming, true)
        if (incoming.updatedAt > current.updatedAt) return Pair(incoming, true)
        if (incoming.updatedAt == current.updatedAt && incoming != current) return Pair(incoming, true)
        return Pair(current, false)
    }

    @Synchronized
    fun mergeNotesState(
        context: Context,
        pierre: NoteEntry?,
        ebba: NoteEntry?,
        petitMot: NoteEntry?
    ): Boolean {
        val cur = getNotesState(context)
        val (nextP, pChanged) = pickNewer(cur.pierre, pierre)
        val (nextE, eChanged) = pickNewer(cur.ebba, ebba)
        val (nextM, mChanged) = pickNewer(cur.petitMot, petitMot)

        val nextSeeded = seedAuthors(WidgetNotesState(nextP, nextE, nextM))
        val changed = pChanged || eChanged || mChanged || nextSeeded != cur
        if (changed) {
            saveNotesState(context, nextSeeded)
        }
        return changed
    }

    fun getPartnerOwnNote(context: Context): NoteEntry? {
        val state = getNotesState(context)
        val partner = getPartnerName(context)
        val slot = if (partner == "Pierre") state.pierre else state.ebba
        if (slot != null && slot.text.isNotBlank()) return slot
        val mot = state.petitMot
        if (mot != null && mot.text.isNotBlank() && mot.author == partner) return mot
        return null
    }

    fun getPartnerNote(context: Context): NoteEntry? {
        val own = getPartnerOwnNote(context)
        if (own != null) return own
        return getNotesState(context).petitMot?.takeIf { it.text.isNotBlank() }
    }

    @Synchronized
    fun updateFromBridgeJson(context: Context, rawJson: String): Boolean {
        val obj = JSONObject(rawJson)
        val user = if (obj.optString("currentUser") == "Ebba") "Ebba" else "Pierre"
        prefs(context).edit().putString(KEY_USER, user).apply()

        val partnerNotes = obj.optJSONObject("partnerNotes")
        val pNote = parseNoteEntry(partnerNotes?.optJSONObject("Pierre"))
        val eNote = parseNoteEntry(partnerNotes?.optJSONObject("Ebba"))
        val mNote = parseNoteEntry(obj.optJSONObject("petitMot"))

        val next = seedAuthors(WidgetNotesState(pNote, eNote, mNote))
        saveNotesState(context, next)
        return next.pierre != null || next.ebba != null || next.petitMot != null
    }

    @Synchronized
    fun toggleLikePartnerNote(context: Context): Pair<WidgetNotesState, Pair<NoteEntry, Boolean>>? {
        val me = getCurrentUser(context)
        val partner = getPartnerName(context)
        val cur = getNotesState(context)
        val target = getPartnerOwnNote(context) ?: return null

        val list = ArrayList(target.heartedBy)
        val likedNow: Boolean
        val nextHearts: Int
        if (list.contains(me)) {
            list.removeAll { it == me }
            nextHearts = (target.hearts - 1).coerceAtLeast(0)
            likedNow = false
        } else {
            list.add(me)
            nextHearts = target.hearts + 1
            likedNow = true
        }

        val ts = System.currentTimeMillis()
        val updated = target.copy(
            author = target.author.ifBlank { partner },
            hearts = nextHearts,
            heartedBy = list,
            updatedAt = ts
        )

        val nextPierre = if (partner == "Pierre") updated else cur.pierre
        val nextEbba = if (partner == "Ebba") updated else cur.ebba
        val nextMot = if (cur.petitMot != null && cur.petitMot.author == partner && cur.petitMot.text == target.text) {
            updated
        } else {
            cur.petitMot
        }

        val nextState = WidgetNotesState(nextPierre, nextEbba, nextMot)
        saveNotesState(context, nextState)
        return Pair(nextState, Pair(updated, likedNow))
    }

    @Synchronized
    fun writeMyNote(context: Context, rawText: String): Pair<WidgetNotesState, NoteEntry> {
        val me = getCurrentUser(context)
        val cur = getNotesState(context)
        val ts = System.currentTimeMillis()
        val newNote = NoteEntry(
            text = rawText.trim().take(240),
            author = me,
            hearts = 0,
            heartedBy = emptyList(),
            updatedAt = ts
        )
        val nextState = WidgetNotesState(
            pierre = if (me == "Pierre") newNote else cur.pierre,
            ebba = if (me == "Ebba") newNote else cur.ebba,
            petitMot = newNote
        )
        saveNotesState(context, nextState)
        return Pair(nextState, newNote)
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
