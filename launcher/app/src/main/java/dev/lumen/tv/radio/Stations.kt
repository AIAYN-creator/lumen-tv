package dev.lumen.tv.radio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.io.ByteArrayOutputStream

data class Station(
    val id: String,
    val name: String,
    val tagline: String,
    val url: String,
    /** Brand-ish colour for the station's tile and artwork. */
    val color: Long,
)

/** Streams checked against radio-browser.info and played back from the COPE group's own servers. */
val STATIONS = listOf(
    Station("cope-valencia", "COPE Valencia", "Información y deportes", "https://wecast-bl01.flumotion.com/copesedes/valencia.mp3", 0xFFD71920),
    Station("cope", "COPE", "Cadena nacional", "https://net1-cope-rrcast.flumotion.com/cope/net1.mp3", 0xFFB3141A),
    Station("cadena100", "Cadena 100", "Música", "http://cadena100-streamers-mp3.flumotion.com/cope/cadena100.mp3", 0xFFF2A900),
    Station("rockfm", "Rock FM", "Rock", "http://flucast34-h-cloud.flumotion.com/cope/rockfm-low.mp3", 0xFF3A3A3A),
    Station("megastar", "MegaStar FM", "Éxitos", "http://flucast24-h-cloud.flumotion.com/cope/megastar.mp3", 0xFF7B2CBF),
)

/** Square artwork for the media session (the screensaver's "now playing" cover). */
fun Station.artwork(size: Int = 400): ByteArray {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(color.toInt())
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = 0xFFFFFFFF.toInt()
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSize = size * 0.15f
    }
    val words = name.split(" ")
    val lines = if (words.size > 1 && name.length > 8) listOf(words.dropLast(1).joinToString(" "), words.last()) else listOf(name)
    val lineHeight = paint.textSize * 1.1f
    val top = size / 2f - lineHeight * (lines.size - 1) / 2f + paint.textSize * 0.35f
    lines.forEachIndexed { i, line -> canvas.drawText(line, size / 2f, top + i * lineHeight, paint) }
    return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
}
