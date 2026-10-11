package app.gamenative.prototype.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colours read off the game's pause menu, map and upgrade store (GENTEK screens). */
internal object Gentek {
    val Black = Color(0xFF0B0202)
    val DeepRed = Color(0xFF2A0604)
    val GlowRed = Color(0xFF5E0E09)
    val Line = Color(0xFF7A1610)
    val LineBright = Color(0xFFE0301C)
    val Title = Color(0xFFD02A1C)
    val Khaki = Color(0xFFB8A57A)
    val Cream = Color(0xFFF6DCC6)
    val Orange = Color(0xFFFF7A3C)
    val Amber = Color(0xFFFF9614)
    val ItemTop = Color(0xFF2B2621)
    val ItemBottom = Color(0xFF15110E)
    val SelectedTop = Color(0xFFB8261A)
    val SelectedBottom = Color(0xFF7A120A)
    val Panel = Color(0xCC0E0303)
    val Crosshair = Color(0xFFFF6418)
    // Around the island on the pause map: a dark red, (48, 4, 2) near the island
    val MapBackground = Color(0xFF300402)
}

/**
 * The game's fonts when they could be read from its files, else the platform's. The menus, lists and labels are set
 * in the squared PrototypeBody; the distressed PrototypeTitle is only used for the GENTEK logo.
 */
internal class GentekFonts(logo: FontFamily?, body: FontFamily?) {
    private val logo = logo ?: FontFamily.Default
    val body = body ?: FontFamily.Default
    private val fallback = body == null

    fun logo(size: TextUnit, color: Color) = TextStyle(fontFamily = logo, fontSize = size, color = color, shadow = Shadow(Color.Black, Offset.Zero, 8f))

    /** Menu entries, headers, prices: upper case lettering, optionally glowing. */
    fun title(size: TextUnit, color: Color, glow: Color? = null) = TextStyle(
        fontFamily = body,
        fontSize = size,
        color = color,
        fontWeight = if (fallback) FontWeight.Bold else FontWeight.Normal,
        shadow = glow?.let { Shadow(it, Offset.Zero, 10f) },
    )

    fun body(size: TextUnit, color: Color) = TextStyle(fontFamily = body, fontSize = size, color = color, lineHeight = size * 1.25f)
}

internal val LocalGentekFonts = staticCompositionLocalOf { GentekFonts(null, null) }

/** The screens' backdrop: a dark red glow, scan lines, a thin frame with bright corners. */
internal fun Modifier.gentekBackdrop() = background(
    Brush.radialGradient(listOf(Gentek.GlowRed, Gentek.DeepRed, Gentek.Black), radius = 1400f),
).scanLines()

internal fun Modifier.scanLines(alpha: Float = 0.28f) = drawBehind {
    val step = 3.dp.toPx()
    var y = 0f
    while (y < size.height) {
        drawLine(Color.Black.copy(alpha = alpha), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        y += step
    }
}

/** A panel: translucent dark fill, thin red outline and bright corner ticks. */
internal fun Modifier.gentekFrame(fill: Color = Gentek.Panel, corner: Dp = 7.dp) = drawBehind {
    drawRect(fill)
    val w = 1.dp.toPx()
    drawRect(Gentek.Line, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
    val c = corner.toPx()
    val t = 2.dp.toPx()
    for ((x, y, dx, dy) in listOf(
        listOf(0f, 0f, 1f, 1f), listOf(size.width, 0f, -1f, 1f),
        listOf(0f, size.height, 1f, -1f), listOf(size.width, size.height, -1f, -1f),
    )) {
        drawLine(Gentek.LineBright, Offset(x, y), Offset(x + dx * c, y), strokeWidth = t)
        drawLine(Gentek.LineBright, Offset(x, y), Offset(x, y + dy * c), strokeWidth = t)
    }
}

/** A pause-menu entry: dark bar with khaki lettering, or red with glowing cream lettering when [selected]. */
@Composable
internal fun MenuItem(label: String, selected: Boolean, enabled: Boolean = true, height: Dp = 30.dp, onClick: () -> Unit) {
    val fonts = LocalGentekFonts.current
    val brush = if (selected) Brush.verticalGradient(listOf(Gentek.SelectedTop, Gentek.SelectedBottom))
    else Brush.verticalGradient(listOf(Gentek.ItemTop, Gentek.ItemBottom))
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .background(brush)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            label,
            style = fonts.title(
                14.sp,
                when {
                    selected -> Gentek.Cream
                    enabled -> Gentek.Khaki
                    else -> Gentek.Khaki.copy(alpha = 0.35f)
                },
                glow = if (selected) Gentek.LineBright else null,
            ),
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}
