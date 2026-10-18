package dev.lumen.tv.state

import android.content.Context
import dev.lumen.tv.Config
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/** Night mode switched by hand ([on] or off) until [until], when the schedule takes over again. */
data class NightOverride(val on: Boolean, val until: Long)

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
 * Notes and the night mode switch are persisted so they survive restarts; the sleep timer
 * deliberately isn't.
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

    /** Night mode set by hand from the home screen or the phone, or null to follow the schedule. */
    private val _night = MutableStateFlow<NightOverride?>(null)
    val night: StateFlow<NightOverride?> = _night.asStateFlow()

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
        val until = prefs.getLong("nightUntil", 0L)
        if (until > System.currentTimeMillis()) _night.value = NightOverride(prefs.getBoolean("nightOn", false), until)
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

    /** Whether the screensaver shows night mode at [now]: the hand switch while it lasts, else the schedule. */
    fun isNight(now: LocalDateTime, override: NightOverride? = _night.value): Boolean =
        if (override != null && now.millis() < override.until) override.on else scheduledNight(now)

    /**
     * Flips night mode by hand and returns the new state. The switch holds until the schedule
     * next changes (turned on at 23:00, it lasts the night; turned off at 02:00, until 07:00).
     */
    @Synchronized
    fun toggleNight(context: Context): Boolean {
        val now = LocalDateTime.now()
        val on = !isNight(now)
        // Back in line with the schedule: nothing to override.
        val override = if (on == scheduledNight(now)) null else NightOverride(on, nextNightSwitch(now).millis())
        _night.value = override
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("nightOn", override?.on ?: false)
            .putLong("nightUntil", override?.until ?: 0L)
            .apply()
        return on
    }

    private fun scheduledNight(t: LocalDateTime): Boolean =
        if (Config.NIGHT_START <= Config.NIGHT_END) t.hour in Config.NIGHT_START until Config.NIGHT_END
        else t.hour >= Config.NIGHT_START || t.hour < Config.NIGHT_END

    /** The next time night mode starts or ends on the schedule. */
    private fun nextNightSwitch(now: LocalDateTime): LocalDateTime {
        val today = now.toLocalDate()
        return listOf(today, today.plusDays(1))
            .flatMap { day -> listOf(Config.NIGHT_START, Config.NIGHT_END).map { day.atTime(it, 0) } }
            .filter { it.isAfter(now) }
            .min()
    }

    private fun LocalDateTime.millis(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
