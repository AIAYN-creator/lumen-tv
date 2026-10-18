package dev.lumen.tv.remote

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.graphics.asAndroidBitmap
import dev.lumen.tv.R
import dev.lumen.tv.data.AppRepository
import dev.lumen.tv.dream.ScreensaverActivity
import dev.lumen.tv.media.activeMediaController
import dev.lumen.tv.media.snapshot
import dev.lumen.tv.Config
import dev.lumen.tv.state.HouseState
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import dev.lumen.tv.geo.GeoGame
import dev.lumen.tv.ui.qrBitmap
import dev.lumen.tv.ui.remoteUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * The phone remote. Android lets an accessibility service do what the physical remote does
 * (D-pad, OK, back, home) and type into text fields, so this service is both the "hand" on the
 * TV and the small web server the phone talks to: http://<chromecast-ip>:8765
 *
 * Enabled once with adb (see README).
 */
class RemoteService : AccessibilityService() {

    private var server: HttpServer? = null

    /**
     * The Chromecast's Wi-Fi power saving parks the radio after a second of silence, which made
     * the first tap after a pause take ~1 s. A high-performance lock keeps it awake; the device is
     * mains-powered, so the cost is nil.
     */
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    private val scope = kotlinx.coroutines.MainScope()
    private val alertOverlay by lazy { Overlay(this, Overlay.TOP_END) }
    private val sleepOverlay by lazy { Overlay(this, Overlay.BOTTOM_CENTER) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        HouseState.load(this)
        server = HttpServer(PORT, ::handle, ::onSocketMessage).also { runCatching { it.start() } }
        GoalWatcher(Config.TEAMS.filter { it.espnPath?.startsWith("soccer") == true }, ::showAlert).start(scope)
        scope.launch { runSleepTimer() }
        wifiLock = runCatching {
            @Suppress("DEPRECATION")
            getSystemService(android.net.wifi.WifiManager::class.java)
                .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "lumen-remote")
                .apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
    }

    override fun onDestroy() {
        runCatching { wifiLock?.release() }
        scope.cancel()
        alertOverlay.hide()
        sleepOverlay.hide()
        server?.stop()
        keys.shutdownNow()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** In the sleep timer's last minute, any button on the real remote cancels it (and is swallowed). */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!sleepOverlay.showing) return false
        if (event.action == KeyEvent.ACTION_UP) cancelSleep()
        return true
    }

    // -----------------------------------------------------------------------------------------
    // Sleep timer and alerts

    private suspend fun runSleepTimer() {
        HouseState.sleepAt.collectLatest { sleepAt ->
            sleepOverlay.hide()
            if (sleepAt == null) return@collectLatest
            delay((sleepAt - System.currentTimeMillis() - 60_000L).coerceAtLeast(0))
            sleepOverlay.show { SleepWarningToast(sleepAt) }
            delay((sleepAt - System.currentTimeMillis()).coerceAtLeast(0))
            sleepOverlay.hide()
            HouseState.setSleepTimer(null)
            // Sleeping the Chromecast also sends the TV to standby over HDMI-CEC.
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }
    }

    private fun cancelSleep() {
        HouseState.setSleepTimer(null)
        sleepOverlay.hide()
    }

    private fun showAlert(alert: MatchAlert) {
        alertOverlay.show { MatchToast(alert) }
        scope.launch { delay(9_000); alertOverlay.hide() }
    }

