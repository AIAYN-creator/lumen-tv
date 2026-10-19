package dev.lumen.tv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.lumen.tv.data.Arrival
import dev.lumen.tv.data.ImageCache
import dev.lumen.tv.data.MatchState
import dev.lumen.tv.data.TeamCard
import dev.lumen.tv.data.WeatherAlert
import dev.lumen.tv.data.reachable
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val hourFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dayFormat = DateTimeFormatter.ofPattern("EEE d", Locale.forLanguageTag("es-ES"))
private val LIVE_RED = Color(0xFFFF453A)

// ---------------------------------------------------------------------------------------------
// Grid primitives

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = Tone.tertiary) {
    Text(text.uppercase(Locale.forLanguageTag("es-ES")), style = Type.label.copy(color = color), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

@Composable
fun HorizontalHairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Tone.hairline))
}

@Composable
fun VerticalHairline(modifier: Modifier = Modifier) {
    Box(modifier.width(1.dp).fillMaxHeight().background(Tone.hairline))
}

/**
 * A row of equal-width columns divided by hairlines, at a fixed height. Every module gets
 * exactly the same box regardless of how much it has to show, so the grid stays symmetric.
 */
@Composable
fun ModuleRow(height: Dp, modules: List<@Composable ColumnScope.() -> Unit>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(height)) {
        modules.forEachIndexed { index, module ->
            if (index > 0) VerticalHairline(Modifier.padding(vertical = 14.dp))
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(
                        start = if (index == 0) 0.dp else 20.dp,
                        end = if (index == modules.lastIndex) 0.dp else 20.dp,
                        top = 14.dp,
                        bottom = 12.dp,
                    ),
                verticalArrangement = Arrangement.SpaceBetween,
                content = module,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Weather alert

/**
 * AEMET status, always two lines so the layout never shifts: "● Aviso naranja · lluvias /
 * Hasta las 03:59" in the warning colour, or a quiet "● Sin avisos · AEMET / Valencia" when
 * there are none. [alerts] null means not loaded yet.
 */
@Composable
fun AlertStatus(alerts: List<WeatherAlert>?, alignEnd: Boolean = true) {
    val alert = alerts?.firstOrNull()
    val color: Color
    val title: String
    val detail: String
    if (alert != null) {
        val now = OffsetDateTime.now()
        val local = { t: OffsetDateTime -> t.atZoneSameInstant(ZoneId.systemDefault()).format(hourFormat) }
        color = Color(alert.level.color)
        title = "Aviso ${alert.level.label} · ${alert.event}"
        detail = if (alert.onset.isAfter(now)) "Desde las ${local(alert.onset)}" else "Hasta las ${local(alert.expires)}"
    } else {
        color = Tone.tertiary
        title = if (alerts == null) "Avisos · AEMET" else "Sin avisos · AEMET"
        detail = if (alerts == null) "Consultando…" else "${dev.lumen.tv.Config.CITY}, próximas 12 h"
    }
    Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(color, CircleShape))
            Spacer(Modifier.width(7.dp))
            SectionLabel(title, color = color)
        }
        Text(detail, style = Type.caption.copy(color = Tone.tertiary), modifier = Modifier.padding(top = 2.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// Transit

/** Metro lines get a dot, buses a small square. */
@Composable
fun LineBadge(line: String, color: Long, round: Boolean) {
    Box(
        Modifier
            .size(width = if (round || line.length < 2) 18.dp else 22.dp, height = 18.dp)
            .background(Color(color), if (round) CircleShape else RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(line, style = Type.label.copy(fontSize = 10.sp, letterSpacing = 0.sp, color = Color.White, fontWeight = FontWeight(700)))
    }
}

/**
 * Label plus exactly [rows] arrival lines (blank lines are kept so every module is the same
 * height). [arrivals] null means loading, or no data for a couple of minutes: faint placeholder
 * bars instead of an error message.
 *
 * [walk] is the minutes it takes to reach the platform: anything leaving sooner is dropped, so the
 * three lines are three trains you could actually get to instead of one real option and two you
 * have already missed. It stays out of the label, which has no room for it at four modules across.
 */
@Composable
fun ColumnScope.TransitModule(title: String, arrivals: List<Arrival>?, round: Boolean, rows: Int = 3, walk: Int = 0) {
    SectionLabel(title)
    val shown = arrivals?.reachable(walk).orEmpty().take(rows)
    for (i in 0 until rows) {
        val arrival = shown.getOrNull(i)
        when {
            arrival != null -> ArrivalRow(arrival, round)
            arrivals == null -> SkeletonRow(SKELETON_WIDTHS[i % SKELETON_WIDTHS.size])
            // Trains are running, you just cannot reach any of them in time. Worth saying, because
            // it is not the same as the line having stopped for the night.
            i == 0 && arrivals.isNotEmpty() -> Text("No llegas a ninguno", style = Type.body.copy(color = Tone.tertiary), modifier = Modifier.height(22.dp))
            i == 0 -> Text("Sin servicio próximo", style = Type.body.copy(color = Tone.tertiary), modifier = Modifier.height(22.dp))
            else -> Spacer(Modifier.height(22.dp))
        }
    }
}

private val SKELETON_WIDTHS = listOf(0.72f, 0.56f, 0.64f)

/** A row-sized placeholder: a thin rounded bar in the hairline tone. */
@Composable
fun SkeletonRow(widthFraction: Float) {
    Box(Modifier.height(22.dp).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth(widthFraction).height(6.dp).background(Tone.hairline, RoundedCornerShape(3.dp)))
    }
}

@Composable
private fun ArrivalRow(arrival: Arrival, round: Boolean) {
    Row(Modifier.height(22.dp), verticalAlignment = Alignment.CenterVertically) {
        LineBadge(arrival.line, arrival.color, round)
        Text(
            arrival.destination,
            style = Type.body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 10.dp, end = 8.dp).weight(1f),
        )
        WaitText(arrival)
    }
}

/** "4 min" with a light number and a small unit; "0 min" and EMT's "Próximo" become "ahora". */
@Composable
private fun WaitText(arrival: Arrival) {
    val minutes = arrival.minutes
    val text = when {
        minutes == 0 -> buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight(600))) { append("ahora") } }
        minutes != null -> buildAnnotatedString {
            append("$minutes")
            withStyle(SpanStyle(fontSize = 11.sp, color = Tone.tertiary)) { append(" min") }
        }
        else -> buildAnnotatedString { append(arrival.wait) }
    }
    Text(text, style = Type.figure, maxLines = 1)
}

