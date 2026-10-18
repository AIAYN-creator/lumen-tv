package dev.lumen.tv.dream

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.lumen.tv.ui.LumenTheme

/**
 * The screensaver on demand (moon button on the home screen). Apps can't start the system
 * dream without root, so this shows the same screen as an activity. Any key closes it, and
 * since it doesn't keep the screen on, the system dream and sleep still take over as usual.
 */
class ScreensaverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val night = intent.getBooleanExtra(EXTRA_NIGHT, false)
        setContent { LumenTheme { ScreensaverScreen(forceNight = night) } }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        finish()
        return true
    }

    companion object {
        /** Show night mode regardless of the time (preview from the phone remote). */
        const val EXTRA_NIGHT = "night"
    }
}
