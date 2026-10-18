package dev.lumen.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import dev.lumen.tv.ui.HomeScreen
import dev.lumen.tv.ui.LumenTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // We are the home screen: there is nothing to go back to.
        onBackPressedDispatcher.addCallback(this) {}
        setContent {
            LumenTheme {
                HomeScreen()
            }
        }
        // The home screen paints its own opaque background; skipping the window's black fill
        // saves a full-screen pass on every frame on the Chromecast's small GPU.
        window.setBackgroundDrawable(null)
    }
}
