package dev.lumen.tv.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.lumen.tv.Config
import dev.lumen.tv.data.AlertRepository
import dev.lumen.tv.data.Arrival
import dev.lumen.tv.data.BusRepository
import dev.lumen.tv.data.BusStop
import dev.lumen.tv.data.MetroRepository
import dev.lumen.tv.data.MetroStation
import dev.lumen.tv.data.SportsRepository
import dev.lumen.tv.data.TeamCard
import dev.lumen.tv.data.Weather
import dev.lumen.tv.data.WeatherAlert
import dev.lumen.tv.data.WeatherRepository
import dev.lumen.tv.data.WallpaperRepository
import kotlinx.coroutines.delay
import androidx.compose.runtime.collectAsState
import dev.lumen.tv.state.HouseState
import dev.lumen.tv.state.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private val spanish: Locale = Locale.forLanguageTag("es-ES")
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dateFormat = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", spanish)

fun LocalDateTime.timeText(): String = format(timeFormat)
fun LocalDateTime.dateText(): String = format(dateFormat).replaceFirstChar { it.titlecase(spanish) }

fun LocalDateTime.greeting(): String = when (hour) {
    in 0..5 -> "Buenas noches"
    in 6..13 -> "Buenos días"
    in 14..20 -> "Buenas tardes"
    else -> "Buenas noches" // 21-23
}

/** Current time, updated at the start of every minute (nothing on screen shows seconds). */
@Composable
fun rememberNow(): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(60_000L - System.currentTimeMillis() % 60_000L + 20L)
        }
    }
    return now
}

/**
 * Runs [fetch] every [intervalMillis] while the screen is visible (paused while another app is
 * in front), retrying after [retryMillis] on failure. A failure keeps showing the last value
 * until it is older than [maxAgeMillis], so a network blip doesn't blank the screen but stale
 * train times don't linger either.
 */
@Composable
fun <T : Any> rememberPolled(
    intervalMillis: Long,
    retryMillis: Long = 60_000L,
    maxAgeMillis: Long = Long.MAX_VALUE,
    key: Any? = null,
    fetch: suspend () -> T?,
): T? {
    var value by remember(key) { mutableStateOf<T?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, key) {
        var fetchedAt = 0L
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val fresh = fetch()
                val now = System.currentTimeMillis()
                if (fresh != null) {
                    value = fresh
                    fetchedAt = now
                } else if (now - fetchedAt > maxAgeMillis) {
                    value = null
                }
                delay(if (fresh != null) intervalMillis else retryMillis)
            }
        }
    }
    return value
}

@Composable
fun rememberWeather(): Weather? =
    rememberPolled(30 * 60_000L) { WeatherRepository.fetch(Config.LATITUDE, Config.LONGITUDE) }

@Composable
fun rememberAlerts(): List<WeatherAlert>? =
    rememberPolled(15 * 60_000L) { AlertRepository.fetch(Config.ALERT_AREA) }

@Composable
fun rememberMetro(station: MetroStation): List<Arrival>? =
    rememberPolled(30_000L, retryMillis = 10_000L, maxAgeMillis = 120_000L, key = station) { MetroRepository.fetch(station.id) }

@Composable
fun rememberBus(stop: BusStop): List<Arrival>? =
    rememberPolled(30_000L, retryMillis = 10_000L, maxAgeMillis = 120_000L, key = stop) { BusRepository.fetch(stop) }

/** Live family notes, newest first; expired ones drop out (checked every minute). */
@Composable
fun rememberNotes(): List<Note> {
    val context = LocalContext.current
    remember { HouseState.load(context) }
    val notes by HouseState.notes.collectAsState()
    val now = rememberNow()
    return remember(notes, now) { notes.filterNot { it.expired } }
}

/** Minutes left on the sleep timer (rounded up), or null when there's none. */
@Composable
fun rememberSleepMinutes(): Int? {
    val sleepAt by HouseState.sleepAt.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sleepAt) {
        while (sleepAt != null) {
            now = System.currentTimeMillis()
            delay(5_000)
        }
    }
    return sleepAt?.let { ((it - now + 59_999) / 60_000).toInt().coerceAtLeast(0) }
}

fun LocalDateTime.isNight(): Boolean =
    if (Config.NIGHT_START <= Config.NIGHT_END) hour in Config.NIGHT_START until Config.NIGHT_END
    else hour >= Config.NIGHT_START || hour < Config.NIGHT_END

/** Refreshed every minute; fixtures are cached inside the repository, so this mostly polls live scores. */
@Composable
fun rememberSports(): List<TeamCard>? =
    rememberPolled(60_000L) { SportsRepository.fetch(Config.TEAMS) }

/**
 * Screensaver background: the user's photos (rotating every [intervalMillis], with a slow
 * Ken Burns pan) or, when there are none, the drifting aurora.
 *
 * The screensaver stays up for hours, so this is built to cost almost nothing: the aurora is
 * pre-rendered at half resolution once a second (its drift is far too slow to need more), and
 * the Ken Burns pan is stepped ten times a second rather than every frame. Animating the
 * gradients per frame kept the Chromecast's GPU saturated around the clock.
 */
