package dev.lumen.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lumen.tv.data.Sky
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Line-art weather pictograms, drawn rather than loaded so they share the hairline, round-capped
 * look of the thin Inter numerals. Everything is laid out on a 24×24 grid and scaled to [size].
 */
@Composable
fun WeatherGlyph(sky: Sky, isDay: Boolean, size: Dp, modifier: Modifier = Modifier, color: Color = Tone.primary) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val stroke = Stroke(width = (1.5f * u).coerceAtLeast(1.5f), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val g = Glyph(this, u, stroke, color)
        when (sky) {
            Sky.CLEAR -> if (isDay) g.sun(12f, 12f, 4.5f, 7.5f, 10f) else g.moon(12f, 12f, 7.5f)
            Sky.PARTLY -> {
                val cloud = g.cloud(dx = 2.5f, dy = 3f)
                clipPath(cloud, ClipOp.Difference) {
                    if (isDay) g.sun(9f, 9f, 3.5f, 5.8f, 7.6f) else g.moon(9.5f, 9.5f, 5.5f)
                }
                g.draw(cloud)
            }
            Sky.CLOUDY -> g.draw(g.cloud(dx = 0f, dy = 1f))
            Sky.FOG -> {
                g.draw(g.cloud(dx = 0f, dy = -2.5f))
                g.line(5f, 18f, 19f, 18f)
                g.line(7f, 21f, 17f, 21f)
            }
            Sky.DRIZZLE -> {
                g.draw(g.cloud(dx = 0f, dy = -2.5f))
                listOf(8f, 12f, 16f).forEach { x -> g.dot(x, 19f); g.dot(x - 1.5f, 22f) }
            }
            Sky.RAIN -> {
                g.draw(g.cloud(dx = 0f, dy = -2.5f))
                listOf(8f, 12f, 16f).forEach { x -> g.line(x, 17.5f, x - 1.8f, 21.5f) }
            }
            Sky.SNOW -> {
                g.draw(g.cloud(dx = 0f, dy = -2.5f))
                listOf(8f, 12f, 16f).forEach { x -> g.flake(x, 19.5f) }
            }
            Sky.STORM -> {
                g.draw(g.cloud(dx = 0f, dy = -2.5f))
                g.polyline(13f to 16f, 10.5f to 19.5f, 13.5f to 19.5f, 11f to 23f)
            }
        }
    }
}

private class Glyph(val scope: DrawScope, val u: Float, val stroke: Stroke, val color: Color) {

    private fun p(x: Float, y: Float) = Offset(x * u, y * u)

    fun draw(path: Path) = scope.drawPath(path, color, style = stroke)

    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        scope.drawLine(color, p(x1, y1), p(x2, y2), strokeWidth = stroke.width, cap = StrokeCap.Round)

    fun dot(x: Float, y: Float) = scope.drawCircle(color, radius = stroke.width * 0.75f, center = p(x, y))

    fun polyline(vararg points: Pair<Float, Float>) {
        val path = Path().apply {
            points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * u, y * u) else lineTo(x * u, y * u) }
        }
        draw(path)
    }

    /** Circle with radiating rays. */
    fun sun(cx: Float, cy: Float, r: Float, rayFrom: Float, rayTo: Float) {
        scope.drawCircle(color, radius = r * u, center = p(cx, cy), style = stroke)
        for (i in 0 until 8) {
            val a = i * PI / 4
            line(
                cx + rayFrom * cos(a).toFloat(), cy + rayFrom * sin(a).toFloat(),
                cx + rayTo * cos(a).toFloat(), cy + rayTo * sin(a).toFloat(),
            )
        }
    }

    /** Crescent: a disc with an offset disc cut out of it. */
    fun moon(cx: Float, cy: Float, r: Float) {
        val disc = Path().apply { addOval(Rect(p(cx, cy), r * u)) }
        val bite = Path().apply { addOval(Rect(p(cx + r * 0.55f, cy - r * 0.45f), r * 0.85f * u)) }
        draw(Path.combine(PathOperation.Difference, disc, bite))
    }

    /** Three overlapping puffs on a flat base, merged into one outline. */
    fun cloud(dx: Float, dy: Float): Path {
        fun oval(cx: Float, cy: Float, r: Float) = Path().apply { addOval(Rect(p(cx + dx, cy + dy), r * u)) }
        val base = Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    Rect(p(4.5f + dx, 13f + dy), p(19.5f + dx, 18f + dy)),
                    androidx.compose.ui.geometry.CornerRadius(2.5f * u),
                )
            )
        }
        var shape = Path.combine(PathOperation.Union, oval(8.5f, 14f, 4f), oval(12.5f, 11f, 5f))
        shape = Path.combine(PathOperation.Union, shape, oval(16.5f, 14f, 3.5f))
        return Path.combine(PathOperation.Union, shape, base)
    }

    /** Small six-pointed flake. */
    fun flake(cx: Float, cy: Float) {
        for (i in 0 until 3) {
            val a = i * PI / 3 + PI / 2
            val dx = 1.6f * cos(a).toFloat()
            val dy = 1.6f * sin(a).toFloat()
            line(cx - dx, cy - dy, cx + dx, cy + dy)
        }
    }
}
