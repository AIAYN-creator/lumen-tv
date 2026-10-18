package dev.lumen.tv.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.clip
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
import dev.lumen.tv.data.ImageCache
import dev.lumen.tv.data.Match
import dev.lumen.tv.data.MatchState
import dev.lumen.tv.data.TeamCard
import dev.lumen.tv.data.Weather
import dev.lumen.tv.media.NowPlaying
import dev.lumen.tv.state.Note
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * What the home screen's hero can show, most important first. When several apply at once the
 * first is shown large and the next two as one-line rows underneath, so nothing gets lost.
 */
sealed interface HeroItem {
    data class LiveMatch(val card: TeamCard) : HeroItem
    data class FamilyNote(val note: Note) : HeroItem
    data class Playing(val media: NowPlaying) : HeroItem
    data class MatchToday(val card: TeamCard, val match: Match) : HeroItem
}

fun heroItems(notes: List<Note>, media: NowPlaying?, sports: List<TeamCard>?): List<HeroItem> {
    val today = LocalDate.now()
    val live = sports.orEmpty().filter { it.main?.state == MatchState.LIVE }.map { HeroItem.LiveMatch(it) }
    val todays = sports.orEmpty().mapNotNull { card ->
        card.next?.takeIf { it.start.atZone(ZoneId.systemDefault()).toLocalDate() == today && it.start.isAfter(Instant.now()) }
            ?.let { HeroItem.MatchToday(card, it) }
    }
    // Newest note first; every note gets a place in the queue (main or one of the secondary lines).
    return live + notes.map { HeroItem.FamilyNote(it) } + listOfNotNull(media?.let { HeroItem.Playing(it) }) + todays
}

private val HERO_MAIN_HEIGHT = 128.dp
private const val MAX_SECONDARY = 2

