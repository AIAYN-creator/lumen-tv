package dev.lumen.tv.cameras

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.annotation.Keep
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
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.platform.ComposeView
import androidx.tv.material3.Text
import dev.lumen.tv.ui.LumenTheme
import dev.lumen.tv.ui.SectionLabel
import dev.lumen.tv.ui.Tone
import dev.lumen.tv.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 *
 * The live WebView is a plain view under a see-through Compose layer, not an AndroidView
 * inside it: there, behind Compose's opaque background and layers, the video played but the
 * screen stayed black.
 */
class CamerasActivity : ComponentActivity() {
    private lateinit var live: LiveCamera

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        live = LiveCamera(this)
        val screen = ComposeView(this).apply { setContent { LumenTheme { CamerasScreen(live) } } }
        // Android TV outlines the focused view in yellow; the screen is one big focus target.
        screen.viewTreeObserver.addOnGlobalFocusChangeListener { _, focused -> focused?.defaultFocusHighlightEnabled = false }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(live.view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(screen, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        })
        window.setBackgroundDrawable(null)
    }

    // Leaving (Home, another app) ends the session: no streams running in the background.
    override fun onStop() {
        super.onStop()
        finish()
    }

    override fun onDestroy() {
        live.destroy()
        super.onDestroy()
    }
}

/** València city cameras are live but the city's viewer ends each view 20 s after it opens. */
private const val LIVE_SECONDS = 18
/** A camera that hasn't started playing by then is probably offline: try another. */
private const val LIVE_START_TIMEOUT = 8_000L
/** Share of picks that go to the (live) València cameras. */
private const val VALENCIA_SHARE = 0.25

@Composable
private fun CamerasScreen(live: LiveCamera) {
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
            val next = if (camera.source == Camera.Source.VALENCIA) {
                if (live.start(camera)) Shown.Live(camera) else null
            } else {
                loadStill(camera)
            }
            if (next != null) {
                if (next is Shown.Still) live.stop()
                shown = next
                return@LaunchedEffect
            }
        }
    }
    // Live views move on by themselves before the city's player cuts them off.
    LaunchedEffect(shown) {
        when (val s = shown) {
            is Shown.Live -> {
                delay((live.openedAt + LIVE_SECONDS * 1000L - SystemClock.uptimeMillis()).coerceAtLeast(0))
                advance++
            }
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
            // See-through while live, so the WebView underneath shows.
            .background(if (shown is Shown.Live) Color.Transparent else Color.Black)
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
                // The video is the WebView underneath; while the next one connects, say so.
                is Shown.Live -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (!live.playing) SectionLabel("Conectando con la cámara…")
                }
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

/**
 * The city's own public viewer, trimmed down to just the video, in one WebView that is reused
 * for every live camera. It stays transparent until the video is really playing, so the city
 * page and the connection never show.
 */
@SuppressLint("SetJavaScriptEnabled")
class LiveCamera(context: Context) {
    /** True once the current camera's video is advancing on screen. */
    var playing by mutableStateOf(false)
        private set
    /** When the current camera's viewer was opened (uptime ms); its 20 s run from here. */
    var openedAt = 0L
        private set
    private var current: Camera? = null

    val view: WebView = WebView(context).apply {
        setBackgroundColor(android.graphics.Color.BLACK)
        alpha = 0f
        // Keys belong to the Compose layer (OK = next camera).
        isFocusable = false
        isFocusableInTouchMode = false
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        addJavascriptInterface(Bridge(), "Lumen")
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
                val camera = current?.takeIf { it.liveUrl == url } ?: return
                view.evaluateJavascript(FULLSCREEN_VIDEO_CSS, null)
                view.evaluateJavascript(PLAYBACK_WATCHDOG, null)
                // Health check in the log: is the video advancing, and how often did the watchdog step in?
                view.postDelayed({
                    if (current == camera) view.evaluateJavascript(
                        "(function(){var v=document.getElementById('test-video');if(!v)return 'no video';var b=v.buffered;" +
                            "return 't='+v.currentTime.toFixed(1)+' buffered='+(b.length?b.end(b.length-1).toFixed(1):'-')+' paused='+v.paused+" +
                            "' ready='+v.readyState+' rate='+v.playbackRate.toFixed(2)+' rescues='+(window.lumenRescues||0)})()"
                    ) { android.util.Log.i("Lumen", "Camera ${camera.id} video: $it") }
                }, 12_000)
            }
        }
    }

    /** Opens [camera] and waits for its video to play; false (and stopped) if it doesn't start. */
    suspend fun start(camera: Camera): Boolean {
        current = camera
        playing = false
        openedAt = SystemClock.uptimeMillis()
        view.animate().cancel()
        view.alpha = 0f
        view.loadUrl(camera.liveUrl)
        val started = withTimeoutOrNull(LIVE_START_TIMEOUT) { snapshotFlow { playing }.first { it } } != null
        if (!started) {
            android.util.Log.i("Lumen", "Camera ${camera.id} didn't start; skipping")
            stop()
        }
        return started
    }

    fun stop() {
        current = null
        playing = false
        view.animate().cancel()
        view.alpha = 0f
        view.loadUrl("about:blank")
    }

    fun destroy() = view.destroy()

    // Kept by name: R8 would otherwise rename what the page's JavaScript calls.
    @Keep
    private inner class Bridge {
        /** Called by the watchdog once the video has really started; [url] guards against a late call. */
        @Keep
        @JavascriptInterface
        fun playing(url: String) {
            view.post {
                if (current?.liveUrl != url || playing) return@post
                playing = true
                view.animate().alpha(1f).setDuration(600).start()
            }
        }
    }
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

