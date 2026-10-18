package dev.lumen.tv.radio

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.tv.material3.Text
import dev.lumen.tv.ui.LumenTheme
import dev.lumen.tv.ui.SectionLabel
import dev.lumen.tv.ui.Tone
import dev.lumen.tv.ui.Type

/** Radio: big "now playing" header and a row of station tiles. Playback lives in [RadioService]. */
class RadioActivity : ComponentActivity() {

    private var controller: MediaController? = null
    private var playingId by mutableStateOf<String?>(null)
    private var buffering by mutableStateOf(false)

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync(player)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LumenTheme { RadioScreen(playingId, buffering, ::toggle) } }
        window.setBackgroundDrawable(null)
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, RadioService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            controller = runCatching { future.get() }.getOrNull()?.also {
                it.addListener(listener)
                sync(it)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        super.onStop()
    }

    private fun sync(player: Player) {
        playingId = player.currentMediaItem?.mediaId?.takeIf { player.playWhenReady }
        buffering = player.playbackState == Player.STATE_BUFFERING
    }

    private fun toggle(station: Station) {
        val player = controller ?: return
        if (playingId == station.id) {
            player.stop()
            player.clearMediaItems()
            return
        }
        val item = MediaItem.Builder()
            .setMediaId(station.id)
            .setUri(station.url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(station.name)
                    .setArtist(station.tagline)
                    .setStation(station.name)
                    .setArtworkData(station.artwork(), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    .build()
            )
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.play()
    }
}

@Composable
private fun RadioScreen(playingId: String?, buffering: Boolean, onToggle: (Station) -> Unit) {
    val current = STATIONS.firstOrNull { it.id == playingId }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Color(0xFF050507))) {
        Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 44.dp)) {
            SectionLabel("Radio")
            Spacer(Modifier.weight(1f))

            // Now playing
            Row(verticalAlignment = Alignment.CenterVertically) {
                val dot = when {
                    current == null -> Tone.tertiary
                    buffering -> Color(0xFFFF9F0A)
                    else -> Color(0xFFFF453A)
                }
                Box(Modifier.size(8.dp).background(dot, CircleShape))
                Spacer(Modifier.width(10.dp))
                SectionLabel(
                    when {
                        current == null -> "Elige una emisora"
                        buffering -> "Conectando…"
                        else -> "En directo"
                    },
                    color = if (current != null && !buffering) Color(0xFFFF6B6B) else Tone.tertiary,
                )
            }
            Text(
                current?.name ?: "Radio",
                style = Type.display.copy(fontSize = 72.sp, lineHeight = 76.sp, fontWeight = FontWeight(200)),
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(current?.tagline ?: "COPE y su familia de emisoras", style = Type.title.copy(fontWeight = FontWeight(300), color = Tone.secondary))

            Spacer(Modifier.height(48.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                STATIONS.forEachIndexed { i, station ->
                    StationTile(
                        station,
                        playing = station.id == playingId,
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    ) { onToggle(station) }
                }
            }
        }
    }
}

/** Square tile in the station's colour; same focus treatment as the home screen tiles. */
@Composable
private fun StationTile(station: Station, playing: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val focus = remember { Animatable(0f) }
    LaunchedEffect(focused) { focus.animateTo(if (focused) 1f else 0f, tween(140, easing = FastOutSlowInEasing)) }
    Column {
        Box(
            modifier
                .size(148.dp) // five fit across the screen
                .graphicsLayer { val s = 1f + 0.08f * focus.value; scaleX = s; scaleY = s }
                .drawWithContent {
                    val r = CornerRadius(16.dp.toPx())
                    drawRoundRect(Color(station.color), cornerRadius = r)
                    drawContent()
                    val p = focus.value
                    if (p > 0f) {
                        val w = 2.dp.toPx()
                        drawRoundRect(Color.White.copy(alpha = p), Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(r.x - w / 2), style = Stroke(w))
                    }
                }
                .onFocusChanged { focused = it.isFocused }
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
                .padding(18.dp),
        ) {
            Text(station.name, style = Type.title.copy(fontSize = 19.sp, fontWeight = FontWeight(600)), modifier = Modifier.align(Alignment.BottomStart))
            if (playing) {
                Box(Modifier.align(Alignment.TopEnd).size(10.dp).background(Color.White, CircleShape))
            }
        }
        Text(
            if (playing) "Sonando · pulsa para parar" else station.tagline,
            style = Type.caption,
            modifier = Modifier.padding(top = 12.dp).graphicsLayer { alpha = 0.4f + 0.6f * focus.value },
        )
    }
}
