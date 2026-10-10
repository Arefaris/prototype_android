package app.gamenative.prototype

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Splash shown over the X server while the container boots; matches the launcher screen. */
@Composable
fun PrototypeBootSplash(visible: Boolean, text: String, progress: Float) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMillis = 400)),
        exit = fadeOut(animationSpec = tween(durationMillis = 300)),
    ) {
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

            val barModifier = Modifier.widthIn(min = 320.dp, max = 480.dp).height(6.dp)
            val track = Color(0xFF2A2A2E)
            if (progress < 0f) {
                LinearProgressIndicator(color = Accent, trackColor = track, modifier = barModifier)
            } else {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    color = Accent,
                    trackColor = track,
                    modifier = barModifier,
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(text, color = TextDim, fontSize = 16.sp, textAlign = TextAlign.Center)
        }
    }
}
