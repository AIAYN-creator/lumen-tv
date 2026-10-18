package dev.lumen.tv.remote

import android.util.Log
import androidx.compose.ui.graphics.Color
import dev.lumen.tv.data.Team
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class MatchAlert(
    val kind: Kind,
    val tag: String,
    val title: String,
    val subtitle: String,
    val crestUrl: String?,
    val accent: Color,
) {
    enum class Kind { GOAL, RED_CARD }
}

/**
 * Watches the football teams' LaLiga games on ESPN and raises an alert for every goal (for or
 * against) and every red card. Idle it checks every 5 minutes whether a game is on; during a
 * game it reads the play-by-play every 20 seconds.
 */
class GoalWatcher(private val teams: List<Team>, private val onAlert: (MatchAlert) -> Unit) {

    private val seen = mutableMapOf<String, MutableSet<String>>()

    fun start(scope: CoroutineScope) = scope.launch(Dispatchers.IO) {
        while (isActive) {
            val live = teams.mapNotNull { team -> liveEvent(team)?.let { team to it } }
            live.forEach { (team, eventId) -> runCatching { check(team, eventId) }.onFailure { Log.w("Lumen", "Goal check failed", it) } }
            delay(if (live.isEmpty()) 5 * 60_000L else 20_000L)
        }
    }

    /** The ESPN event id if the team's current/next game is in progress. */
    private fun liveEvent(team: Team): String? {
        val event = get("https://site.api.espn.com/apis/site/v2/sports/${team.espnPath}/teams/${team.espnId}")
            ?.optJSONObject("team")?.optJSONArray("nextEvent")?.optJSONObject(0) ?: return null
        val state = event.optJSONArray("competitions")?.optJSONObject(0)?.optJSONObject("status")?.optJSONObject("type")?.optString("state")
        return event.optString("id").takeIf { state == "in" && it.isNotEmpty() }
    }

    private suspend fun check(team: Team, eventId: String) {
        val summary = get("https://site.api.espn.com/apis/site/v2/sports/${team.espnPath}/summary?event=$eventId") ?: return
        val competitors = summary.optJSONObject("header")?.optJSONArray("competitions")?.optJSONObject(0)?.optJSONArray("competitors")
        var home = ""; var away = ""; var homeScore = ""; var awayScore = ""
        for (i in 0 until (competitors?.length() ?: 0)) {
            val c = competitors!!.getJSONObject(i)
            val name = c.optJSONObject("team")?.optString("shortDisplayName").orEmpty().ifEmpty { c.optJSONObject("team")?.optString("displayName").orEmpty() }
            if (c.optString("homeAway") == "home") { home = name; homeScore = c.optString("score") } else { away = name; awayScore = c.optString("score") }
        }

        val events = summary.optJSONArray("keyEvents") ?: return
        val known = seen[eventId]
        val firstLook = known == null
        val ids = known ?: mutableSetOf<String>().also { seen[eventId] = it }

        for (i in 0 until events.length()) {
            val e = events.getJSONObject(i)
            val id = e.optString("id")
            if (!ids.add(id) || firstLook) continue // on the first look, don't replay what already happened
            val type = e.optJSONObject("type")?.optString("text").orEmpty()
            val clock = e.optJSONObject("clock")?.optString("displayValue").orEmpty()
            val teamId = e.optJSONObject("team")?.optString("id").orEmpty()
            val ours = teamId == team.espnId.toString()
            val player = e.optJSONArray("participants")?.optJSONObject(0)?.optJSONObject("athlete")?.optString("displayName").orEmpty()
            val crest = teamId.takeIf { it.isNotEmpty() }?.let { "https://a.espncdn.com/i/teamlogos/soccer/500/$it.png" }

            val alert = when {
                e.optBoolean("scoringPlay") -> MatchAlert(
                    kind = MatchAlert.Kind.GOAL,
                    tag = (if (ours) "Gol del ${team.name}" else "Gol en contra") + " · $clock",
                    title = "$home $homeScore – $awayScore $away",
                    subtitle = player + if (type.contains("Own", true)) " (en propia)" else if (type.contains("Penalty", true)) " (penalti)" else "",
                    crestUrl = crest,
                    accent = if (ours) Color(0xFF30D158) else Color(0xFFFF9F0A),
                )
                type.contains("Red", ignoreCase = true) -> MatchAlert(
                    kind = MatchAlert.Kind.RED_CARD,
                    tag = "Tarjeta roja · $clock",
                    title = player.ifEmpty { "Expulsión" },
                    subtitle = e.optJSONObject("team")?.optString("displayName").orEmpty() + " · $home $homeScore – $awayScore $away",
                    crestUrl = crest,
                    accent = Color(0xFFFF453A),
                )
                else -> null
            }
            if (alert != null) withContext(Dispatchers.Main) { onAlert(alert) }
        }
    }

    private fun get(url: String): JSONObject? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8_000; readTimeout = 10_000 }
        JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
    }.getOrNull()
}
