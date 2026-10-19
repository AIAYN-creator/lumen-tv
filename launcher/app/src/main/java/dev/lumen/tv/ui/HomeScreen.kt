package dev.lumen.tv.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.lumen.tv.Config
import dev.lumen.tv.data.AppRepository
import dev.lumen.tv.data.TvApp
import dev.lumen.tv.dream.ScreensaverActivity
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.addPathNodes
import kotlinx.coroutines.launch
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import dev.lumen.tv.media.rememberNowPlaying
import dev.lumen.tv.state.HouseState

private val SIDE_PADDING = 48.dp

/**
 * Dark mode on the home screen: no wallpaper at all (black costs the GPU nothing and the
 * bitmap isn't even decoded), and one veil over everything else. The veil multiplies by the
 * screensaver's warm tone and then dims, so white type lands on the same colour N2 uses and
 * the app banners — the brightest thing on screen at 3 a.m. — come down with it.
 *
 * Two flat rectangles, no gradients and no offscreen layer: on this hardware that is free.
 */
private val DarkTint = Color(0xFFFFE2C2)
private const val DARK_DIM = 0.42f

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<TvApp>>(emptyList()) }

    // Reload whenever we come back to the home screen, so new installs show up.
    LifecycleResumeEffect(Unit) {
        scope.launch { apps = AppRepository.load(context) }
        onPauseOrDispose {}
    }

    var showQr by remember { mutableStateOf(false) }
    val dark = rememberNight()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!dark) StaticWallpaper(Modifier.fillMaxSize(), intervalMillis = 10 * 60_000L)
        // Rows run edge to edge (with content padding) so focused tiles can grow without being clipped.
        Column(Modifier.fillMaxSize().padding(vertical = 28.dp)) {
            Header(context, dark, onShowQr = { showQr = true }, modifier = Modifier.padding(horizontal = SIDE_PADDING))
            Spacer(Modifier.weight(1f))
            AppRows(apps, onLaunch = { context.launch(it) })
        }
        if (dark) {
            Box(
                Modifier.fillMaxSize().drawBehind {
                    drawRect(DarkTint, blendMode = BlendMode.Multiply)
                    drawRect(Color.Black.copy(alpha = DARK_DIM))
                },
            )
        }
        if (showQr) {
            // Any key closes the QR sheet.
            val closer = remember { FocusRequester() }
            Box(
                Modifier.fillMaxSize()
                    .focusRequester(closer)
                    .onKeyEvent { if (it.type == KeyEventType.KeyUp) showQr = false; true }
                    .focusable()
            ) { RemoteQrSheet() }
            LaunchedEffect(Unit) { closer.requestFocus() }
        }
    }
}

/**
 * Two parts at one fixed height: the dynamic hero on the left (whatever matters most right now,
 * plus up to two secondary lines) and a quiet column on the right with the clock, weather,
 * warnings, sleep timer and buttons.
 */
