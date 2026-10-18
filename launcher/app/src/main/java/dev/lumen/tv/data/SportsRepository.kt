package dev.lumen.tv.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

data class Team(
    val name: String,
    val sportsDbId: Int,
    val espnPath: String? = null,
    val espnId: Int? = null,
)

enum class MatchState { UPCOMING, LIVE, FINISHED }

data class Match(
    val competition: String,
    val start: Instant,
    val home: String,
    val away: String,
    val homeBadge: String?,
    val awayBadge: String?,
    val homeScore: Int?,
    val awayScore: Int?,
    val state: MatchState,
    /** Live clock or period ("67'", "Q3 4:12") when known. */
    val clock: String? = null,
    /** TheSportsDB status code ("NS", "Q3", "HT", "FT"...). */
    val status: String? = null,
)

private val FINISHED_STATUSES = setOf("FT", "AOT", "AET", "PEN", "Match Finished", "Finished")
private val NOT_STARTED_STATUSES = setOf("NS", "Not Started", "TBD", "")

/** What a team card shows: the live or last match, plus the next fixture. */
data class TeamCard(val team: Team, val main: Match?, val next: Match?)

/**
 * Fixtures and results come from TheSportsDB (free key, covers all four teams with crests).
 * During a match, live scores come from ESPN where it covers the competition (LaLiga, Euroleague);
 * otherwise the card says "en juego" until the final result arrives.
 */
object SportsRepository {

    private const val SPORTS_DB = "https://www.thesportsdb.com/api/v1/json/123"
    private const val ESPN = "https://site.api.espn.com/apis/site/v2/sports"

    /** A match counts as possibly live from kick-off until this long after. */
    private val LIVE_WINDOW: Duration = Duration.ofMinutes(165)

    /** TheSportsDB is rate limited, so fixtures are cached between the frequent live refreshes. */
    private val FIXTURE_TTL: Duration = Duration.ofMinutes(10)
    private val fixtures = mutableMapOf<Int, Triple<Instant, Match?, Match?>>()

    /** Home and screensaver both poll the scoreboards; share one refresh between them. */
    private val memo = Memo<List<Team>, List<TeamCard>>(45_000L)

    suspend fun fetch(teams: List<Team>): List<TeamCard> = memo.get(teams) { download(teams) }.orEmpty()

    private suspend fun download(teams: List<Team>): List<TeamCard> = coroutineScope {
        teams.map { team -> async { cardOrLastGood(team) } }.awaitAll()
    }

    /** Last card that had something to show, per team. */
    private val lastGood = ConcurrentHashMap<Team, TeamCard>()

    /**
     * A Wi-Fi blip makes every source fail at once, which leaves an empty card. Keep showing the
     * last good one instead of blanking the results until the next refresh.
     */
    private suspend fun cardOrLastGood(team: Team): TeamCard {
        val card = card(team)
        if (card.main == null && card.next == null) return lastGood[team] ?: card
        lastGood[team] = card
        return card
    }

    /** True while any card is (possibly) live, so callers can refresh more often. */
    fun anyLive(cards: List<TeamCard>): Boolean = cards.any { it.main?.state == MatchState.LIVE }

    private suspend fun card(team: Team): TeamCard {
        // Football: ESPN's own LaLiga schedule is complete and current (TheSportsDB has been
        // seen skipping games, e.g. Levante's 1–1 at Deportivo). Basketball stays on TheSportsDB.
        // If ESPN is unreachable, the last ESPN card beats TheSportsDB's incomplete list.
        if (team.espnPath?.startsWith("soccer") == true) {
            espnCard(team)?.let { return it }
            lastGood[team]?.let { return it }
        }

        val now = Instant.now()
        val (rawLast, rawNext) = fixtures(team, now)

        // TheSportsDB can take hours to move a finished game from "next" to "last" (it stays in
        // "next" with status FT). So treat a "next" that is over as the latest result, and only
        // offer genuinely future games as the next fixture.
        val nextIsOver = rawNext != null &&
            (rawNext.status in FINISHED_STATUSES || now.isAfter(rawNext.start + LIVE_WINDOW))
        val last = listOfNotNull(rawLast, rawNext?.takeIf { nextIsOver }?.copy(state = MatchState.FINISHED))
            .maxByOrNull { it.start }
        val next = rawNext?.takeUnless { nextIsOver }

        if (next != null && now.isAfter(next.start.minusSeconds(300)) && now.isBefore(next.start + LIVE_WINDOW)) {
            val live = espnLive(team, next) ?: when {
                // TheSportsDB fills in scores and status during the game for some competitions.
                next.status in FINISHED_STATUSES -> next.copy(state = MatchState.FINISHED)
                else -> next.copy(state = MatchState.LIVE, clock = next.status?.takeIf { it !in NOT_STARTED_STATUSES })
            }
            return TeamCard(team, live, null)
        }
        return TeamCard(team, last, next)
    }

