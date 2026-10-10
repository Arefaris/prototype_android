package app.gamenative.prototype.companion

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Shows the companion on the second screen (the AYN Thor's bottom display) while Prototype runs on the main one.
 * Same display handling as GameNative's ExternalDisplayInputController: the first presentation display other than
 * the game's, followed through hot-plug. The window never takes focus, so the controller keeps driving the game.
 * It goes away with the game: hidden while the activity is in the background, back when it returns.
 */
class CompanionController(
    private val activity: ComponentActivity,
    private val gameDir: File,
    private val gameSurface: () -> SurfaceView?,
) {
    companion object {
        /** Whether this device has a second screen for the companion; without one it is not started at all. */
        fun hasSecondScreen(context: Context): Boolean = presentationDisplays(context, Display.DEFAULT_DISPLAY).isNotEmpty()
    }

    private val displayManager = activity.getSystemService(DisplayManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val model = CompanionModel(GameBridge(gameDir), gameDir, scope)
    private val assets = MutableStateFlow(GameAssets.EMPTY)
    private var assetsRequested = false
    private var presentation: CompanionPresentation? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = updatePresentation()
        override fun onDisplayRemoved(displayId: Int) = updatePresentation()
        override fun onDisplayChanged(displayId: Int) {
            if (presentation?.display?.displayId == displayId) updatePresentation()
        }
    }

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> updatePresentation()
            Lifecycle.Event.ON_STOP -> dismissPresentation()
            else -> Unit
        }
    }

    fun start() {
        displayManager?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        activity.lifecycle.addObserver(lifecycleObserver)
        updatePresentation()
    }

    fun stop() {
        activity.lifecycle.removeObserver(lifecycleObserver)
        dismissPresentation()
        try {
            displayManager?.unregisterDisplayListener(displayListener)
        } catch (_: Exception) {
        }
        scope.cancel()
    }

    private fun updatePresentation() {
        // Minimised: nothing on the second screen until the game is back in front
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val target = findPresentationDisplay()
        if (target == null) {
            dismissPresentation()
            return
        }
        if (presentation?.display?.displayId == target.displayId) return
        dismissPresentation()
        presentation = CompanionPresentation(activity, target, model, assets, ::takeScreenshot).also { it.show() }
        model.start()
        // First time: read the map, fonts and icons out of the game files (a second or two; the fonts are cached)
        if (!assetsRequested) {
            assetsRequested = true
            scope.launch(Dispatchers.IO) {
                val loaded = GameAssets.load(activity, gameDir)
                loaded.map?.let { map -> model.requestStrings(map.labels.map { it.key }) }
                assets.value = loaded
            }
        }
    }

    private fun dismissPresentation() {
        model.stop()
        presentation?.dismiss()
        presentation = null
    }

    private fun findPresentationDisplay(): Display? {
        val current = activity.display ?: return null
        return presentationDisplays(activity, current.displayId).firstOrNull()
    }

    private fun takeScreenshot() {
        GameScreenshot.capture(activity, gameSurface()) { file ->
            model.showMessage(if (file != null) "SCREENSHOT SAVED" else "SCREENSHOT FAILED")
        }
    }
}

/** A screen other than [gameDisplayId] that can show a presentation (the AYN Thor's bottom display). */
private fun presentationDisplays(context: Context, gameDisplayId: Int): List<Display> =
    context.getSystemService(DisplayManager::class.java)
        ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        ?.filter { it.displayId != gameDisplayId && it.name != "HiddenDisplay" }
        .orEmpty()

private class CompanionPresentation(
    private val activity: ComponentActivity,
    display: Display,
    private val model: CompanionModel,
    private val assets: StateFlow<GameAssets>,
    private val onScreenshot: () -> Unit,
) : Presentation(activity, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        )
        // Compose in a dialog window needs the owners on the window's root view; the game's activity provides them
        window?.decorView?.let { root ->
            root.setViewTreeLifecycleOwner(activity)
            root.setViewTreeViewModelStoreOwner(activity)
            root.setViewTreeSavedStateRegistryOwner(activity)
        }
        setContentView(
            ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent { CompanionScreen(model, assets, onScreenshot) }
            },
        )
    }
}