    private fun handle(request: Request): Response = when (request.path) {
        "/", "/index.html" -> asset("remote/index.html", "text/html; charset=utf-8")
        "/manifest.webmanifest" -> asset("remote/manifest.webmanifest", "application/manifest+json")
        "/inter.ttf" -> Response(
            contentType = "font/ttf",
            body = resources.openRawResource(R.font.inter).use { it.readBytes() },
            cacheSeconds = 31_536_000,
        )
        "/icon.png" -> Response(contentType = "image/png", body = appIcon(), cacheSeconds = 86_400)
        "/api/key" -> key(request.param("k").orEmpty())
        "/api/text" -> typeText(request.text)
        "/api/submit" -> submitText()
        "/api/apps" -> apps()
        "/api/banner" -> banner(request.param("pkg").orEmpty())
        "/api/launch" -> launch(request.param("pkg").orEmpty())
        "/api/state" -> state()
        "/api/art" -> art()
        "/api/note" -> note(request)
        "/api/sleep" -> sleep(request)
        "/api/demo-alert" -> demoAlert()
        // GeoGuesser València: TV page, phone page and the game API.
        "/geo" -> asset("remote/geo.html", "text/html; charset=utf-8")
        "/geo/tv" -> asset("remote/geo_tv.html", "text/html; charset=utf-8")
        "/api/geo/state" -> Response.json(GeoGame.state(request.param("id")).toString())
        "/api/geo/join" -> {
            val name = runCatching { JSONObject(request.text).optString("name") }.getOrDefault("").trim().take(16)
            if (name.isEmpty()) Response.text("name", 400) else Response.json(JSONObject().put("id", GeoGame.join(name)).toString())
        }
        "/api/geo/guess" -> {
            val body = runCatching { JSONObject(request.text) }.getOrElse { JSONObject() }
            GeoGame.guess(body.optString("id"), body.optDouble("lat"), body.optDouble("lon"))
            Response.text("ok")
        }
        // Starting a game fetches photos for a few seconds, so it runs off the request thread.
        "/api/geo/advance" -> { Thread { GeoGame.advance(this) }.start(); Response.text("ok") }
        "/api/geo/reset" -> { GeoGame.reset(); Response.text("ok") }
        "/api/geo/leave" -> {
            GeoGame.leave(runCatching { JSONObject(request.text).optString("id") }.getOrDefault(""))
            Response.text("ok")
        }
        // Admin: {"code"} → {"ok"}; kick {"code","name"} removes that player. Wrong code → 403.
        "/api/geo/admin" -> {
            val ok = GeoGame.isAdmin(this, runCatching { JSONObject(request.text).optString("code") }.getOrNull())
            Response.json(JSONObject().put("ok", ok).toString())
        }
        "/api/geo/kick" -> {
            val body = runCatching { JSONObject(request.text) }.getOrElse { JSONObject() }
            if (!GeoGame.isAdmin(this, body.optString("code"))) Response.text("forbidden", 403)
            else { GeoGame.kick(body.optString("name")); Response.text("ok") }
        }
        // Back on the TV: {"exit": true} when the TV app should close (it was already in the lobby).
        "/api/geo/back" -> Response.json(JSONObject().put("exit", !GeoGame.back()).toString())
        "/api/geo/qr" -> Response(contentType = "image/png", body = qrPng((remoteUrl(this) ?: "") + "/geo"))
        else -> Response.notFound
    }

    // -----------------------------------------------------------------------------------------
    // Notes and sleep timer

    /**
     * Notes. POST {"text","author","hours"} adds one (newest first, up to six);
     * POST ?delete=<id> removes one; POST ?clear=1 removes all. Always answers with the list.
     */
    private fun note(request: Request): Response {
        if (request.method == "POST") {
            val delete = request.param("delete")
            when {
                delete != null -> HouseState.removeNote(this, delete)
                request.param("clear") == "1" -> HouseState.clearNotes(this)
                else -> {
                    val body = runCatching { JSONObject(request.text) }.getOrElse { JSONObject() }
                    val text = body.optString("text").trim().take(140)
                    if (text.isNotEmpty()) {
                        HouseState.addNote(this, text, body.optString("author").trim().take(24), body.optDouble("hours", 12.0).coerceIn(0.25, 24.0 * 7))
                    }
                }
            }
        }
        return Response.json(notesJson().toString())
    }

