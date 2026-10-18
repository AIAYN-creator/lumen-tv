package dev.lumen.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import kotlin.math.roundToInt

/** Simplified sky condition, from WMO weather codes. Drives the pictograms. */
enum class Sky { CLEAR, PARTLY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, STORM }

fun skyOf(code: Int): Sky = when (code) {
    0 -> Sky.CLEAR
    1, 2 -> Sky.PARTLY
    3 -> Sky.CLOUDY
    45, 48 -> Sky.FOG
    in 51..57 -> Sky.DRIZZLE
    in 61..67, in 80..82 -> Sky.RAIN
    in 71..77, 85, 86 -> Sky.SNOW
    in 95..99 -> Sky.STORM
    else -> Sky.CLOUDY
}

data class HourForecast(
    val time: LocalDateTime,
    val temperature: Int,
    val sky: Sky,
    val isDay: Boolean,
    val precipitationChance: Int,
)

data class Weather(
    val temperature: Int,
    val sky: Sky,
    val isDay: Boolean,
    val max: Int,
    val min: Int,
    val windKmh: Int,
    val precipitationChance: Int,
    val sunrise: LocalDateTime,
    val sunset: LocalDateTime,
    val hours: List<HourForecast>,
) {
    val description: String get() = when (sky) {
        Sky.CLEAR -> "Despejado"
        Sky.PARTLY -> "Poco nuboso"
        Sky.CLOUDY -> "Nublado"
        Sky.FOG -> "Niebla"
        Sky.DRIZZLE -> "Llovizna"
        Sky.RAIN -> "Lluvia"
        Sky.SNOW -> "Nieve"
        Sky.STORM -> "Tormenta"
    }
}

/** Open-Meteo: free, no API key. Times come back in the city's local time. */
object WeatherRepository {

    /** Shared by every screen that shows the weather (see [Memo]). */
    private val memo = Memo<Pair<Double, Double>, Weather>(10 * 60_000L)

    suspend fun fetch(latitude: Double, longitude: Double): Weather? = memo.get(latitude to longitude) { download(latitude, longitude) }

    private suspend fun download(latitude: Double, longitude: Double): Weather? = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
                    "&current=temperature_2m,weather_code,is_day,wind_speed_10m" +
                    "&hourly=temperature_2m,weather_code,is_day,precipitation_probability&forecast_hours=8" +
                    "&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset,precipitation_probability_max" +
                    "&forecast_days=1&timezone=auto"
            )
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val current = json.getJSONObject("current")
            val daily = json.getJSONObject("daily")
            val hourly = json.getJSONObject("hourly")

            val times = hourly.getJSONArray("time")
            val hours = (0 until times.length()).map { i ->
                HourForecast(
                    time = LocalDateTime.parse(times.getString(i)),
                    temperature = hourly.getJSONArray("temperature_2m").getDouble(i).roundToInt(),
                    sky = skyOf(hourly.getJSONArray("weather_code").getInt(i)),
                    isDay = hourly.getJSONArray("is_day").getInt(i) == 1,
                    precipitationChance = hourly.getJSONArray("precipitation_probability").optInt(i, 0),
                )
            }

            Weather(
                temperature = current.getDouble("temperature_2m").roundToInt(),
                sky = skyOf(current.getInt("weather_code")),
                isDay = current.getInt("is_day") == 1,
                max = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
                min = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
                windKmh = current.getDouble("wind_speed_10m").roundToInt(),
                precipitationChance = daily.getJSONArray("precipitation_probability_max").optInt(0, 0),
                sunrise = LocalDateTime.parse(daily.getJSONArray("sunrise").getString(0)),
                sunset = LocalDateTime.parse(daily.getJSONArray("sunset").getString(0)),
                // The first entry is the current hour, which the big temperature already shows.
                hours = hours.drop(1),
            )
        }.getOrNull()
    }
}
