package dev.lumen.tv.data

import android.text.Html
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class MetroStation(val id: Int, val name: String)

/** One upcoming vehicle, for metro or bus cards. */
data class Arrival(val line: String, val color: Long, val destination: String, val wait: String)

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
