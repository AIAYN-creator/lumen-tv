package dev.lumen.tv.geo

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GeoGuesser València: the TV shows a street photo, everyone guesses on their phone's map.
 * Five rounds, two minutes each (or less once everyone has answered), points by distance.
 *
 * The state lives here, in the launcher's process; the TV page and the phones poll it through
 * the phone remote's web server (see RemoteService).
 */
object GeoGame {

    enum class Phase { LOBBY, LOADING, ROUND, REVEAL, FINAL }

    data class Place(val imageUrl: String, val lat: Double, val lon: Double)
    data class Guess(val lat: Double, val lon: Double, val meters: Int, val points: Int)
    class Player(val id: String, val name: String, val colour: String) {
        var score = 0
        val guesses = mutableMapOf<Int, Guess>()
    }

    const val ROUNDS = 5
    private const val ROUND_MILLIS = 120_000L

    /** Valencia city: old town, Eixample, Ruzafa, Benimaclet, Cabanyal, Patraix... */
    private const val MIN_LAT = 39.445
    private const val MAX_LAT = 39.495
    private const val MIN_LON = -0.405
    private const val MAX_LON = -0.330

    private const val MIN_QUALITY = 0.6
    private const val SINCE_2015 = 1_420_070_400_000L

    private val COLOURS = listOf("#FF9F0A", "#30D158", "#64D2FF", "#FF375F", "#BF5AF2", "#FFD60A", "#5E5CE6", "#AC8E68")

    private var phase = Phase.LOBBY
    private var round = 0
    private var deadline = 0L
    private var places = emptyList<Place>()
    private var error: String? = null
    private val players = linkedMapOf<String, Player>()

