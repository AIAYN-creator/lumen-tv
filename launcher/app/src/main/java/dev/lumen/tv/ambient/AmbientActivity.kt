package dev.lumen.tv.ambient

import android.net.Uri
import android.os.Bundle
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Text
import dev.lumen.tv.ui.LumenTheme
import dev.lumen.tv.ui.SectionLabel
import dev.lumen.tv.ui.Tone
import dev.lumen.tv.ui.Type
import dev.lumen.tv.ui.dateText
import dev.lumen.tv.ui.rememberNow
import dev.lumen.tv.ui.timeText
import kotlinx.coroutines.delay
import java.io.File

data class Scene(val id: String, val name: String, val mood: String, val tint: Long)

/** Videos live in /sdcard/Android/data/dev.lumen.tv/files/ambientes/<id>.mp4 (pushed by deploy.ps1). */
val SCENES = listOf(
    Scene("malvarrosa", "Malvarrosa", "Olas y brisa del Mediterráneo", 0xFF1D6FA3),
    Scene("chimenea", "Chimenea", "Leña crepitando", 0xFFB5521B),
    Scene("lluvia", "Lluvia", "Gotas en la ventana", 0xFF3D4A5C),
    Scene("bosque", "Bosque", "Luz entre los árboles", 0xFF2E6B3A),
)

/** Ambientes: pick a scene, then it loops full screen with a quiet clock in the corner. */
class AmbientActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var playing by mutableStateOf<Scene?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) { if (playing != null) stop() else finish() }
        setContent {
            LumenTheme {
                val scene = playing
                if (scene == null) ScenePicker(::fileFor, ::start)
                else ScenePlayer(scene, player)
            }
        }
        window.setBackgroundDrawable(null)
    }

    private fun fileFor(scene: Scene): File? =
        getExternalFilesDir("ambientes")?.let { File(it, "${scene.id}.mp4") }?.takeIf { it.exists() }

    private fun start(scene: Scene) {
        val file = fileFor(scene) ?: return
        val exo = player ?: ExoPlayer.Builder(this).build().also { player = it }
        exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        exo.repeatMode = Player.REPEAT_MODE_ONE
        exo.prepare()
        exo.play()
        playing = scene
        // An ambient scene is meant to stay up: don't let the screensaver or sleep take over.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun stop() {
        player?.stop()
        playing = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStop() {
        stop()
        player?.release()
        player = null
        super.onStop()
    }
}

@Composable
private fun ScenePlayer(scene: Scene, player: ExoPlayer?) {
    val now = rememberNow()
    // The scene's name fades out after a few seconds; only the clock stays.
    var showTitle by remember(scene) { mutableStateOf(true) }
    LaunchedEffect(scene) { delay(5_000); showTitle = false }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { context -> SurfaceView(context).also { player?.setVideoSurfaceView(it) } },
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0x99000000))))
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 56.dp, vertical = 44.dp)) {
            AnimatedVisibility(showTitle, enter = fadeIn(), exit = fadeOut(tween(1_500))) {
                Column {
                    SectionLabel("Ambientes")
                    Text(scene.name, style = Type.title.copy(fontSize = 28.sp, fontWeight = FontWeight(300)))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(now.timeText(), style = Type.temperature.copy(fontSize = 40.sp, lineHeight = 42.sp, color = Color.White.copy(alpha = 0.85f)))
            Text(now.dateText(), style = Type.caption)
        }
    }
}

@Composable
private fun ScenePicker(fileFor: (Scene) -> File?, onStart: (Scene) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0xFF050507))) {
        Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 44.dp)) {
            SectionLabel("Ambientes")
            Spacer(Modifier.weight(1f))
            Text("¿Qué ambiente ponemos?", style = Type.display.copy(fontSize = 56.sp, lineHeight = 60.sp, fontWeight = FontWeight(200)))
            Text("La escena queda en bucle con un reloj discreto. Atrás para salir.", style = Type.title.copy(fontWeight = FontWeight(300), color = Tone.secondary))
            Spacer(Modifier.height(48.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                SCENES.forEachIndexed { i, scene ->
                    SceneTile(scene, available = fileFor(scene) != null, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) { onStart(scene) }
                }
            }
        }
    }
}

@Composable
private fun SceneTile(scene: Scene, available: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val focus = remember { Animatable(0f) }
    LaunchedEffect(focused) { focus.animateTo(if (focused) 1f else 0f, tween(140, easing = FastOutSlowInEasing)) }
    Column {
        Box(
            modifier
                .size(width = 224.dp, height = 126.dp)
                .graphicsLayer { val s = 1f + 0.08f * focus.value; scaleX = s; scaleY = s; alpha = if (available) 1f else 0.45f }
                .drawWithContent {
                    val r = CornerRadius(14.dp.toPx())
                    drawRoundRect(Brush.linearGradient(listOf(Color(scene.tint), Color(scene.tint).copy(alpha = 0.35f))), cornerRadius = r)
                    drawContent()
                    val p = focus.value
                    if (p > 0f) {
                        val w = 2.dp.toPx()
                        drawRoundRect(Color.White.copy(alpha = p), Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(r.x - w / 2), style = Stroke(w))
                    }
                }
                .onFocusChanged { focused = it.isFocused }
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
                .padding(16.dp),
        ) {
            Text(scene.name, style = Type.title.copy(fontSize = 20.sp, fontWeight = FontWeight(600)), modifier = Modifier.align(Alignment.BottomStart))
        }
        Text(
            if (available) scene.mood else "Falta el vídeo",
            style = Type.caption,
            modifier = Modifier.padding(top = 12.dp).width(224.dp).graphicsLayer { alpha = 0.4f + 0.6f * focus.value },
        )
    }
}
