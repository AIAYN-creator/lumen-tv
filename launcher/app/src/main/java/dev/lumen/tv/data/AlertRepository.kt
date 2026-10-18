package dev.lumen.tv.data

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime

enum class AlertLevel(val label: String, val color: Long) {
    YELLOW("amarillo", 0xFFF5C518),
    ORANGE("naranja", 0xFFFF8A1F),
    RED("rojo", 0xFFE53935),
}

data class WeatherAlert(
    val level: AlertLevel,
    val event: String,
    val onset: OffsetDateTime,
    val expires: OffsetDateTime,
)

/** AEMET warnings, read from the MeteoAlarm Spain feed (public, no API key). */
object AlertRepository {

    private const val FEED = "https://feeds.meteoalarm.org/feeds/meteoalarm-legacy-atom-spain"

    /** Warnings for [area] that are active now or start within the next 12 hours, most severe first. */
    /**
     * The feed is ~750 KB and the server supports neither compression nor conditional requests,
     * so it's fetched at most every 30 minutes and shared by every screen.
     */
    private val memo = Memo<String, List<WeatherAlert>>(30 * 60_000L)

    suspend fun fetch(area: String): List<WeatherAlert>? = memo.get(area) { download(area) }?.let { all ->
        val now = OffsetDateTime.now()
        all.filter { it.expires.isAfter(now) && it.onset.isBefore(now.plusHours(12)) }
            .sortedWith(compareByDescending<WeatherAlert> { it.level }.thenBy { it.onset })
    }

    /** Every warning for [area] in the feed, unfiltered by time (filtering happens on each read). */
    private suspend fun download(area: String): List<WeatherAlert>? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(FEED).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
            }
            val alerts = connection.inputStream.use { stream ->
                val parser = Xml.newPullParser().apply { setInput(stream, "UTF-8") }
                val found = mutableListOf<WeatherAlert>()
                var fields: MutableMap<String, String>? = null
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    val name = parser.name?.substringAfter(':')
                    when (parser.eventType) {
                        XmlPullParser.START_TAG -> when {
                            name == "entry" -> fields = mutableMapOf()
                            fields != null && name in FIELDS -> fields[name!!] = parser.nextText()
                        }
                        XmlPullParser.END_TAG -> if (name == "entry") {
                            fields?.toAlert(area)?.let(found::add)
                            fields = null
                        }
                    }
                }
                found
            }
            alerts
        }.getOrNull()
    }

    private val FIELDS = setOf("title", "areaDesc", "onset", "expires", "severity")

    private fun Map<String, String>.toAlert(area: String): WeatherAlert? {
        if (this["areaDesc"] != area) return null
        val level = when (this["severity"]) {
            "Moderate" -> AlertLevel.YELLOW
            "Severe" -> AlertLevel.ORANGE
            "Extreme" -> AlertLevel.RED
            else -> return null
        }
        // Titles look like "Orange Rain Warning issued for Spain - Litoral norte de Valencia".
        val event = Regex("""^\w+ (.+?) Warning""").find(this["title"].orEmpty())?.groupValues?.get(1).orEmpty()
        return WeatherAlert(
            level = level,
            event = translate(event),
            onset = OffsetDateTime.parse(this["onset"] ?: return null),
            expires = OffsetDateTime.parse(this["expires"] ?: return null),
        )
    }

    private fun translate(event: String): String = when (event.lowercase()) {
        "rain" -> "lluvias"
        "thunderstorm" -> "tormentas"
        "wind" -> "viento"
        "coastalevent" -> "fenómenos costeros"
        "snow-ice", "snow" -> "nieve"
        "high-temperature" -> "calor"
        "low-temperature" -> "frío"
        "fog" -> "niebla"
        "forest-fire" -> "riesgo de incendio"
        "rain-flood", "flooding" -> "inundaciones"
        "avalanches" -> "aludes"
        else -> event.lowercase()
    }
}
