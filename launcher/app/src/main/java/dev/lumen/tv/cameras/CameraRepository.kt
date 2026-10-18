package dev.lumen.tv.cameras

import android.content.Context
import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** One public camera: a still image refreshed every few minutes (DGT) or a live stream (València). */
data class Camera(
    val id: String,
    val source: Source,
    /** Place, e.g. "Burgos" or "Pintor Maella (Av. França)". */
    val place: String,
    /** Road and kilometre for DGT cameras ("A-62 · km 25,3"), empty for city cameras. */
    val road: String,
    /** Where the road heads ("Portugal"), empty when unknown. */
    val towards: String,
    val imageUrl: String?,
) {
    enum class Source(val label: String) { DGT("DGT"), VALENCIA("València") }

    /** The city's public viewer page; it streams live video for about 20 seconds per view. */
    val liveUrl: String get() = "https://camaras.valencia.es/camaras/visualizador.html?id=$id"
}

/**
 * Camera lists: the DGT's DATEX2 feed for the state road network (CC BY) and the València
 * city cameras from the municipal geoportal (CC BY). Both are cached for a day.
 */
object CameraRepository {

    private const val DGT_FEED = "https://nap.dgt.es/datex2/v3/dgt/DevicePublication/camaras_datex2_v37.xml"
    private const val VALENCIA_FEED = "https://geoportal.valencia.es/server/rest/services/OPENDATA/Trafico/MapServer/190/query" +
        "?where=1%3D1&outFields=idcamara,descripcio&outSR=4326&f=json&resultRecordCount=2000"
    private const val MAX_AGE = 24 * 3_600_000L

    suspend fun load(context: Context): List<Camera> = withContext(Dispatchers.IO) {
        val dgt = cached(context, "cams_dgt_v2.json") { fetchDgt() }
        val valencia = cached(context, "cams_vlc.json") { fetchValencia() }
        dgt + valencia
    }

    private fun cached(context: Context, name: String, fetch: () -> List<Camera>): List<Camera> {
        val file = File(context.cacheDir, name)
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < MAX_AGE) {
            runCatching { return decode(file.readText()) }
        }
        val fresh = runCatching(fetch).onFailure { Log.w("Lumen", "Camera list failed: $name", it) }.getOrDefault(emptyList())
        if (fresh.isNotEmpty()) file.writeText(encode(fresh))
        // Fall back to a stale cache rather than nothing.
        return fresh.ifEmpty { runCatching { decode(file.readText()) }.getOrDefault(emptyList()) }
    }

    private fun fetchDgt(): List<Camera> {
        val connection = (URL(DGT_FEED).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 60_000 }
        return connection.inputStream.use { stream ->
            val parser = Xml.newPullParser().apply { setInput(stream, "UTF-8") }
            val cameras = mutableListOf<Camera>()
            var id: String? = null
            val fields = mutableMapOf<String, String>()
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "device" -> { id = parser.getAttributeValue(null, "id"); fields.clear() }
                        "roadName", "roadDestination", "kilometerPoint", "province", "deviceUrl", "typeOfDevice" ->
                            if (id != null) fields[parser.name] = parser.nextText().trim()
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "device" && id != null) {
                        val url = fields["deviceUrl"]
                        if (fields["typeOfDevice"] == "camera" && url != null) {
                            val km = fields["kilometerPoint"]?.let { " · km " + it.replace('.', ',') }.orEmpty()
                            cameras += Camera(
                                id = id!!,
                                source = Camera.Source.DGT,
                                place = fields["province"].orEmpty().prettyPlace(),
                                road = fields["roadName"].orEmpty() + km,
                                towards = fields["roadDestination"].orEmpty().prettyPlace(),
                                imageUrl = url,
                            )
                        }
                        id = null
                    }
                }
            }
            cameras
        }
    }

    private fun fetchValencia(): List<Camera> {
        val connection = (URL(VALENCIA_FEED).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 30_000 }
        val features = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getJSONArray("features")
        return (0 until features.length()).mapNotNull { i ->
            val a = features.getJSONObject(i).getJSONObject("attributes")
            val id = a.optString("idcamara").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Camera(id, Camera.Source.VALENCIA, a.optString("descripcio").trim(), "", "", null)
        }
    }

    /** "VALENCIA/VALÈNCIA" → "València", "CORUÑA, A" → "A Coruña", "BURGOS" → "Burgos". */
    private fun String.prettyPlace(): String {
        if (isBlank()) return ""
        var s = substringAfter('/').trim()
        Regex("""^(.+), (A|O|EL|LA|LOS|LAS|L'|ILLES|LES|ELS|ES)$""").find(s)?.let { s = "${it.groupValues[2]} ${it.groupValues[1]}" }
        val es = Locale.forLanguageTag("es-ES")
        return s.lowercase(es).split(' ', '-').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(es) } }
    }

    private fun encode(cameras: List<Camera>): String = JSONArray().apply {
        cameras.forEach {
            put(JSONObject().put("id", it.id).put("s", it.source.name).put("p", it.place).put("r", it.road).put("t", it.towards).put("u", it.imageUrl ?: ""))
        }
    }.toString()

    private fun decode(json: String): List<Camera> = JSONArray(json).let { a ->
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Camera(o.getString("id"), Camera.Source.valueOf(o.getString("s")), o.getString("p"), o.getString("r"), o.getString("t"), o.getString("u").ifEmpty { null })
        }
    }
}
