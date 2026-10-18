package dev.lumen.tv.cameras

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import dev.lumen.tv.ui.LumenTheme
import dev.lumen.tv.ui.SectionLabel
import dev.lumen.tv.ui.Tone
import dev.lumen.tv.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What's on screen: a DGT still (with when it was taken) or a València live stream. */
private sealed interface Shown {
    val camera: Camera
    data class Still(override val camera: Camera, val image: ImageBitmap, val takenAt: Instant?) : Shown
    data class Live(override val camera: Camera) : Shown
}

/**
 * Cámaras: a random public camera from anywhere in Spain; OK jumps to the next one, with a
 * soft crossfade (the next camera is fully loaded before it replaces the current one).
 */
class CamerasActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { LumenTheme { CamerasScreen() } }
        window.setBackgroundDrawable(null)
    }
}

/** València city cameras are live but the city's viewer ends each view after 20 s. */
private const val LIVE_SECONDS = 18
/** Share of picks that go to the (live) València cameras. */
private const val VALENCIA_SHARE = 0.25

@Composable
private fun CamerasScreen() {
    val context = LocalContext.current
    var cameras by remember { mutableStateOf<List<Camera>?>(null) }
    var shown by remember { mutableStateOf<Shown?>(null) }
    var advance by remember { mutableIntStateOf(0) }
    var hintVisible by remember { mutableStateOf(true) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        cameras = CameraRepository.load(context)
        focus.requestFocus()
    }
    // Load the next camera in the background; the current one stays up until it's ready.
    LaunchedEffect(cameras, advance) {
        val list = cameras ?: return@LaunchedEffect
        if (list.isEmpty()) return@LaunchedEffect
        repeat(8) {
            val camera = pick(list)
            val next = if (camera.source == Camera.Source.VALENCIA) Shown.Live(camera) else loadStill(camera)
            if (next != null) { shown = next; return@LaunchedEffect }
        }
    }
    // Live views move on by themselves before the city's player cuts them off.
    LaunchedEffect(shown) {
        when (val s = shown) {
            is Shown.Live -> { delay(LIVE_SECONDS * 1000L); advance++ }
            is Shown.Still -> while (true) {
                delay(60_000)
                loadStill(s.camera)?.let { shown = it } // DGT stills refresh every few minutes
            }
            null -> {}
        }
    }
    LaunchedEffect(Unit) { delay(6_000); hintVisible = false }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onKeyEvent {
                val next = it.key == Key.DirectionCenter || it.key == Key.Enter || it.key == Key.DirectionRight
                if (next && it.type == KeyEventType.KeyUp) advance++
                next
            }
            .focusable(),
    ) {
        AnimatedContent(
            targetState = shown,
            contentKey = { it?.camera?.id },
            transitionSpec = { fadeIn(tween(900)) togetherWith fadeOut(tween(900)) },
            label = "camera",
        ) { s ->
            when (s) {
                is Shown.Still -> StillView(s.image)
                is Shown.Live -> LiveView(s.camera)
                null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    SectionLabel(if (cameras == null) "Buscando cámaras…" else "Cargando cámara…")
                }
            }
        }
        // Legibility shade for the corner text.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 0.78f to Color(0x66000000), 1f to Color(0xE6000000))))
        shown?.let { InfoCorner(it, Modifier.align(Alignment.BottomStart).padding(horizontal = 56.dp, vertical = 44.dp)) }
        AnimatedVisibility(hintVisible, Modifier.align(Alignment.TopEnd).padding(horizontal = 56.dp, vertical = 40.dp), exit = fadeOut(tween(1_500))) {
            SectionLabel("OK · siguiente cámara")
        }
    }
}

