package dev.lumen.tv.state

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A note left from someone's phone, shown on the TV until it expires or is deleted. */
data class Note(val id: String, val text: String, val author: String, val createdAt: Long, val expiresAt: Long) {
    val expired: Boolean get() = System.currentTimeMillis() > expiresAt

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("text", text).put("author", author).put("createdAt", createdAt).put("expiresAt", expiresAt)

    companion object {
        fun fromJson(o: JSONObject) = Note(
            o.optString("id").ifEmpty { UUID.randomUUID().toString() },
            o.getString("text"),
            o.optString("author"),
            o.optLong("createdAt", System.currentTimeMillis()),
            o.getLong("expiresAt"),
        )
    }
}

/**
 * State shared by the TV screens and the phone remote, which all live in the same process.
 * Notes are persisted so they survive restarts; the sleep timer deliberately isn't.
 */
object HouseState {

    private const val PREFS = "house"
    private const val MAX_NOTES = 6

    /** Live notes, newest first. */
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    /** Wall-clock time the TV will be put to sleep, or null when no timer is set. */
    private val _sleepAt = MutableStateFlow<Long?>(null)
    val sleepAt: StateFlow<Long?> = _sleepAt.asStateFlow()

    private var loaded = false

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = prefs.getString("notes", null)?.let { json ->
            runCatching { JSONArray(json).let { a -> (0 until a.length()).map { Note.fromJson(a.getJSONObject(it)) } } }.getOrNull()
        } ?: prefs.getString("note", null)?.let { old -> runCatching { listOf(Note.fromJson(JSONObject(old))) }.getOrNull() }
        _notes.value = list.orEmpty().filterNot { it.expired }.sortedByDescending { it.createdAt }
    }

    @Synchronized
    fun addNote(context: Context, text: String, author: String, hours: Double) {
        val now = System.currentTimeMillis()
        val note = Note(UUID.randomUUID().toString(), text, author, now, now + (hours * 3_600_000).toLong())
        save(context, (listOf(note) + _notes.value).filterNot { it.expired }.take(MAX_NOTES))
    }

    @Synchronized
    fun removeNote(context: Context, id: String) = save(context, _notes.value.filterNot { it.id == id })

    @Synchronized
    fun clearNotes(context: Context) = save(context, emptyList())

    /** Live notes, pruning any that have expired. */
    @Synchronized
    fun currentNotes(context: Context): List<Note> {
        val live = _notes.value.filterNot { it.expired }
        if (live.size != _notes.value.size) save(context, live)
        return live
    }

    private fun save(context: Context, notes: List<Note>) {
        _notes.value = notes
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("notes", JSONArray().apply { notes.forEach { put(it.toJson()) } }.toString())
            .remove("note")
            .apply()
    }

    fun setSleepTimer(minutes: Int?) {
        _sleepAt.value = minutes?.let { System.currentTimeMillis() + it * 60_000L }
    }
}