    // ---------------------------------------------------------------------------------------------
    // ESPN (football)

    /** The season schedule changes rarely; the live/next game is read fresh on every refresh. */
    private val espnSchedules = Memo<Int, JSONArray>(10 * 60_000L)

    private suspend fun espnCard(team: Team): TeamCard? {
        val path = team.espnPath ?: return null
        val id = team.espnId ?: return null
        // "schedule" lists played games; "?fixture=true" lists the upcoming ones.
        val schedule = espnSchedules.get(id) {
            val played = getJson("$ESPN/$path/teams/$id/schedule")?.optJSONArray("events") ?: return@get null
            val upcoming = getJson("$ESPN/$path/teams/$id/schedule?fixture=true")?.optJSONArray("events")
            JSONArray().apply {
                for (i in 0 until played.length()) put(played.get(i))
                if (upcoming != null) for (i in 0 until upcoming.length()) put(upcoming.get(i))
            }
        }
        val nextEvent = getJson("$ESPN/$path/teams/$id")
            ?.optJSONObject("team")?.optJSONArray("nextEvent")?.optJSONObject(0)

        val matches = buildList {
            schedule?.let { s -> for (i in 0 until s.length()) espnMatch(path, s.getJSONObject(i))?.let(::add) }
            nextEvent?.let { espnMatch(path, it) }?.let(::add)
        }
            // The live/next game appears in both lists; keep the freshest copy (added last).
            .reversed().distinctBy { it.start }
        if (matches.isEmpty()) return null

        matches.firstOrNull { it.state == MatchState.LIVE }?.let { return TeamCard(team, it, null) }
        val now = Instant.now()
        val last = matches.filter { it.state == MatchState.FINISHED }.maxByOrNull { it.start }
        val next = matches.filter { it.state == MatchState.UPCOMING && it.start.isAfter(now) }.minByOrNull { it.start }
        return TeamCard(team, last, next)
    }

    private fun espnMatch(path: String, event: JSONObject): Match? {
        val start = parseEspnDate(event.optString("date")) ?: return null
        val competition = event.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val status = competition.optJSONObject("status")
        val state = when (status?.optJSONObject("type")?.optString("state")) {
            "in" -> MatchState.LIVE
            "post" -> MatchState.FINISHED
            else -> MatchState.UPCOMING
        }
        var home: JSONObject? = null
        var away: JSONObject? = null
        val competitors = competition.optJSONArray("competitors") ?: return null
        for (i in 0 until competitors.length()) {
            val c = competitors.getJSONObject(i)
            if (c.optString("homeAway") == "home") home = c else away = c
        }
        fun name(c: JSONObject?) = c?.optJSONObject("team")?.let { it.optString("shortDisplayName").ifEmpty { it.optString("displayName") } }.orEmpty()
        fun crest(c: JSONObject?) = c?.optJSONObject("team")?.optString("id")?.takeIf { it.isNotEmpty() }
            ?.let { "https://a.espncdn.com/i/teamlogos/soccer/500/$it.png" }
        fun score(c: JSONObject?): Int? {
            val raw = c?.opt("score") ?: return null
            return (if (raw is JSONObject) raw.optString("displayValue") else raw.toString()).toIntOrNull()
        }
        val played = state != MatchState.UPCOMING
        return Match(
            competition = if (path == "soccer/esp.1") "LaLiga" else "Fútbol",
            start = start,
            home = name(home),
            away = name(away),
            homeBadge = crest(home),
            awayBadge = crest(away),
            homeScore = if (played) score(home) else null,
            awayScore = if (played) score(away) else null,
            state = state,
            clock = if (state == MatchState.LIVE) status?.optString("displayClock") else null,
        )
    }

    /** ESPN dates sometimes omit seconds ("2026-10-16T19:00Z"). */
    private fun parseEspnDate(raw: String): Instant? =
        runCatching { Instant.parse(if (raw.length == 17) raw.replace("Z", ":00Z") else raw) }.getOrNull()

    // ---------------------------------------------------------------------------------------------
    // TheSportsDB (basketball, and fallback)

