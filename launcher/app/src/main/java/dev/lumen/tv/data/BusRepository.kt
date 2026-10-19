package dev.lumen.tv.data

import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL

data class BusStop(val line: String, val stopId: Int, val name: String, val walk: Int = 0)

/** EMT Valencia real-time arrivals, from the endpoint behind the map on emtvalencia.es. */
object BusRepository {

    private const val EMT_RED = 0xFFE30613

    private val memo = Memo<BusStop, List<Arrival>>(20_000L)

    suspend fun fetch(stop: BusStop): List<Arrival>? = memo.get(stop) { download(stop) }

    private suspend fun download(stop: BusStop): List<Arrival>? = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(
                "https://www.emtvalencia.es/EMT/mapfunctions/MapUtilsPetitions.php" +
                    "?sec=getSAE&parada=${stop.stopId}&adaptados=false&idioma=es&nocache=${System.currentTimeMillis()}"
            )
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
            }
            connection.inputStream.use { stream ->
                val parser = Xml.newPullParser().apply { setInput(stream, "UTF-8") }
                val arrivals = mutableListOf<Arrival>()
                var fields: MutableMap<String, String>? = null
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    when (parser.eventType) {
                        XmlPullParser.START_TAG -> when {
                            parser.name == "bus" -> fields = mutableMapOf()
                            fields != null && parser.name in FIELDS -> fields[parser.name] = parser.nextText().trim()
                        }
                        XmlPullParser.END_TAG -> if (parser.name == "bus") {
                            fields?.let { bus ->
                                val line = bus["linea"].orEmpty()
                                if (line == stop.line) {
                                    arrivals += Arrival(line, EMT_RED, bus["destino"].orEmpty(), bus["minutos"].orEmpty().removeSuffix("."))
                                }
                            }
                            fields = null
                        }
                    }
                }
                arrivals
            }
        }.onFailure { Log.w("Lumen", "Bus fetch failed", it) }.getOrNull()
    }

    private val FIELDS = setOf("linea", "destino", "minutos")
}