@Composable
fun Wallpaper(
    modifier: Modifier = Modifier,
    intervalMillis: Long,
    kenBurns: Boolean,
    auroraPeriod: Int = 120_000,
) {
    val context = LocalContext.current
    var photo by remember { mutableStateOf<ImageBitmap?>(null) }
    var hasPhotos by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        var index = -1
        while (true) {
            val files = WallpaperRepository.files(context)
            hasPhotos = files.isNotEmpty()
            if (files.isEmpty()) {
                photo = null
            } else {
                index = if (index < 0) files.indices.random() else (index + 1) % files.size
                WallpaperRepository.decode(files[index])?.let { photo = it }
            }
            delay(intervalMillis)
        }
    }
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        if (!hasPhotos) {
            var aurora by remember { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(width, height) {
                while (true) {
                    val phase = (2 * Math.PI * (System.currentTimeMillis() % auroraPeriod) / auroraPeriod).toFloat()
                    aurora = withContext(Dispatchers.Default) { bake(width / 2, height / 2, null, phase) }
                    delay(1_000)
                }
            }
            aurora?.let {
                Image(it, null, contentScale = ContentScale.FillBounds, filterQuality = androidx.compose.ui.graphics.FilterQuality.Low, modifier = Modifier.fillMaxSize())
            }
        } else {
            Crossfade(targetState = photo, animationSpec = tween(2_500), label = "wallpaper") { image ->
                if (image != null) PhotoBackground(image, kenBurns)
            }
            // Scrim so text stays readable on bright photos.
            Box(Modifier.fillMaxSize().background(scrim))
        }
    }
}

@Composable
private fun PhotoBackground(photo: ImageBitmap, kenBurns: Boolean) {
    // 0 → 1 → 0 over 90 s, stepped at 10 fps: smooth to the eye at this speed, cheap for the GPU.
    var progress by remember { mutableStateOf(0f) }
    if (kenBurns) {
        LaunchedEffect(Unit) {
            val start = System.currentTimeMillis()
            while (true) {
                val t = ((System.currentTimeMillis() - start) % 90_000) / 45_000f
                progress = if (t <= 1f) t else 2f - t
                delay(100)
            }
        }
    }
    Image(
        photo,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().graphicsLayer {
            if (kenBurns) {
                scaleX = 1.06f + 0.08f * progress
                scaleY = scaleX
                translationX = size.width * 0.03f * (progress - 0.5f)
            }
        },
    )
}

/** Default wallpaper: aurora glows over a deep night sky; [phase] (radians) sets their drift. */
private fun DrawScope.drawAurora(phase: Float) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF070B1E), Color(0xFF0B1030), Color(0xFF05060F))))
    glow(Color(0xFF3C3489), 0.25f + 0.08f * sin(phase), 0.30f + 0.06f * cos(phase), 0.75f)
    glow(Color(0xFF1D9E75), 0.75f + 0.07f * cos(phase * 2), 0.35f + 0.08f * sin(phase), 0.60f, alpha = 0.55f)
    glow(Color(0xFFD4537E), 0.55f + 0.10f * sin(phase + 2f), 0.85f + 0.05f * cos(phase), 0.55f, alpha = 0.35f)
}

private val scrim = Brush.verticalGradient(
    0f to Color(0x80000000),
    0.35f to Color(0x26000000),
    0.6f to Color(0x26000000),
    1f to Color(0xD9000000),
)

/**
 * Home-screen background, pre-rendered into one bitmap: photo (or aurora) plus scrim. Full-screen
 * gradients are expensive for the Chromecast HD's GPU and were being repainted on every frame of
 * the focus animations; a single bitmap costs one texture copy per frame instead.
 */
@Composable
fun StaticWallpaper(modifier: Modifier = Modifier, intervalMillis: Long) {
    val context = LocalContext.current
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        var baked by remember { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(width, height) {
            var index = -1
            while (true) {
                val files = WallpaperRepository.files(context)
                val photo = if (files.isEmpty()) null else {
                    index = if (index < 0) files.indices.random() else (index + 1) % files.size
                    WallpaperRepository.decode(files[index])
                }
                // The aurora is all soft gradients, so half resolution looks identical and the
                // GPU reads a quarter of the memory each frame. Photos keep full resolution.
                baked = withContext(Dispatchers.Default) {
                    if (photo == null) bake(width / 2, height / 2, null, HOME_AURORA_PHASE) else bake(width, height, photo, 0f)
                }
                delay(intervalMillis)
            }
        }
        Crossfade(targetState = baked, animationSpec = tween(1_500), label = "wallpaper") { image ->
            if (image != null) {
                Image(
                    image,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private const val HOME_AURORA_PHASE = 0.6f

/** Pre-renders the background (photo centre-cropped, or the aurora at [phase]) plus the scrim. */
private fun bake(width: Int, height: Int, photo: ImageBitmap?, phase: Float): ImageBitmap {
    val bitmap = ImageBitmap(width, height)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bitmap), Size(width.toFloat(), height.toFloat())) {
        if (photo == null) {
            drawAurora(phase)
        } else {
            // Centre-crop the photo to the screen.
            val scale = maxOf(width / photo.width.toFloat(), height / photo.height.toFloat())
            val srcWidth = (width / scale).toInt()
            val srcHeight = (height / scale).toInt()
            drawImage(
                photo,
                srcOffset = IntOffset((photo.width - srcWidth) / 2, (photo.height - srcHeight) / 2),
                srcSize = IntSize(srcWidth, srcHeight),
                dstSize = IntSize(width, height),
            )
        }
        drawRect(scrim)
    }
    return bitmap.also { it.prepareToDraw() }
}

private fun DrawScope.glow(color: Color, x: Float, y: Float, radius: Float, alpha: Float = 0.7f) {
    val center = Offset(size.width * x, size.height * y)
    val r = size.maxDimension * radius
    drawCircle(
        brush = Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), center, r),
        radius = r,
        center = center,
    )
}