    private suspend fun fixtures(team: Team, now: Instant): Pair<Match?, Match?> {
        fixtures[team.sportsDbId]?.let { (fetched, last, next) ->
            // Refetch early once a cached "next" match should be over, to pick up its result —
            // once, not on every poll (the API may keep listing it as "next" for hours).
            val stale = next != null && now.isAfter(next.start + LIVE_WINDOW) && fetched.isBefore(next.start + LIVE_WINDOW)
            // During a game TheSportsDB may carry the live score, so poll it more often.
            val inPlay = next != null && now.isAfter(next.start) && !stale
            val ttl = if (inPlay) Duration.ofMinutes(2) else FIXTURE_TTL
            if (!stale && Duration.between(fetched, now) < ttl) return last to next
        }
        val last = getJson("$SPORTS_DB/eventslast.php?id=${team.sportsDbId}")
            ?.optJSONArray("results")?.firstMatch(MatchState.FINISHED)
        val next = getJson("$SPORTS_DB/eventsnext.php?id=${team.sportsDbId}")
            ?.optJSONArray("events")?.firstMatch(MatchState.UPCOMING)
        val cached = fixtures[team.sportsDbId]
        // Keep the previous values if a request failed (e.g. rate limit).
        val result = (last ?: cached?.second) to (next ?: cached?.third)
        fixtures[team.sportsDbId] = Triple(now, result.first, result.second)
        return result
    }

    private fun JSONArray.firstMatch(state: MatchState): Match? {
        if (length() == 0) return null
        val e = getJSONObject(0)
        val start = runCatching {
            LocalDateTime.parse("${e.getString("dateEvent")}T${e.optString("strTime", "00:00:00").ifEmpty { "00:00:00" }}")
                .toInstant(ZoneOffset.UTC)
        }.getOrNull() ?: return null
        return Match(
            competition = e.optString("strLeague").prettyCompetition(),
            start = start,
            home = e.optString("strHomeTeam"),
            away = e.optString("strAwayTeam"),
            homeBadge = e.optString("strHomeTeamBadge").takeIf { it.startsWith("http") }?.let { "$it/small" },
            awayBadge = e.optString("strAwayTeamBadge").takeIf { it.startsWith("http") }?.let { "$it/small" },
            homeScore = e.optString("intHomeScore").toIntOrNull(),
            awayScore = e.optString("intAwayScore").toIntOrNull(),
            state = state,
            status = e.optString("strStatus").takeIf { it.isNotBlank() && it != "null" },
        )
    }

    /** Live (or just-finished) score from ESPN for the fixture [fixture], if ESPN covers it. */
    private suspend fun espnLive(team: Team, fixture: Match): Match? {
        val path = team.espnPath ?: return null
        val event = getJson("$ESPN/$path/teams/${team.espnId}")
            ?.optJSONObject("team")?.optJSONArray("nextEvent")?.optJSONObject(0) ?: return null
        val start = runCatching { Instant.parse(event.getString("date").let { if (it.length == 17) it.replace("Z", ":00Z") else it }) }
            .getOrNull() ?: return null
        // Make sure ESPN's game is the same one (e.g. not a different competition).
        if (Duration.between(start, fixture.start).abs() > Duration.ofHours(3)) return null

        val competition = event.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val status = competition.optJSONObject("status")?.optJSONObject("type")
        val state = when (status?.optString("state")) {
            "in" -> MatchState.LIVE
            "post" -> MatchState.FINISHED
            else -> return null
        }
        val competitors = competition.optJSONArray("competitors") ?: return null
        var homeScore: Int? = null
        var awayScore: Int? = null
        for (i in 0 until competitors.length()) {
            val c = competitors.getJSONObject(i)
            // ESPN gives the score either as a plain value or as {"displayValue": ...}.
            val raw = c.opt("score")
            val score = (if (raw is JSONObject) raw.optString("displayValue") else raw?.toString())?.toIntOrNull()
            if (c.optString("homeAway") == "home") homeScore = score else awayScore = score
        }
        return fixture.copy(
            homeScore = homeScore,
            awayScore = awayScore,
            state = state,
            clock = if (state == MatchState.LIVE) competition.optJSONObject("status")?.optString("displayClock")
                ?.let { clock -> espnPeriod(path, competition, clock) } else null,
        )
    }

    private fun espnPeriod(path: String, competition: JSONObject, clock: String): String {
        val period = competition.optJSONObject("status")?.optInt("period") ?: 0
        return if (path.startsWith("basketball")) "Q$period $clock" else clock
    }

    private fun String.prettyCompetition(): String = when {
        contains("La Liga", true) -> "LaLiga"
        contains("Segunda", true) -> "LaLiga Hypermotion"
        contains("Copa del Rey", true) -> "Copa del Rey"
        contains("EuroLeague Women", true) -> "EuroLeague Women"
        contains("EuroLeague", true) -> "Euroliga"
        contains("EuroCup", true) -> "Eurocup"
        contains("ACB", true) -> "Liga Endesa"
        contains("Femenina", true) -> "Liga Femenina"
        else -> this
    }

    private suspend fun getJson(url: String): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 10_000
            }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        }.onFailure { Log.w("Lumen", "Sports fetch failed: $url", it) }.getOrNull()
    }
}