@Composable
fun Hero(items: List<HeroItem>, weather: Weather?, now: LocalDateTime, modifier: Modifier = Modifier) {
    val main = items.firstOrNull()
    val secondary = items.drop(1).take(MAX_SECONDARY)
    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(HERO_MAIN_HEIGHT)) {
            AnimatedContent(
                targetState = main,
                transitionSpec = { fadeIn(tween(600)) togetherWith fadeOut(tween(400)) },
                contentKey = { it?.let { item -> item::class } },
                label = "hero",
            ) { item ->
                when (item) {
                    is HeroItem.LiveMatch -> LiveMatchMain(item.card)
                    is HeroItem.FamilyNote -> NoteMain(item.note)
                    is HeroItem.Playing -> PlayingMain(item.media)
                    is HeroItem.MatchToday -> MatchTodayMain(item.card, item.match)
                    null -> FallbackMain(weather, now)
                }
            }
        }
        // Secondary rows: always the same two slots, empty when unused, so the page never shifts.
        Column(Modifier.padding(top = 14.dp)) {
            val hidden = items.size - 1 - secondary.size
            repeat(MAX_SECONDARY) { i ->
                Box(Modifier.height(30.dp)) {
                    secondary.getOrNull(i)?.let { SecondaryRow(it, more = if (i == secondary.lastIndex) hidden else 0) }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Main renderers

@Composable
private fun NoteMain(note: Note) = Column {
    SectionLabel(if (note.author.isNotBlank()) "Nota de ${note.author}" else "Nota")
    Text(
        note.text,
        style = Type.title.copy(fontSize = 32.sp, fontWeight = FontWeight(300), lineHeight = 40.sp),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 10.dp),
    )
}

@Composable
private fun PlayingMain(media: NowPlaying) {
    val context = LocalContext.current
    var art by remember(media.title) { mutableStateOf<ImageBitmap?>(media.art?.asImageBitmap()) }
    LaunchedEffect(media.artUri) {
        if (art == null && media.artUri?.startsWith("http") == true) art = ImageCache.load(context, media.artUri, 256)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(112.dp).clip(RoundedCornerShape(8.dp)).background(Tone.hairline)) {
            art?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.size(112.dp)) }
        }
        Column(Modifier.padding(start = 24.dp)) {
            SectionLabel(if (media.playing) "Sonando · ${media.app}" else "En pausa · ${media.app}")
            Text(media.title, style = Type.title.copy(fontSize = 32.sp, fontWeight = FontWeight(300)), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            Text(media.artist, style = Type.body.copy(color = Tone.secondary, fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LiveMatchMain(card: TeamCard) {
    val match = card.main ?: return
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(LIVE, CircleShape))
            Spacer(Modifier.width(8.dp))
            SectionLabel("En directo · ${match.competition}" + (match.clock?.let { " · $it" } ?: ""), color = LIVE)
        }
        ScoreLine(match, Modifier.padding(top = 14.dp))
    }
}

@Composable
private fun MatchTodayMain(card: TeamCard, match: Match) = Column {
    SectionLabel("Hoy · ${match.competition} · ${match.start.hourText()}")
    ScoreLine(match, Modifier.padding(top = 14.dp), showScore = false)
}

/** "[crest] Valencia  1 – 0  Villarreal [crest]" in large light type. */
@Composable
private fun ScoreLine(match: Match, modifier: Modifier = Modifier, showScore: Boolean = true) {
    val big = Type.title.copy(fontSize = 32.sp, fontWeight = FontWeight(300))
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Crest(match.homeBadge, 40.dp)
        Text(match.home, style = big, maxLines = 1, modifier = Modifier.padding(start = 14.dp))
        Text(
            if (showScore) "  ${match.homeScore ?: 0} – ${match.awayScore ?: 0}  " else "  –  ",
            style = big.copy(fontWeight = FontWeight(if (showScore) 500 else 200)),
        )
        Text(match.away, style = big, maxLines = 1, modifier = Modifier.padding(end = 14.dp))
        Crest(match.awayBadge, 40.dp)
    }
}

/** Nothing going on: greeting and a one-line read of the day's weather. */
@Composable
private fun FallbackMain(weather: Weather?, now: LocalDateTime) = Column {
    SectionLabel(now.greeting())
    Text(
        weather?.let { daySentence(it) } ?: "Que tengas un buen día",
        style = Type.title.copy(fontSize = 32.sp, fontWeight = FontWeight(300), lineHeight = 40.sp),
        maxLines = 2,
        modifier = Modifier.padding(top = 10.dp),
    )
}

private fun daySentence(weather: Weather): String {
    val rainyHours = weather.hours.take(6).count { it.precipitationChance >= 50 }
    val sky = when {
        rainyHours >= 4 -> "Va a seguir lloviendo"
        rainyHours >= 1 -> "Puede llover en las próximas horas"
        else -> weather.description
    }
    return "$sky. ${weather.temperature}° ahora, entre ${weather.min}° y ${weather.max}°."
}

// -------------------------------------------------------------------------------------------------
// Secondary rows

/** One line: dot, tracked label, text; [more] adds "+N más" when items didn't fit. */
@Composable
private fun SecondaryRow(item: HeroItem, more: Int) {
    val (label, text, color) = when (item) {
        is HeroItem.LiveMatch -> item.card.main!!.let { Triple("En directo", "${it.home} ${it.homeScore ?: 0} – ${it.awayScore ?: 0} ${it.away}", LIVE) }
        is HeroItem.FamilyNote -> Triple(if (item.note.author.isNotBlank()) "Nota de ${item.note.author}" else "Nota", item.note.text, Tone.tertiary)
        is HeroItem.Playing -> Triple("Sonando", "${item.media.title} · ${item.media.artist}", Tone.tertiary)
        is HeroItem.MatchToday -> Triple("Hoy ${item.match.start.hourText()}", "${item.match.home} – ${item.match.away}", Tone.tertiary)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(5.dp).background(color, CircleShape))
        Spacer(Modifier.width(10.dp))
        SectionLabel(label, color = color)
        Spacer(Modifier.width(12.dp))
        Text(text, style = Type.body.copy(color = Tone.secondary), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (more > 0) {
            Spacer(Modifier.width(14.dp))
            SectionLabel("+$more más")
        }
    }
}

private val LIVE = Color(0xFFFF453A)