// ---------------------------------------------------------------------------------------------
// Sports

/** Header, two team lines and the next fixture, always the same four lines. */
@Composable
fun ColumnScope.MatchModule(card: TeamCard) {
    val match = card.main
    if (match?.state == MatchState.LIVE) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(LIVE_RED, CircleShape))
            Spacer(Modifier.width(7.dp))
            SectionLabel("En directo" + (match.clock?.let { " · $it" } ?: ""), color = LIVE_RED)
        }
    } else {
        SectionLabel(match?.let { "${it.competition} · ${it.start.dayText()}" } ?: card.team.name)
    }

    if (match == null) {
        Text("Sin partidos", style = Type.body.copy(color = Tone.tertiary))
        Spacer(Modifier.height(22.dp))
    } else {
        val finished = match.state == MatchState.FINISHED
        val homeWins = (match.homeScore ?: 0) > (match.awayScore ?: 0)
        val awayWins = (match.awayScore ?: 0) > (match.homeScore ?: 0)
        TeamLine(match.home, match.homeBadge, match.homeScore, winner = finished && homeWins, dimmed = finished && awayWins)
        TeamLine(match.away, match.awayBadge, match.awayScore, winner = finished && awayWins, dimmed = finished && homeWins)
    }

    val next = card.next
    Text(
        if (next != null) {
            val rival = if (next.home.startsWith(card.team.name.substringBefore(' '))) next.away else next.home
            "Próximo · $rival · ${next.start.dayText()} ${next.start.hourText()}"
        } else "",
        style = Type.caption.copy(color = Tone.tertiary, fontSize = 11.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun TeamLine(name: String, badgeUrl: String?, score: Int?, winner: Boolean, dimmed: Boolean) {
    val color = if (dimmed) Tone.secondary else Tone.primary
    Row(Modifier.height(22.dp), verticalAlignment = Alignment.CenterVertically) {
        Crest(badgeUrl, 20.dp)
        Text(
            name,
            style = (if (winner) Type.bodyStrong else Type.body).copy(color = color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 10.dp, end = 8.dp).weight(1f),
        )
        Text(score?.toString() ?: "–", style = (if (winner) Type.figureStrong else Type.figure).copy(color = color))
    }
}

@Composable
fun Crest(url: String?, size: Dp) {
    val context = LocalContext.current
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) { if (url != null) image = ImageCache.load(context, url) }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        image?.let { Image(it, contentDescription = null, modifier = Modifier.size(size)) }
            ?: Box(Modifier.size(size * 0.6f).background(Tone.hairline, CircleShape))
    }
}

fun Instant.dayText(): String {
    val date = atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (date) {
        today -> "hoy"
        today.minusDays(1) -> "ayer"
        today.plusDays(1) -> "mañana"
        else -> date.format(dayFormat)
    }
}

fun Instant.hourText(): String = atZone(ZoneId.systemDefault()).format(hourFormat)
