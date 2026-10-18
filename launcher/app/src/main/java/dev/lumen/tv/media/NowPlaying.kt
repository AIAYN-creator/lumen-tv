package dev.lumen.tv.media

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Exists only so Android lets Lumen read active media sessions (Spotify, YouTube Music...).
 * Enabled once with: adb shell cmd notification allow_listener dev.lumen.tv/dev.lumen.tv.media.MediaListener
 */
class MediaListener : NotificationListenerService()

data class NowPlaying(
    val app: String,
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val artUri: String?,
    val durationMs: Long,
    val positionMs: Long,
    /** When [positionMs] was sampled (SystemClock.elapsedRealtime), to extrapolate while playing. */
    val positionAt: Long,
    val speed: Float,
    val playing: Boolean,
)

/** The media playing right now on the TV (Spotify preferred), or null. */
@Composable
fun rememberNowPlaying(): NowPlaying? {
    val context = LocalContext.current
    var state by remember { mutableStateOf<NowPlaying?>(null) }

    DisposableEffect(Unit) {
        val manager = context.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(context, MediaListener::class.java)
        val handler = Handler(Looper.getMainLooper())
        var controller: MediaController? = null

        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) { state = controller?.snapshot(context) }
            override fun onPlaybackStateChanged(playback: PlaybackState?) { state = controller?.snapshot(context) }
            override fun onSessionDestroyed() { state = null }
        }

        fun pick(sessions: List<MediaController>?) {
            controller?.unregisterCallback(callback)
            controller = sessions.preferred()
            controller?.registerCallback(callback, handler)
            state = controller?.snapshot(context)
        }

        val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }
        val registered = runCatching {
            pick(manager.getActiveSessions(listener))
            manager.addOnActiveSessionsChangedListener(sessionsListener, listener, handler)
        }.onFailure { Log.w("Lumen", "No media session access (notification listener not enabled?)", it) }.isSuccess

        onDispose {
            controller?.unregisterCallback(callback)
            if (registered) manager.removeOnActiveSessionsChangedListener(sessionsListener)
        }
    }
    return state
}

private const val SPOTIFY = "com.spotify.tv.android"

/** The session worth showing/controlling: whatever is playing, Spotify first. */
private fun List<MediaController>?.preferred(): MediaController? = orEmpty().sortedWith(
    compareByDescending<MediaController> { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        .thenByDescending { it.packageName == SPOTIFY }
).firstOrNull()

/** Current media session, outside Compose (used by the phone remote). */
fun activeMediaController(context: Context): MediaController? = runCatching {
    context.getSystemService(MediaSessionManager::class.java)
        .getActiveSessions(ComponentName(context, MediaListener::class.java))
        .preferred()
}.getOrNull()

fun MediaController.snapshot(context: Context): NowPlaying? {
    val metadata = metadata ?: return null
    val playback = playbackState ?: return null
    if (playback.state != PlaybackState.STATE_PLAYING && playback.state != PlaybackState.STATE_PAUSED) return null
    val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return null
    val appName = if (packageName == context.packageName) "Radio" else runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))).toString()
    }.getOrDefault(packageName)
    return NowPlaying(
        app = appName,
        title = title,
        artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
        art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART),
        artUri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI),
        durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
        positionMs = playback.position,
        positionAt = playback.lastPositionUpdateTime,
        speed = playback.playbackSpeed,
        playing = playback.state == PlaybackState.STATE_PLAYING,
    )
}
