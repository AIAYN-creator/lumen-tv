package dev.lumen.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import dev.lumen.tv.R

private fun inter(weight: Int) = Font(
    R.font.inter,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Inter (variable), from Google Fonts. */
val Inter = FontFamily(inter(100), inter(200), inter(300), inter(400), inter(500), inter(600), inter(700))

/** Text tones: everything is white, hierarchy comes from opacity and weight. */
object Tone {
    val primary = Color.White
    val secondary = Color.White.copy(alpha = 0.72f)
    val tertiary = Color.White.copy(alpha = 0.48f)
    val hairline = Color.White.copy(alpha = 0.16f)
}

/** The type scale. Numbers are tabular so times and scores don't jitter as they change. */
object Type {
    private val base = TextStyle(fontFamily = Inter, color = Tone.primary, fontFeatureSettings = "tnum")

    /** Giant clock. */
    val display = base.copy(fontSize = 84.sp, fontWeight = FontWeight(150), letterSpacing = (-0.04).em, lineHeight = 84.sp)

    /** Home clock. */
    val clock = base.copy(fontSize = 64.sp, fontWeight = FontWeight(200), letterSpacing = (-0.03).em, lineHeight = 66.sp)

    /** Big temperature. */
    val temperature = base.copy(fontSize = 44.sp, fontWeight = FontWeight(200), letterSpacing = (-0.03).em, lineHeight = 46.sp)

    /** Section labels: small, uppercase, widely tracked. */
    val label = base.copy(fontSize = 10.sp, fontWeight = FontWeight(600), letterSpacing = 0.16.em, color = Tone.tertiary)

    val title = base.copy(fontSize = 19.sp, fontWeight = FontWeight(500), letterSpacing = (-0.01).em)
    val body = base.copy(fontSize = 14.sp, fontWeight = FontWeight(400))
    val bodyStrong = base.copy(fontSize = 14.sp, fontWeight = FontWeight(600))
    val caption = base.copy(fontSize = 12.sp, fontWeight = FontWeight(400), color = Tone.secondary)

    /** Minutes, scores. */
    val figure = base.copy(fontSize = 17.sp, fontWeight = FontWeight(300))
    val figureStrong = base.copy(fontSize = 17.sp, fontWeight = FontWeight(600))
}

@Composable
fun LumenTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color.Black,
            onSurface = Color.White,
        ),
        content = content,
    )
}
