package dev.lumen.tv.remote

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.lumen.tv.ui.LumenTheme

/**
 * One floating window drawn above whatever app is on screen. Accessibility services may add
 * TYPE_ACCESSIBILITY_OVERLAY windows without any extra permission. It never takes focus or
 * touches, so the app underneath keeps working normally. Call from the main thread.
 */
class Overlay(private val service: AccessibilityService, private val gravity: Int) : LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private val windows = service.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null

    init {
        savedState.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    val showing: Boolean get() = view != null

    fun show(content: @Composable () -> Unit) {
        hide()
        val density = service.resources.displayMetrics.density
        val composeView = ComposeView(service).apply {
            setViewTreeLifecycleOwner(this@Overlay)
            setViewTreeSavedStateRegistryOwner(this@Overlay)
            setContent {
                LumenTheme {
                    // Slide/fade in once when the window appears.
                    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
                    AnimatedVisibility(
                        visible,
                        enter = fadeIn(tween(350)) + slideInVertically(tween(350)) { -it / 3 },
                        exit = fadeOut(tween(250)) + slideOutVertically(tween(250)) { -it / 3 },
                    ) { content() }
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = this@Overlay.gravity
            x = (48 * density).toInt()
            y = (40 * density).toInt()
        }
        runCatching { windows.addView(composeView, params) }.onSuccess { view = composeView }
    }

    fun hide() {
        view?.let { runCatching { windows.removeView(it) } }
        view = null
    }

    companion object {
        val TOP_END = Gravity.TOP or Gravity.END
        val BOTTOM_CENTER = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    }
}
