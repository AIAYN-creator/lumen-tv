package dev.lumen.tv.dream

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.lumen.tv.Config
import dev.lumen.tv.data.ImageCache
import dev.lumen.tv.media.NowPlaying
import dev.lumen.tv.media.rememberNowPlaying
import dev.lumen.tv.ui.AlertStatus
import dev.lumen.tv.ui.WeatherGlyph
import dev.lumen.tv.data.HourForecast
import dev.lumen.tv.data.Weather
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import java.time.format.DateTimeFormatter
import dev.lumen.tv.ui.HorizontalHairline
import dev.lumen.tv.ui.MatchModule
import dev.lumen.tv.ui.ModuleRow
import dev.lumen.tv.ui.SectionLabel
import dev.lumen.tv.ui.SkeletonRow
import dev.lumen.tv.ui.Tone
import dev.lumen.tv.ui.TransitModule
import dev.lumen.tv.ui.Type
import dev.lumen.tv.ui.Wallpaper
import dev.lumen.tv.ui.dateText
import dev.lumen.tv.ui.rememberAlerts
import dev.lumen.tv.ui.rememberBus
import dev.lumen.tv.ui.rememberMetro
import dev.lumen.tv.ui.rememberNow
import dev.lumen.tv.ui.rememberSports
import dev.lumen.tv.ui.rememberWeather
import dev.lumen.tv.ui.timeText
import dev.lumen.tv.ui.rememberNight
import dev.lumen.tv.ui.rememberNotes
import dev.lumen.tv.ui.rememberSleepMinutes
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn

/**
 * Screensaver (used by both the system dream and the on-demand activity).
 *
 * Fixed editorial layout, nothing moves around: clock top-left, weather top-right, a reserved
 * "now playing" band, then a 4-column grid (transit, then teams) separated by hairlines.
 */
@Composable
fun ScreensaverScreen(forceNight: Boolean = false) {
    val night = rememberNight()
    Crossfade(targetState = forceNight || night, animationSpec = tween(2_000), label = "night") { night ->
        if (night) NightScreen() else DayScreen()
    }
}

@Composable
private fun DayScreen() {
    Box(Modifier.fillMaxSize()) {
        Wallpaper(Modifier.fillMaxSize(), intervalMillis = 60_000L, kenBurns = true)
        // Extra shade behind the grid so it reads on any photo.
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xB3000000)))
            }
        )
        Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 30.dp, bottom = 26.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                ClockBlock(Modifier.weight(1f))
                NoteBlock(Modifier.weight(1.15f).padding(top = 18.dp, end = 32.dp))
                WeatherBlock()
            }
            Spacer(Modifier.weight(1f))
            MiddleBand()
            Spacer(Modifier.height(20.dp))
            HorizontalHairline()
            TransitGrid()
            HorizontalHairline()
            SportsGrid()
        }
    }
}

@Composable
private fun ClockBlock(modifier: Modifier = Modifier) {
    val now = rememberNow()
    val sleepMinutes = rememberSleepMinutes()
    Column(modifier) {
        Text(now.timeText(), style = Type.display)
        Text(now.dateText(), style = Type.title.copy(fontWeight = FontWeight(300), color = Tone.secondary), modifier = Modifier.padding(top = 6.dp))
        if (sleepMinutes != null) SleepLine(sleepMinutes, Modifier.padding(top = 10.dp))
    }
}

/** "◐ La tele se apaga en 42 min". */
@Composable
private fun SleepLine(minutes: Int, modifier: Modifier = Modifier, color: Color = Tone.secondary) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(color, androidx.compose.foundation.shape.CircleShape))
        Spacer(Modifier.width(8.dp))
        SectionLabel("La tele se apaga en $minutes min", color = color)
    }
}

/**
 * Family notes, beside a hairline. One note: large, up to four lines. Two or more: the two
 * newest, smaller and two lines each, plus "+N más". Empty space when there are none, so the
 * header keeps its shape.
 */
