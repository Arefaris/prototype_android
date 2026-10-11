package app.gamenative.prototype

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.gamenative.utils.CustomGameScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

private sealed interface LauncherState {
    data object Checking : LauncherState
    data object NeedsPermission : LauncherState
    data object GameNotFound : LauncherState
    data class Preparing(val message: String) : LauncherState
    data class Failed(val message: String) : LauncherState
}

internal val Background = Color(0xFF0B0B0D)
internal val Accent = Color(0xFFC62828)
internal val TextMain = Color(0xFFECECEC)
internal val TextDim = Color(0xFF9A9A9A)

/**
 * The only screen of the app: checks storage access and the game folder, prepares the container
 * and hands off to the stock launch path. When everything is in place it launches without input.
 */
@Composable
fun PrototypeScreen(onLaunch: (appId: String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<LauncherState>(LauncherState.Checking) }
    var launched by rememberSaveable { mutableStateOf(false) }

    fun check() {
        if (launched || state is LauncherState.Preparing) return
        val storageRoot = "/storage/emulated/0"
        if (!CustomGameScanner.hasStoragePermission(context, storageRoot)) {
            state = LauncherState.NeedsPermission
            return
        }
        val gameDir = PrototypeGame.findGameDir()
        if (gameDir == null) {
            state = LauncherState.GameNotFound
            return
        }
        state = LauncherState.Preparing("Preparing the game…")
        scope.launch {
            try {
                withContext(Dispatchers.IO) { prepare(context, gameDir) }
                launched = true
                onLaunch(PrototypeGame.APP_ID)
            } catch (e: Exception) {
                Timber.e(e, "Prototype launch preparation failed")
                state = LauncherState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    LifecycleResumeEffect(Unit) {
        check()
        onPauseOrDispose { }
    }

    // The stock launch path reports failures through PrototypeLaunchStatus instead of a dialog
    val launchError = PrototypeLaunchStatus.error
    LaunchedEffect(launchError) {
        if (launchError != null) {
            launched = false
            state = LauncherState.Failed(launchError)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("PROTOTYPE", color = TextMain, fontSize = 40.sp, fontWeight = FontWeight.Black, letterSpacing = 8.sp)
        Spacer(Modifier.height(32.dp))

        when (val s = state) {
            LauncherState.Checking, is LauncherState.Preparing -> {
                val progress = PrototypeLaunchStatus.progress
                if (launched && progress >= 0f) {
                    // Only the first launch unpacks components, so it is the only one with real progress
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        color = Accent,
                        trackColor = Color(0xFF2A2A2E),
                        modifier = Modifier.widthIn(min = 320.dp, max = 480.dp).height(6.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    Message("First launch: setting things up.\nThis happens once and takes about a minute.")
                } else {
                    CircularProgressIndicator(color = Accent, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(16.dp))
                    Message((s as? LauncherState.Preparing)?.message ?: "Checking…")
                }
            }
            LauncherState.NeedsPermission -> {
                Message("Prototype needs access to all files to find the game folder.")
                Spacer(Modifier.height(24.dp))
                ActionButton("Allow access") { CustomGameScanner.requestManageExternalStoragePermission(context) }
            }
            LauncherState.GameNotFound -> {
                Message(
                    "Game not found.\n\nCopy the Prototype game folder to \"${PrototypeGame.FOLDER_NAME}\" " +
                        "at the root of internal storage or the SD card, so that this file exists:\n" +
                        "/${PrototypeGame.FOLDER_NAME}/${PrototypeGame.EXE_NAME}\n\n" +
                        "A copy downloaded with GameNative to the SD card or external storage is found where it is.",
                )
                Spacer(Modifier.height(24.dp))
                ActionButton("Check again") { check() }
            }
            is LauncherState.Failed -> {
                Message("Could not start the game:\n${s.message}")
                Spacer(Modifier.height(24.dp))
                ActionButton("Retry") {
                    state = LauncherState.Checking
                    check()
                }
            }
        }
    }
}

private fun prepare(context: android.content.Context, gameDir: File) {
    PrototypeGame.installCompanionHelper(context, gameDir)
    PrototypeGame.register(gameDir)
    PrototypeGame.prepareContainer(context, gameDir)
}

@Composable
private fun Message(text: String) {
    Text(text, color = TextDim, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 560.dp))
}

@Composable
private fun ActionButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = TextMain),
        modifier = Modifier.widthIn(min = 240.dp).height(56.dp),
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
