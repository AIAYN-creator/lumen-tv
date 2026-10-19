package dev.lumen.tv.data

import android.text.Html
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalTime
import kotlin.math.roundToInt

/** A station, plus the minutes it takes to get from the front door to its platform. */
data class MetroStation(val id: Int, val name: String, val walk: Int = 0)

/** One upcoming vehicle, for metro or bus cards. [estimated] ones are worked out, not announced. */
data class Arrival(
    val line: String,
    val color: Long,
    val destination: String,
    val wait: String,
    val estimated: Boolean = false,
) {
    /** The wait in whole minutes; EMT's "Próximo" is zero, and anything unparseable is null. */
    val minutes: Int?
        get() = when {
            wait.startsWith("Pr", ignoreCase = true) || wait.startsWith("Llegando", ignoreCase = true) -> 0
            else -> Regex("""^(\d+)""").find(wait)?.groupValues?.get(1)?.toIntOrNull()
        }
}

/**
 * The arrivals that are still worth knowing about, where [walk] is the minutes from the front door
 * to the platform. A train leaving in two minutes from a station fifteen minutes away is not
 * information, it is a train you are going to miss, so it goes. A walk of 0 filters nothing.
 */
fun List<Arrival>.reachable(walk: Int): List<Arrival> =
    if (walk <= 0) this else filter { (it.minutes ?: Int.MAX_VALUE) >= walk }

/** How far past the walk it is worth guessing. Three trains is plenty; a whole evening is not. */
private const val PROJECTION_HORIZON = 20

/** Gaps outside this are the service winding down or starting up, and no longer a rhythm. */
private val SANE_GAP = 2.0..15.0

/**
 * Estimates are only made between these hours. Near the end of service a steady gap stops meaning
 * there is another train: the last two can be ten minutes apart and then there is simply no third
 * one, which is the one ghost the gap check cannot catch. The window closes before that happens
 * and does not reopen after midnight, when the metro is still running on a Friday but winding down.
 */
private val ESTIMATING_HOURS = 6 until 23

/**
 * The trains the panel cannot see yet, carried forward from the ones it can.
 *
 * The panel only knows trains already in service, around twenty minutes ahead. For a station you
 * reach in nineteen, that horizon ends more or less where your walk does, so it can sit there with
 * nothing to offer while a train passes every three minutes. Each destination keeps a fairly even
 * gap, so this measures the gap it has been keeping and carries it forward, which is the sum you
 * would do in your head anyway. They are estimates, there can be delays, and they say so with a ~.
 *
 * Nothing is invented out of thin air: a destination needs at least two announced trains before its
 * gap means anything, the gap has to look like a working frequency, and the guesses stop
 * [PROJECTION_HORIZON] minutes past the walk. When the last trains of the night are thinning out,
 * all of that stops matching and the card goes back to saying it cannot see that far.
 */
fun List<Arrival>.projected(walk: Int): List<Arrival> {
    if (walk <= 0 || LocalTime.now().hour !in ESTIMATING_HOURS) return emptyList()
    return groupBy { it.destination }
        .flatMap { (_, group) ->
            val times = group.mapNotNull { it.minutes }.sorted()
            if (times.size < 2) return@flatMap emptyList()
            val gap = (times.last() - times.first()).toDouble() / (times.size - 1)
            if (gap !in SANE_GAP) return@flatMap emptyList()
            // Always strictly later than the last announced train of this destination, so a guess
            // can never collide with, or repeat, a real one.
            generateSequence(times.last() + gap) { it + gap }
                .takeWhile { it <= walk + PROJECTION_HORIZON }
                .filter { it >= walk }
                .map { group.last().copy(wait = "${it.roundToInt()} min", estimated = true) }
                .toList()
        }
        .sortedBy { it.minutes ?: Int.MAX_VALUE }
}

/**
 * Next trains at a Metrovalencia station. There is no public API, so this calls the same
 * endpoint the metrovalencia.es "next trains" panel uses and reads the HTML fragment it returns.
 */
object MetroRepository {

    private const val ENDPOINT = "https://www.metrovalencia.es/wp-admin/admin-ajax.php"

    private val lineRegex = Regex("""linea-(\d+)""")
    private val destinationRegex = Regex("""<div class="nombre-estacion">\s*([^<]+?)\s*</div>""")
    private val waitRegex = Regex("""class="minutos[^"]*">\s*([^<]+?)\s*<""")

    /** The home screen and the screensaver show the same stations: one request serves both. */
    private val memo = Memo<Int, List<Arrival>>(20_000L)

    suspend fun fetch(stationId: Int): List<Arrival>? = memo.get(stationId) { download(stationId) }

    private suspend fun download(stationId: Int): List<Arrival>? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            }
            val body = "action=formularios_ajax&data=" +
                URLEncoder.encode("action=info-estacion&id=$stationId", "UTF-8")
            connection.outputStream.use { it.write(body.toByteArray()) }
            val html = connection.inputStream.bufferedReader().use { JSONObject(it.readText()).getString("html") }

            html.split("item--proximos").drop(1).mapNotNull { item ->
                val line = lineRegex.find(item)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                val destination = destinationRegex.find(item)?.groupValues?.get(1) ?: return@mapNotNull null
                val wait = waitRegex.find(item)?.groupValues?.get(1) ?: return@mapNotNull null
                Arrival("$line", lineColor(line), Html.fromHtml(destination, Html.FROM_HTML_MODE_LEGACY).toString(), wait)
            }
        }.onFailure { Log.w("Lumen", "Metro fetch failed", it) }.getOrNull()
    }

    /** Official line colours, roughly. */
    private fun lineColor(line: Int): Long = when (line) {
        1 -> 0xFFE5B400
        2 -> 0xFFD6006F
        3 -> 0xFFE2001A
        4 -> 0xFF009B48
        5 -> 0xFF00704A
        6 -> 0xFF7D3F98
        7 -> 0xFFF08A00
        8 -> 0xFF00A9E0
        9 -> 0xFF9C6B3F
        10 -> 0xFF8CC63F
        else -> 0xFF888888
    }
}