/**
 * Shows the frames through a canvas (see inside). The city's player sits 0.04 s behind the live edge, too tight for this hardware: the video
 * runs dry within a second and Chromium releases the decoder, leaving a frozen frame. This
 * keeps more buffer, follows the stream's real pace (some cameras deliver ~0.75 s of video
 * per second, so the clock would outrun the data) and restarts playback if it stops with data
 * waiting (the player only calls play() once).
 */
private const val PLAYBACK_WATCHDOG = """
(function () {
  var v = document.getElementById('test-video');
  if (!v || window.lumenWatchdog) return;
  window.lumenWatchdog = true;
  window.lumenRescues = 0;
  try { if (window.stream && stream.jitter < 0.5) stream.jitter = 0.5; } catch (e) {}
  // Frames are copied to a canvas. The <video> itself goes to a hardware plane under the app's
  // window and only shows where nothing above it is opaque; the canvas is ordinary page content.
  var c = document.createElement('canvas');
  c.style.cssText = 'position:fixed;inset:0;width:100vw;height:100vh;object-fit:contain;z-index:9;background:#000';
  document.body.appendChild(c);
  var g = c.getContext('2d', { alpha: false });
  var drawn = -1;
  (function paint() {
    if (v.videoWidth && (c.width !== v.videoWidth || c.height !== v.videoHeight)) { c.width = v.videoWidth; c.height = v.videoHeight; drawn = -1; }
    // Only when there's a new frame: the screen refreshes at 60 Hz, the cameras send ~25 fps.
    if (v.readyState >= 2 && v.currentTime !== drawn) { g.drawImage(v, 0, 0, c.width, c.height); drawn = v.currentTime; }
    requestAnimationFrame(paint);
  })();
  var last = -1, stuck = 0, told = false;
  setInterval(function () {
    var b = v.buffered;
    if (!b.length) return;
    var end = b.end(b.length - 1), lag = end - v.currentTime;
    // Tell the app once frames are really moving, so it can show the view.
    if (!told && v.currentTime > 0.3 && !v.paused) {
      told = true;
      try { Lumen.playing(location.href); } catch (e) {}
    }
    // Hold about 0.6 s behind what has arrived: slow down when short, catch up when long.
    var rate = Math.max(0.5, Math.min(1.3, 1 + (lag - 0.6) * 0.8));
    if (Math.abs(rate - v.playbackRate) > 0.03) v.playbackRate = rate;
    // Data waiting but no progress for a second: restart it.
    var moving = v.currentTime !== last;
    last = v.currentTime;
    if (lag > 0.2 && (v.paused || !moving)) {
      if (++stuck < 2) return;
      stuck = 0;
      window.lumenRescues++;
      if (lag > 2) v.currentTime = end - 0.6;
      var p = v.play();
      if (p) p.catch(function () {});
    } else {
      stuck = 0;
    }
  }, 500);
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
