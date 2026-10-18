package dev.lumen.tv.radio

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Plays the radio in the background, so it keeps going on the home screen and under the
 * screensaver. Its media session is what makes the station show up as "now playing".
 */
class RadioService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        session = MediaSession.Builder(this, player).build()

        // After two minutes on pause, stop for good, so the screensaver stops showing
        // "En pausa · Radio" and the stream isn't kept open for nothing.
        val handler = android.os.Handler(mainLooper)
        val release = Runnable { player.stop(); player.clearMediaItems() }
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                handler.removeCallbacks(release)
                if (!playWhenReady && player.mediaItemCount > 0) handler.postDelayed(release, 120_000)
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