    /** POST ?min=N starts (or replaces) the timer; POST ?min=0 cancels it. */
    private fun sleep(request: Request): Response {
        if (request.method == "POST") {
            val minutes = request.param("min")?.toIntOrNull()?.coerceIn(0, 12 * 60) ?: 0
            if (minutes == 0) main.post { cancelSleep() } else HouseState.setSleepTimer(minutes)
        }
        return Response.json(JSONObject().put("sleepAt", HouseState.sleepAt.value ?: JSONObject.NULL).toString())
    }

    private fun notesJson(): JSONArray = JSONArray().apply { HouseState.currentNotes(this@RemoteService).forEach { put(it.toJson()) } }

    /** Shows a sample goal alert, to preview how it looks. */
    private fun demoAlert(): Response {
        main.post {
            showAlert(
                MatchAlert(
                    MatchAlert.Kind.GOAL, "Gol del Valencia CF · 67'", "Valencia 1 – 0 Villarreal", "Hugo Duro",
                    "https://a.espncdn.com/i/teamlogos/soccer/500/94.png", androidx.compose.ui.graphics.Color(0xFF30D158),
                )
            )
        }
        return Response.text("ok")
    }

    // -----------------------------------------------------------------------------------------
    // Keys

    /**
     * Keys run in order on their own thread and the phone gets its answer straight away:
     * injecting a key can take a while if the app in front is busy, and the phone shouldn't wait.
     */
    private val keys = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** WebSocket messages from the phone: "k:<key>" for a key, "ping" as a heartbeat. */
    private fun onSocketMessage(message: String) {
        if (message.startsWith("k:")) key(message.removePrefix("k:"))
    }

    private fun key(name: String): Response {
        if (name !in KEYS) return Response.text("unsupported", 400)
        keys.execute { runKey(name) }
        return Response.text("ok")
    }

