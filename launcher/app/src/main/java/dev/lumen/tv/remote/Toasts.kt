package dev.lumen.tv.remote

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.lumen.tv.data.ImageCache
import dev.lumen.tv.ui.SectionLabel
import dev.lumen.tv.ui.Tone
import dev.lumen.tv.ui.Type
import kotlinx.coroutines.delay

private val toastShape = RoundedCornerShape(18.dp)
private val toastBackground = Color(0xF5101014)

/** Match alert card: crest, coloured tag ("GOL · 67'"), score line and player. */
@Composable
fun MatchToast(alert: MatchAlert) {
    val context = LocalContext.current
    var crest by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(alert.crestUrl) { alert.crestUrl?.let { crest = ImageCache.load(context, it, 160) } }
    Row(
        Modifier.widthIn(max = 420.dp).background(toastBackground, toastShape).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            crest?.let { Image(it, contentDescription = null, modifier = Modifier.size(44.dp)) }
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (alert.kind == MatchAlert.Kind.RED_CARD) {
                    Box(Modifier.size(width = 7.dp, height = 10.dp).background(Color(0xFFFF453A), RoundedCornerShape(1.dp)))
                } else {
                    Box(Modifier.size(7.dp).background(alert.accent, CircleShape))
                }
                Spacer(Modifier.width(8.dp))
                SectionLabel(alert.tag, color = alert.accent)
            }
            Text(alert.title, style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            if (alert.subtitle.isNotBlank()) Text(alert.subtitle, style = Type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Last-minute warning before the sleep timer turns the TV off, with a live countdown. */
@Composable
fun SleepWarningToast(sleepAt: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(250) } }
    val seconds = ((sleepAt - now) / 1000).coerceAtLeast(0)
    Row(
        Modifier.background(toastBackground, toastShape).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$seconds", style = Type.temperature.copy(fontSize = 34.sp, lineHeight = 36.sp), modifier = Modifier.width(56.dp))
        Column {
            SectionLabel("Temporizador")
            Text("La tele se apagará en $seconds s", style = Type.title.copy(fontSize = 17.sp, fontWeight = FontWeight(500)))
            Text("Pulsa cualquier botón para seguir viendo", style = Type.caption.copy(color = Tone.tertiary))
        }
    }
}