@Composable
private fun Header(context: Context, dark: Boolean, onShowQr: () -> Unit, modifier: Modifier = Modifier) {
    val now = rememberNow()
    val weather = rememberWeather()
    val alerts = rememberAlerts()
    val notes = rememberNotes()
    val media = rememberNowPlaying()
    val sports = rememberSports()
    val sleepMinutes = rememberSleepMinutes()

    Row(modifier.fillMaxWidth().height(HEADER_HEIGHT)) {
        Hero(heroItems(notes, media, sports), weather, now, Modifier.weight(1f).padding(top = 4.dp, end = 40.dp))
        VerticalHairline()
        Column(
            Modifier.width(300.dp).fillMaxHeight().padding(start = 32.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RoundButton(PanelIcon, "Salvapantallas") { context.openScreensaver() }
                // Switches the look and leaves you here. The screensaver has its own button;
                // before the home had a dark mode, turning this on jumped straight to it.
                RoundButton(MoonIcon, if (dark) "Quitar el modo oscuro" else "Modo oscuro", active = dark) {
                    HouseState.toggleNight(context)
                }
                RoundButton(PhoneIcon, "Mando en el móvil", onClick = onShowQr)
                RoundButton(Icons.Filled.AccountCircle, "Cuentas y perfiles") { context.openAccounts() }
                RoundButton(Icons.Filled.Settings, "Ajustes") { context.openSettings() }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(now.timeText(), style = Type.clock)
                Text(now.dateText(), style = Type.body.copy(color = Tone.secondary))
            }
            if (weather != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${weather.description} · ${weather.max}° / ${weather.min}°", style = Type.caption, modifier = Modifier.padding(end = 12.dp))
                    WeatherGlyph(weather.sky, weather.isDay, 26.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("${weather.temperature}°", style = Type.temperature.copy(fontSize = 30.sp, lineHeight = 32.sp))
                }
            } else {
                Spacer(Modifier.height(32.dp))
            }
            // The timer replaces the warning line while it runs; both are the same two-line block.
            if (sleepMinutes != null) {
                Column(horizontalAlignment = Alignment.End) {
                    SectionLabel("Temporizador", color = Tone.secondary)
                    Text("La tele se apaga en $sleepMinutes min", style = Type.caption.copy(color = Tone.tertiary), modifier = Modifier.padding(top = 2.dp))
                }
            } else {
                AlertStatus(alerts)
            }
        }
    }
}

private val HEADER_HEIGHT = 216.dp

/** Phone outline, for the "remote on your phone" (QR) button. */
private val PhoneIcon: ImageVector = ImageVector.Builder("Phone", 24.dp, 24.dp, 24f, 24f)
    .addPath(
        addPathNodes("M8,2.5h8a1.8,1.8 0 0 1 1.8,1.8v15.4a1.8,1.8 0 0 1 -1.8,1.8h-8a1.8,1.8 0 0 1 -1.8,-1.8v-15.4a1.8,1.8 0 0 1 1.8,-1.8zM10.5,18.5h3"),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.6f,
        strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
    )
    .build()

/** Small circular icon button, Apple TV control-centre style: outlined at rest, solid white on focus. */
@Composable
private fun RoundButton(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(36.dp),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.15f),
        border = ClickableSurfaceDefaults.border(border = Border(BorderStroke(1.dp, Tone.hairline), shape = CircleShape)),
        colors = ClickableSurfaceDefaults.colors(
            // Switched on (night mode): a soft fill, so it reads as on without stealing focus.
            containerColor = if (active) Color.White.copy(alpha = 0.22f) else Color.Transparent,
            contentColor = if (active) Color.White else Tone.secondary,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.align(Alignment.Center).size(19.dp))
    }
}

@Composable
private fun AppRows(apps: List<TvApp>, onLaunch: (TvApp) -> Unit) {
    if (apps.isEmpty()) return
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    val favorites = apps.filter { it.id in Config.FAVORITES }.ifEmpty { apps.take(4) }
    val rest = apps - favorites.toSet()

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            contentPadding = PaddingValues(vertical = 14.dp, horizontal = SIDE_PADDING),
        ) {
            items(favorites, key = { it.id }) { app ->
                val tileModifier = if (app == favorites.first()) Modifier.focusRequester(firstFocus) else Modifier
                AppTile(app, 152.dp, tileModifier) { onLaunch(app) }
            }
        }
        if (rest.isNotEmpty()) {
            SectionLabel("Todas las apps", Modifier.padding(start = SIDE_PADDING))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 12.dp, horizontal = SIDE_PADDING),
            ) {
                items(rest, key = { it.id }) { app -> AppTile(app, 128.dp) { onLaunch(app) } }
            }
        }
    }
}

/**
 * App tile, hand-rolled rather than tv-material's Surface: the focus animation (lift, border,
 * name) is a single Animatable read only in the layer/draw phases, so moving through a row
 * recomposes nothing per frame and draws no shadow layers. That matters on the Chromecast HD.
 */