/** DGT images are small 4:3 shots: show them whole, over a blurred, dimmed copy that fills the screen. */
@Composable
private fun StillView(image: ImageBitmap) {
    Box(Modifier.fillMaxSize()) {
        Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(36.dp))
        Box(Modifier.fillMaxSize().background(Color(0x99000000)))
        Image(image, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
}

/** The city's own public viewer, trimmed down to just the video. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LiveView(camera: Camera) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webChromeClient = object : WebChromeClient() {
                    // The viewer shows an alert when its 20 s are up; we've moved on by then.
                    override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                        result?.confirm(); return true
                    }
                    override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                        android.util.Log.i("Lumen", "Camera viewer: ${message.message()}")
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript(FULLSCREEN_VIDEO_CSS, null)
                        // One health check in the log: is the video actually advancing?
                        view.postDelayed({
                            view.evaluateJavascript(
                                "(function(){var v=document.getElementById('test-video');return v?('t='+v.currentTime.toFixed(1)+' ready='+v.readyState+' '+v.videoWidth+'x'+v.videoHeight):'no video'})()"
                            ) { android.util.Log.i("Lumen", "Camera ${camera.id} video: $it") }
                        }, 8_000)
                    }
                }
                loadUrl(camera.liveUrl)
            }
        },
        onRelease = { it.destroy() },
        modifier = Modifier.fillMaxSize(),
    )
}

private const val FULLSCREEN_VIDEO_CSS = """
(function () {
  var s = document.createElement('style');
  s.textContent = 'html,body{background:#000!important;margin:0!important;overflow:hidden!important}' +
    '.cabecera_logo,#texto,#texto_datos,#escudo{display:none!important}' +
    '#div_video{position:fixed!important;inset:0!important}' +
    'video{position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;object-fit:contain!important;background:#000!important}' +
    'video::-webkit-media-controls{display:none!important}';
  document.head.appendChild(s);
  var v = document.getElementById('test-video'); if (v) v.controls = false;
})();
"""

private val clock = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun InfoCorner(shown: Shown, modifier: Modifier) {
    val camera = shown.camera
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (shown is Shown.Live) {
                Box(Modifier.size(7.dp).background(Color(0xFFFF453A), CircleShape))
                Spacer(Modifier.width(8.dp))
                SectionLabel("En directo · Ayuntamiento de València", color = Color(0xFFFF6B6B))
            } else {
                SectionLabel("DGT · ${camera.road}")
            }
        }
        Text(camera.place, style = Type.title.copy(fontSize = 34.sp, fontWeight = FontWeight(300)), modifier = Modifier.padding(top = 6.dp))
        val detail = when (shown) {
            is Shown.Live -> "Cámara de tráfico municipal"
            is Shown.Still -> listOfNotNull(
                camera.towards.takeIf { it.isNotBlank() }?.let { "Hacia $it" },
                shown.takenAt?.let {
                    val minutes = ((now - it.toEpochMilli()) / 60_000).coerceAtLeast(0)
                    "Imagen de las ${it.atZone(ZoneId.systemDefault()).format(clock)} · " +
                        if (minutes < 1) "ahora mismo" else "hace $minutes min"
                },
            ).joinToString(" · ")
        }
        Text(detail, style = Type.body.copy(color = Tone.secondary))
    }
}

private fun pick(cameras: List<Camera>): Camera {
    val valencia = cameras.filter { it.source == Camera.Source.VALENCIA }
    val dgt = cameras.filter { it.source == Camera.Source.DGT }
    val pool = if (valencia.isNotEmpty() && (dgt.isEmpty() || Math.random() < VALENCIA_SHARE)) valencia else dgt
    return pool.random()
}

/** Downloads a DGT still; null if it's missing or the tiny "no image" placeholder. */
private suspend fun loadStill(camera: Camera): Shown.Still? = withContext(Dispatchers.IO) {
    runCatching {
        val connection = (URL(camera.imageUrl).openConnection() as HttpURLConnection).apply { connectTimeout = 8_000; readTimeout = 10_000 }
        val bytes = connection.inputStream.use { it.readBytes() }
        if (bytes.size < 6_000) return@runCatching null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
        val takenAt = connection.lastModified.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) }
        Shown.Still(camera, bitmap.asImageBitmap().also { it.prepareToDraw() }, takenAt)
    }.getOrNull()
}