    private fun runKey(name: String) {
        val started = SystemClock.uptimeMillis()
        when (name) {
            "up" -> performGlobalAction(GLOBAL_ACTION_DPAD_UP)
            "down" -> performGlobalAction(GLOBAL_ACTION_DPAD_DOWN)
            "left" -> performGlobalAction(GLOBAL_ACTION_DPAD_LEFT)
            "right" -> performGlobalAction(GLOBAL_ACTION_DPAD_RIGHT)
            "ok" -> performGlobalAction(GLOBAL_ACTION_DPAD_CENTER)
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "power" -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            "playpause" -> media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "next" -> media(KeyEvent.KEYCODE_MEDIA_NEXT)
            "previous" -> media(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "volup" -> volume(AudioManager.ADJUST_RAISE)
            "voldown" -> volume(AudioManager.ADJUST_LOWER)
            "mute" -> volume(AudioManager.ADJUST_TOGGLE_MUTE)
            "nightpreview" -> main.post {
                runCatching {
                    startActivity(Intent(this, ScreensaverActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(ScreensaverActivity.EXTRA_NIGHT, true))
                }
            }
            "screensaver" -> main.post {
                runCatching { startActivity(Intent(this, ScreensaverActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        val took = SystemClock.uptimeMillis() - started
        if (took > 300) android.util.Log.i("Lumen", "Remote key $name took ${took}ms")
    }

    /** Media keys go to the active session (Spotify, YouTube...) when there is one. */
    private fun media(keyCode: Int): Boolean {
        val controller = activeMediaController(this)
        if (controller != null) {
            val controls = controller.transportControls
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> controls.skipToNext()
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> controls.skipToPrevious()
                else -> if (controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
            }
            return true
        }
        val audio = getSystemService(AudioManager::class.java)
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        return true
    }

    /** On the Chromecast this is forwarded to the TV over HDMI-CEC. */
    private fun volume(direction: Int): Boolean {
        getSystemService(AudioManager::class.java)
            .adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        return true
    }

    // -----------------------------------------------------------------------------------------
    // Text

    private fun focusedField(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }

    private fun typeText(text: String): Response {
        val field = focusedField() ?: return Response.text("no field", 409)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return Response.text(if (ok) "ok" else "failed", if (ok) 200 else 500)
    }

    private fun submitText(): Response {
        val field = focusedField() ?: return Response.text("no field", 409)
        val ok = field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        return Response.text(if (ok) "ok" else "failed", if (ok) 200 else 500)
    }

    // -----------------------------------------------------------------------------------------
    // Apps

    private fun apps(): Response {
        val apps = runBlocking { AppRepository.load(this@RemoteService) }
        val json = JSONArray()
        apps.forEach { json.put(JSONObject().put("pkg", it.id).put("label", it.label)) }
        return Response.json(json.toString())
    }

    private fun banner(pkg: String): Response {
        val app = runBlocking { AppRepository.load(this@RemoteService) }.firstOrNull { it.id == pkg }
            ?: return Response.notFound
        val bitmap = (app.banner ?: app.icon)?.asAndroidBitmap() ?: return Response.notFound
        return Response(contentType = "image/png", body = bitmap.png(), cacheSeconds = 3_600)
    }

    private fun launch(pkg: String): Response {
        val app = runBlocking { AppRepository.load(this@RemoteService) }.firstOrNull { it.id == pkg }
            ?: return Response.notFound
        main.post { runCatching { startActivity(app.launchIntent) } }
        return Response.text("ok")
    }

    // -----------------------------------------------------------------------------------------
    // Now playing

    private fun state(): Response {
        val media = activeMediaController(this)?.snapshot(this)
        val json = JSONObject()
            .put("notes", notesJson())
            .put("sleepAt", HouseState.sleepAt.value ?: JSONObject.NULL)
            .put("now", System.currentTimeMillis())
        if (media != null) {
            json.put(
                "media",
                JSONObject()
                    .put("app", media.app)
                    .put("title", media.title)
                    .put("artist", media.artist)
                    .put("playing", media.playing)
                    .put("duration", media.durationMs)
                    // Position extrapolated to now, so the phone can animate the bar itself.
                    .put(
                        "position",
                        if (media.playing) media.positionMs + ((SystemClock.elapsedRealtime() - media.positionAt) * media.speed).toLong()
                        else media.positionMs,
                    )
                    .put("art", if (media.art != null) "/api/art?t=${media.title.hashCode()}" else media.artUri ?: JSONObject.NULL)
            )
        }
        return Response.json(json.toString())
    }

    private fun art(): Response {
        val bitmap = activeMediaController(this)?.snapshot(this)?.art ?: return Response.notFound
        return Response(contentType = "image/png", body = bitmap.png(), cacheSeconds = 3_600)
    }

    // -----------------------------------------------------------------------------------------
    // Helpers

    private fun asset(path: String, type: String) =
        Response(contentType = type, body = assets.open(path).use { it.readBytes() })

    private fun qrPng(text: String): ByteArray = qrBitmap(text, 480).png()

    private fun Bitmap.png(): ByteArray =
        ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    /** Home-screen icon for the phone: the aurora glow behind a thin white ring. */
    private fun appIcon(): ByteArray {
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF070B1E.toInt())
        val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        glow.shader = RadialGradient(size * 0.3f, size * 0.3f, size * 0.8f, 0xFF3C3489.toInt(), 0x003C3489, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), glow)
        glow.shader = RadialGradient(size * 0.8f, size * 0.75f, size * 0.6f, 0xAA1D9E75.toInt(), 0x001D9E75, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), glow)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = size * 0.022f
            color = 0xFFFFFFFF.toInt()
        }
        canvas.drawCircle(size / 2f, size / 2f, size * 0.22f, ring)
        return bitmap.png()
    }

    companion object {
        const val PORT = 8765
        private val KEYS = setOf(
            "up", "down", "left", "right", "ok", "back", "home", "power", "nightpreview",
            "playpause", "next", "previous", "volup", "voldown", "mute", "screensaver",
        )
    }
}