@Composable
private fun NoteBlock(modifier: Modifier = Modifier) {
    val notes = rememberNotes()
    Box(modifier) {
        AnimatedVisibility(notes.isNotEmpty(), enter = fadeIn(tween(800)), exit = fadeOut(tween(800))) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(Tone.hairline))
                Column(Modifier.padding(start = 20.dp)) {
                    val single = notes.size == 1
                    notes.take(2).forEachIndexed { i, note ->
                        if (i > 0) Spacer(Modifier.height(14.dp))
                        SectionLabel(if (note.author.isNotBlank()) "Nota de ${note.author}" else "Nota")
                        Text(
                            note.text,
                            style = if (single) Type.title.copy(fontSize = 22.sp, fontWeight = FontWeight(300), lineHeight = 29.sp)
                            else Type.title.copy(fontSize = 17.sp, fontWeight = FontWeight(300), lineHeight = 22.sp),
                            maxLines = if (single) 4 else 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = if (single) 8.dp else 4.dp),
                        )
                    }
                    if (notes.size > 2) SectionLabel(if (notes.size == 3) "+1 nota más" else "+${notes.size - 2} notas más", Modifier.padding(top = 10.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Night (N2): black, warm and dim. Just what's useful before bed or at 3 a.m.

private val Night = Color(0xFFFFE2C2)

@Composable
private fun NightScreen() {
    val now = rememberNow()
    val weather = rememberWeather()
    val notes = rememberNotes()
    val note = notes.firstOrNull()
    val sleepMinutes = rememberSleepMinutes()
    val stations = Config.METRO_STATIONS.map { it to rememberMetro(it) }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (sleepMinutes != null) {
                SleepLine(sleepMinutes, Modifier.padding(bottom = 18.dp), color = Night.copy(alpha = 0.45f))
            }
            Text(now.timeText(), style = Type.display.copy(fontSize = 120.sp, lineHeight = 120.sp, color = Night.copy(alpha = 0.62f)))
            Text(
                now.dateText(),
                style = Type.title.copy(fontWeight = FontWeight(300), color = Night.copy(alpha = 0.38f)),
                modifier = Modifier.padding(top = 10.dp),
            )

            Box(Modifier.padding(vertical = 34.dp).width(320.dp).height(1.dp).background(Night.copy(alpha = 0.10f)))

            Row(horizontalArrangement = Arrangement.spacedBy(56.dp), verticalAlignment = Alignment.Top) {
                if (weather != null) {
                    NightItem("Ahora") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            WeatherGlyph(weather.sky, weather.isDay, 22.dp, color = Night.copy(alpha = 0.45f))
                            Spacer(Modifier.width(10.dp))
                            NightValue("${weather.temperature}°")
                        }
                        NightDetail(
                            weather.hours.firstOrNull()?.precipitationChance?.takeIf { it >= 10 }?.let { "Lluvia $it%" } ?: weather.description
                        )
                    }
                }
                stations.forEach { (station, arrivals) ->
                    NightItem("Metro · ${station.name}") {
                        val first = arrivals?.firstOrNull()
                        NightValue(first?.let { nightTime(it.wait) } ?: "—")
                        NightDetail(first?.let { "L${it.line} · ${it.destination}" } ?: "Sin servicio")
                    }
                }
            }

            if (note != null) {
                Text(
                    "“${note.text}”" + (if (note.author.isNotBlank()) "  — ${note.author}" else "") +
                        (if (notes.size > 1) "   · +${notes.size - 1}" else ""),
                    style = Type.body.copy(fontSize = 16.sp, color = Night.copy(alpha = 0.40f)),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 34.dp).widthIn(max = 640.dp),
                )
            }
        }
    }
}

@Composable
private fun NightItem(label: String, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        SectionLabel(label, color = Night.copy(alpha = 0.30f))
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun NightValue(text: String) =
    Text(text, style = Type.temperature.copy(fontSize = 28.sp, lineHeight = 30.sp, color = Night.copy(alpha = 0.55f)))

@Composable
private fun NightDetail(text: String) =
    Text(text, style = Type.caption.copy(color = Night.copy(alpha = 0.32f)), maxLines = 1, modifier = Modifier.padding(top = 2.dp))

/** At night the next train can be hours away, so long waits read better as a time ("05:42"). */
private fun nightTime(wait: String): String {
    val minutes = Regex("""^(\d+)\s*min""").find(wait)?.groupValues?.get(1)?.toLongOrNull() ?: return wait
    if (minutes == 0L) return "ahora"
    if (minutes < 60) return "$minutes min"
    return java.time.LocalTime.now().plusMinutes(minutes).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
}

@Composable
private fun WeatherBlock() {
    val weather = rememberWeather() ?: return
    val alerts = rememberAlerts()
    Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WeatherGlyph(weather.sky, weather.isDay, 46.dp)
            Spacer(Modifier.width(16.dp))
            Text("${weather.temperature}°", style = Type.temperature)
        }
        Text(weather.description, style = Type.body.copy(color = Tone.secondary), modifier = Modifier.padding(top = 4.dp))
        Text("${Config.CITY} · ${weather.max}° / ${weather.min}°", style = Type.caption.copy(color = Tone.tertiary))
        Box(Modifier.padding(top = 14.dp)) { AlertStatus(alerts) }
    }
}

/**
 * Band between the header and the grid. Left: what's playing, or today's summary when nothing
 * is; right: the next hours. Both halves are always filled, so the composition stays balanced.
 */
