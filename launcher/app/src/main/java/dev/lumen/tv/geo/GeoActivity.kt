package dev.lumen.tv.geo

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import dev.lumen.tv.remote.RemoteService

/**
 * GeoGuesser on the TV: a full-screen WebView on the game page served by Lumen's own web
 * server, the same place the phones play from (see [GeoGame]). Back leaves the game.
 */
class GeoActivity : ComponentActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val web = WebView(this).apply {
            setBackgroundColor(android.graphics.Color.rgb(5, 5, 7))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // The page is laid out at 1920 px wide; fit it to the screen.
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            webViewClient = WebViewClient()
            loadUrl("http://127.0.0.1:${RemoteService.PORT}/geo/tv")
        }
        setContentView(web)
        web.requestFocus()
        webView = web
        // Back: mid-game returns to the lobby; in the lobby, closes the app. The game lives in
        // Lumen's main process, so ask it over the local web server.
        onBackPressedDispatcher.addCallback(this) {
            Thread {
                val exit = runCatching {
                    val connection = java.net.URL("http://127.0.0.1:${RemoteService.PORT}/api/geo/back").openConnection() as java.net.HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 2_000
                    connection.readTimeout = 2_000
                    org.json.JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optBoolean("exit", true)
                }.getOrDefault(true)
                if (exit) runOnUiThread { finish() }
            }.start()
        }
    }

    // The WebView would otherwise swallow Back; route it to the dispatcher above.
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            if (event.action == android.view.KeyEvent.ACTION_UP) onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private var webView: WebView? = null

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