    @Synchronized
    fun join(name: String): String {
        players.values.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it.id }
        // First colour nobody in the game is using (counting by size repeated colours after someone left).
        val taken = players.values.map { it.colour }.toSet()
        val colour = COLOURS.firstOrNull { it !in taken } ?: COLOURS[players.size % COLOURS.size]
        val player = Player(UUID.randomUUID().toString(), name, colour)
        players[player.id] = player
        return player.id
    }

    @Synchronized
    fun guess(playerId: String, lat: Double, lon: Double) {
        if (phase != Phase.ROUND) return
        val player = players[playerId] ?: return
        if (player.guesses.containsKey(round)) return
        val target = places[round - 1]
        val meters = distance(lat, lon, target.lat, target.lon).roundToInt()
        val points = (5000 * exp(-meters / 1500.0)).roundToInt()
        player.guesses[round] = Guess(lat, lon, meters, points)
        player.score += points
        closeRoundIfDone()
    }

    /**
     * Admin (the phone that entered the code in lumen_admin.txt) removes a player by name,
     * e.g. one left behind by a phone that has gone.
     */
    @Synchronized
    fun kick(name: String) {
        players.values.firstOrNull { it.name == name }?.let { players.remove(it.id) }
        closeRoundIfDone()
    }

    /** True if [code] matches the admin code file (ignoring case, spaces and dashes). */
    fun isAdmin(context: Context, code: String?): Boolean {
        val expected = context.getExternalFilesDir(null)?.let { File(it, "lumen_admin.txt") }
            ?.takeIf { it.exists() }?.readText()?.normalizedCode() ?: return false
        return expected.isNotEmpty() && code?.normalizedCode() == expected
    }

    private fun String.normalizedCode() = uppercase().filter { it.isLetterOrDigit() }

    /** A phone leaves the game ("Salir de la partida"). */
    @Synchronized
    fun leave(playerId: String) {
        players.remove(playerId)
        closeRoundIfDone()
    }

    /**
     * Back on the TV: during a game, return to the lobby (same players, scores cleared) so
     * people can join or leave; in the lobby, return false so the TV app closes.
     */
    @Synchronized
    fun back(): Boolean {
        if (phase == Phase.LOBBY) return false
        toLobby()
        return true
    }

    private fun toLobby() {
        phase = Phase.LOBBY
        round = 0
        places = emptyList()
        players.values.forEach { it.score = 0; it.guesses.clear() }
    }

    /** OK on the TV: start the game, go to the next round, or (after the final) back to the lobby. */
    fun advance(context: Context) {
        val start = synchronized(this) {
            when (phase) {
                Phase.LOBBY -> if (players.isEmpty()) false else { phase = Phase.LOADING; error = null; true }
                Phase.FINAL -> { toLobby(); false }
                Phase.REVEAL -> { if (round >= ROUNDS) phase = Phase.FINAL else startRound(round + 1); false }
                Phase.ROUND -> { reveal(); false } // skip the wait
                Phase.LOADING -> false
            }
        }
        if (start) {
            // Fetching five places takes a few seconds: do it outside the lock.
            val found = runCatching { pickPlaces(context) }.onFailure { Log.w("Lumen", "GeoGuesser: no places", it) }.getOrDefault(emptyList())
            synchronized(this) {
                if (phase != Phase.LOADING) return // Back was pressed while loading
                if (found.size < ROUNDS) {
                    phase = Phase.LOBBY
                    error = if (token(context) == null) "Falta el token de Mapillary" else "No he podido cargar las fotos. Prueba otra vez."
                } else {
                    places = found
                    players.values.forEach { it.score = 0; it.guesses.clear() }
                    startRound(1)
                }
            }
        }
    }

    @Synchronized
    fun reset() {
        phase = Phase.LOBBY; round = 0; places = emptyList(); players.clear(); error = null
    }

    @Synchronized
    fun state(playerId: String?): JSONObject {
        closeRoundIfDone()
        val now = System.currentTimeMillis()
        val json = JSONObject()
            .put("phase", phase.name)
            .put("round", round)
            .put("rounds", ROUNDS)
            .put("now", now)
            .put("deadline", deadline)
            .put("error", error ?: JSONObject.NULL)
            .put("players", JSONArray().apply {
                players.values.sortedByDescending { it.score }.forEach { p ->
                    put(JSONObject()
                        .put("name", p.name).put("colour", p.colour).put("score", p.score)
                        .put("answered", p.guesses.containsKey(round))
                        .put("me", p.id == playerId))
                }
            })
        if (phase == Phase.ROUND || phase == Phase.REVEAL) json.put("image", places[round - 1].imageUrl)
        if (phase == Phase.REVEAL || phase == Phase.FINAL) {
            val target = places[round - 1]
            json.put("target", JSONObject().put("lat", target.lat).put("lon", target.lon))
            json.put("guesses", JSONArray().apply {
                players.values.forEach { p ->
                    p.guesses[round]?.let { g ->
                        put(JSONObject().put("name", p.name).put("colour", p.colour)
                            .put("lat", g.lat).put("lon", g.lon).put("meters", g.meters).put("points", g.points))
                    }
                }
            })
        }
        playerId?.let { players[it] }?.let { me -> json.put("me", JSONObject().put("name", me.name).put("answered", me.guesses.containsKey(round))) }
        return json
    }

    private fun startRound(n: Int) {
        round = n
        phase = Phase.ROUND
        deadline = System.currentTimeMillis() + ROUND_MILLIS
    }

    private fun closeRoundIfDone() {
        if (phase != Phase.ROUND) return
        val allAnswered = players.isNotEmpty() && players.values.all { it.guesses.containsKey(round) }
        if (allAnswered || System.currentTimeMillis() > deadline) reveal()
    }

    private fun reveal() {
        if (phase == Phase.ROUND) phase = Phase.REVEAL
    }

    // ---------------------------------------------------------------------------------------------
    // Places from Mapillary

    /** The token is a file the user drops in the project folder (deploy.ps1 copies it); never in the code. */
    private fun token(context: Context): String? =
        context.getExternalFilesDir(null)?.let { File(it, "mapillary_token.txt") }
            ?.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    private fun pickPlaces(context: Context): List<Place> {
        val token = token(context) ?: return emptyList()
        val picked = mutableListOf<Place>()
        var attempts = 0
        while (picked.size < ROUNDS && attempts < 60) {
            attempts++
            val lat = MIN_LAT + Math.random() * (MAX_LAT - MIN_LAT)
            val lon = MIN_LON + Math.random() * (MAX_LON - MIN_LON)
            val bbox = "${lon - 0.003},${lat - 0.002},${lon + 0.003},${lat + 0.002}"
            val url = "https://graph.mapillary.com/images?access_token=${java.net.URLEncoder.encode(token, "UTF-8")}" +
                "&fields=id,thumb_2048_url,computed_geometry,is_pano,camera_type,width,quality_score,captured_at&bbox=$bbox&limit=40"
            val data = runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8_000; readTimeout = 12_000 }
                JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getJSONArray("data")
            }.onFailure { Log.w("Lumen", "Mapillary query failed", it) }.getOrNull() ?: continue

            // Only good, ordinary street photos: no 360° shots, decent resolution, not ancient,
            // and Mapillary's own quality score above average. Then one of the best few.
            val candidates = (0 until data.length()).map { data.getJSONObject(it) }
                .filter {
                    !it.optBoolean("is_pano") && it.has("thumb_2048_url") && it.has("computed_geometry") &&
                        it.optString("camera_type", "perspective") == "perspective" &&
                        it.optInt("width") >= 2000 &&
                        it.optDouble("quality_score", 0.0) >= MIN_QUALITY &&
                        it.optLong("captured_at") >= SINCE_2015
                }
                .sortedByDescending { it.optDouble("quality_score") }
            val choice = candidates.take(3).randomOrNull() ?: continue
            val coords = choice.getJSONObject("computed_geometry").getJSONArray("coordinates")
            val place = Place(choice.getString("thumb_2048_url"), coords.getDouble(1), coords.getDouble(0))
            // Keep rounds at least 1 km apart, so the game tours the city.
            if (picked.all { distance(it.lat, it.lon, place.lat, place.lon) > 1_000 }) picked += place
        }
        return picked
    }

    private fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}