@Composable
private fun MiddleBand() {
    val playing = rememberNowPlaying()
    val weather = rememberWeather()
    Row(Modifier.fillMaxWidth().height(BAND_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        Crossfade(targetState = playing, animationSpec = tween(800), label = "band", modifier = Modifier.weight(1f)) { media ->
            when {
                media != null -> NowPlayingRow(media)
                weather != null -> DaySummary(weather)
                else -> Spacer(Modifier.fillMaxWidth())
            }
        }
        if (weather != null) HourlyForecast(weather.hours.take(6))
    }
}

/** Sunrise, sunset, chance of rain and wind, as four label/figure pairs. */
@Composable
private fun DaySummary(weather: Weather) {
    val hour = DateTimeFormatter.ofPattern("HH:mm")
    Row(horizontalArrangement = Arrangement.spacedBy(44.dp)) {
        Stat("Amanecer", weather.sunrise.format(hour))
        Stat("Atardecer", weather.sunset.format(hour))
        Stat("Lluvia hoy", "${weather.precipitationChance}%")
        Stat("Viento", "${weather.windKmh} km/h")
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        SectionLabel(label)
        Text(value, style = Type.title.copy(fontWeight = FontWeight(300), fontSize = 24.sp), modifier = Modifier.padding(top = 6.dp))
    }
}

/**
 * Next hours: time, pictogram, temperature and chance of rain. Columns are as wide as their
 * content with fixed gaps, so the last one ends exactly on the right margin like the grid below.
 */
@Composable
private fun HourlyForecast(hours: List<HourForecast>) {
    Row(horizontalArrangement = Arrangement.spacedBy(30.dp)) {
        hours.forEach { hour ->
            Column(Modifier.width(IntrinsicSize.Max), horizontalAlignment = Alignment.CenterHorizontally) {
                SectionLabel("${hour.time.hour}h")
                WeatherGlyph(hour.sky, hour.isDay, 28.dp, Modifier.padding(vertical = 6.dp))
                Text("${hour.temperature}°", style = Type.figure)
                Text(
                    if (hour.precipitationChance >= 10) "${hour.precipitationChance}%" else " ",
                    style = Type.caption.copy(fontSize = 10.sp, color = Color(0xFF7FB8FF)),
                )
            }
        }
    }
}

private val BAND_HEIGHT = 96.dp

@Composable
private fun NowPlayingRow(media: NowPlaying) {
    val context = LocalContext.current
    var art by remember(media.art, media.artUri) { mutableStateOf<ImageBitmap?>(media.art?.asImageBitmap()) }
    LaunchedEffect(media.artUri) {
        if (art == null && media.artUri?.startsWith("http") == true) art = ImageCache.load(context, media.artUri, 256)
    }

    // Extrapolated playback position, ticking once a second only while playing.
    var nowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(media) {
        while (media.playing) {
            nowMs = SystemClock.elapsedRealtime()
            kotlinx.coroutines.delay(1_000L)
        }
    }
    val position = if (media.playing) media.positionMs + ((nowMs - media.positionAt) * media.speed).toLong() else media.positionMs
    val progress = if (media.durationMs > 0) (position.toFloat() / media.durationMs).coerceIn(0f, 1f) else 0f

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(84.dp).clip(RoundedCornerShape(6.dp)).background(Tone.hairline)) {
            art?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Column(Modifier.padding(start = 20.dp).width(340.dp)) {
            SectionLabel(if (media.playing) "Sonando · ${media.app}" else "En pausa · ${media.app}")
            Text(media.title, style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            Text(media.artist, style = Type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (media.durationMs > 0) {
                Row(Modifier.padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(250.dp).height(2.dp).background(Tone.hairline)) {
                        Box(Modifier.fillMaxWidth(progress).height(2.dp).background(Tone.primary))
                    }
                    Text(
                        "${position.mmss()} / ${media.durationMs.mmss()}",
                        style = Type.caption.copy(fontSize = 10.sp, color = Tone.tertiary),
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
        }
    }
}

private fun Long.mmss(): String {
    val seconds = coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun TransitGrid() {
    val modules = mutableListOf<@Composable ColumnScope.() -> Unit>()
    Config.METRO_STATIONS.forEach { station ->
        val arrivals = rememberMetro(station)
        modules += { TransitModule("Metro · ${station.name}", arrivals, round = true) }
    }
    Config.BUS_STOPS.forEach { stop ->
        val arrivals = rememberBus(stop)
        modules += { TransitModule("Bus ${stop.line} · ${stop.name}", arrivals, round = false) }
    }
    ModuleRow(height = 112.dp, modules = modules)
}

@Composable
private fun SportsGrid() {
    val cards = rememberSports()
    val modules = Config.TEAMS.map<_, @Composable ColumnScope.() -> Unit> { team ->
        {
            val card = cards?.firstOrNull { it.team == team }
            if (card != null) {
                MatchModule(card)
            } else {
                SectionLabel(team.name)
                SkeletonRow(0.7f)
                SkeletonRow(0.6f)
                SkeletonRow(0.8f)
            }
        }
    }
    ModuleRow(height = 116.dp, modules = modules)
}