@Composable
private fun AppTile(app: TvApp, width: Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val focus = remember { Animatable(0f) }
    LaunchedEffect(focused) { focus.animateTo(if (focused) 1f else 0f, tween(140, easing = FastOutSlowInEasing)) }
    val corner = 14.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier
                .width(width)
                .height(width * 9 / 16)
                .graphicsLayer {
                    val scale = 1f + 0.1f * focus.value
                    scaleX = scale
                    scaleY = scale
                }
                .drawWithContent {
                    val radius = CornerRadius(corner.toPx())
                    drawRoundRect(Color.White.copy(alpha = 0.12f), cornerRadius = radius)
                    clipPath(Path().apply { addRoundRect(RoundRect(size.toRect(), radius)) }) { this@drawWithContent.drawContent() }
                    val p = focus.value
                    if (p > 0f) {
                        val stroke = 2.dp.toPx()
                        drawRoundRect(
                            Color.White.copy(alpha = p),
                            topLeft = Offset(stroke / 2, stroke / 2),
                            size = Size(size.width - stroke, size.height - stroke),
                            cornerRadius = CornerRadius(radius.x - stroke / 2),
                            style = Stroke(stroke),
                        )
                    }
                }
                .onFocusChanged { focused = it.isFocused }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        ) {
            if (app.banner != null) {
                Image(app.banner, app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Column(
                    Modifier.fillMaxSize().padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    app.icon?.let { Image(it, null, Modifier.size(width / 4)) }
                    Text(app.label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White)
                }
            }
        }
        Text(
            app.label,
            style = Type.bodyStrong.copy(fontSize = 13.sp),
            maxLines = 1,
            textAlign = TextAlign.Center,
            // Alpha is read in the layer phase, so the fade doesn't recompose the tile every frame.
            modifier = Modifier.padding(top = 12.dp).width(width).graphicsLayer { alpha = focus.value },
        )
    }
}

private fun Context.launch(app: TvApp) {
    runCatching { startActivity(app.launchIntent) }
}

private fun Context.openSettings() {
    runCatching { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun Context.openScreensaver() {
    // Own task, so pressing Home always lands on the home screen rather than back on this.
    runCatching { startActivity(Intent(this, ScreensaverActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** The screensaver is a dashboard: a screen with a small grid, drawn like the phone icon. */
private val PanelIcon: ImageVector = ImageVector.Builder("Panel", 24.dp, 24.dp, 24f, 24f)
    .addPath(
        addPathNodes("M4.8,4.5h14.4a1.8,1.8 0 0 1 1.8,1.8v9.4a1.8,1.8 0 0 1 -1.8,1.8h-14.4a1.8,1.8 0 0 1 -1.8,-1.8v-9.4a1.8,1.8 0 0 1 1.8,-1.8zM12,7.5v7M6.5,11h11M9,20.5h6"),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.6f,
        strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
    )
    .build()

/** Material "dark mode" crescent, for night mode; not part of the core icon set. */
private val MoonIcon: ImageVector = ImageVector.Builder("Moon", 24.dp, 24.dp, 24f, 24f)
    .addPath(
        addPathNodes(
            "M12,3c-4.97,0 -9,4.03 -9,9s4.03,9 9,9s9,-4.03 9,-9c0,-0.46 -0.04,-0.92 -0.1,-1.36" +
                "c-0.98,1.37 -2.58,2.26 -4.4,2.26c-2.98,0 -5.4,-2.42 -5.4,-5.4c0,-1.81 0.89,-3.42 2.26,-4.4" +
                "C12.92,3.04 12.46,3 12,3L12,3z"
        ),
        fill = SolidColor(Color.Black),
    )
    .build()

/** Google TV's "Accounts and profiles" screen. */
private fun Context.openAccounts() {
    runCatching { startActivity(Intent(Settings.ACTION_SYNC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
