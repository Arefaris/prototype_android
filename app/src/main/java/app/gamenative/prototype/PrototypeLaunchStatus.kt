package app.gamenative.prototype

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Progress of the stock launch path (preLaunchApp), shown on the Prototype screen instead of
 * GameNative's loading and message dialogs.
 */
object PrototypeLaunchStatus {
    /** 0..1 while unpacking components, negative when the step has no measurable progress. */
    var progress by mutableStateOf(-1f)

    /** Set when the launch path stops with an error. */
    var error by mutableStateOf<String?>(null)

    fun reset() {
        progress = -1f
        error = null
    }
}
