package app.gamenative.prototype.companion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * World (metres) to pause-map units. The pause map places a marker at (XPos, −YPos) and Alex the same way; marker
 * coordinates are 0.375 × the world's x and z (checked against the pause map on the device: the mission and event
 * markers and Alex land where the game draws them).
 */
object MapProjection {
    const val UNITS_PER_METRE = 0.375f

    fun mapX(worldX: Float) = UNITS_PER_METRE * worldX
    fun mapY(worldZ: Float) = -UNITS_PER_METRE * worldZ

    /** Screen heading of a world direction, in degrees clockwise from up (north up, as the pause map). */
    fun headingDegrees(dirX: Float, dirZ: Float): Float = Math.toDegrees(atan2(dirX.toDouble(), dirZ.toDouble())).toFloat()
}

/**
 * The pause map on the second screen: follows Alex or shows the whole island; pinch or the buttons zoom, a double
 * tap switches between the two, a tap on a marker selects it ([onMarkerSelected]).
 */
@Composable
fun MiniMap(
    state: CompanionState,
    assets: GameAssets,
    selected: Marker?,
    onMarkerSelected: (Marker?) -> Unit,
    zoomRequest: Int,
    modifier: Modifier = Modifier,
) {
    // Map units per screen pixel when following Alex
    var unitsPerPixel by remember { mutableFloatStateOf(0.55f) }
    var follow by remember { mutableStateOf(true) }
    var lastZoomRequest by remember { mutableStateOf(zoomRequest) }
    if (zoomRequest != lastZoomRequest) {
        unitsPerPixel = (unitsPerPixel * if (zoomRequest > lastZoomRequest) 0.7f else 1.43f).coerceIn(MIN_UPP, MAX_UPP)
        lastZoomRequest = zoomRequest
    }
    val textMeasurer = rememberTextMeasurer(cacheSize = 32)
    val labelFont = LocalGentekFonts.current.body

    Box(modifier.background(Brush.radialGradient(listOf(Gentek.MapBackground, Color(0xFF180201))))) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // Markers far from Alex would otherwise be drawn over the panels around the map
                .clipToBounds()
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        unitsPerPixel = (unitsPerPixel / zoom).coerceIn(MIN_UPP, MAX_UPP)
                    }
                }
                .pointerInput(state.markers, follow, unitsPerPixel, state.x, state.z) {
                    detectTapGestures(
                        onDoubleTap = { follow = !follow },
                        onTap = { tap ->
                            val view = viewport(state, assets.map, follow, unitsPerPixel, size.width.toFloat(), size.height.toFloat())
                            val distance = { m: Marker -> view.toScreen(m.mapX, m.mapY).let { hypot(it.x - tap.x, it.y - tap.y) } }
                            val hit = state.markers.minByOrNull(distance)
                            onMarkerSelected(hit?.takeIf { distance(it) < TAP_RADIUS_DP * density })
                        },
                    )
                },
        ) {
            val view = viewport(state, assets.map, follow, unitsPerPixel, size.width, size.height)
            val map = assets.map
            if (map != null) {
                drawIsland(map, view)
                drawZones(state.zones, view)
                drawLabels(map, state.strings, view, textMeasurer, TextStyle(fontFamily = labelFont, textAlign = TextAlign.Center))
            } else {
                drawGrid(view)
            }
            if (state.inWorld) drawCrosshair(view.toScreen(MapProjection.mapX(state.x), MapProjection.mapY(state.z)))
            for (marker in state.markers) drawMarker(marker, assets.icons[marker.icon], view.toScreen(marker.mapX, marker.mapY), marker == selected)
            if (state.inWorld) {
                drawAlex(view.toScreen(MapProjection.mapX(state.x), MapProjection.mapY(state.z)), MapProjection.headingDegrees(state.dirX, state.dirZ))
            }
        }
    }
}

/** Where the map is looking: the centre (map units) and the scale. */
private class Viewport(val centreX: Float, val centreY: Float, val unitsPerPixel: Float, val width: Float, val height: Float) {
    fun toScreen(mapX: Float, mapY: Float) = Offset(
        width / 2 + (mapX - centreX) / unitsPerPixel,
        height / 2 + (mapY - centreY) / unitsPerPixel,
    )
}

private fun viewport(state: CompanionState, map: MapArt?, follow: Boolean, unitsPerPixel: Float, width: Float, height: Float): Viewport {
    if (follow || map == null) {
        return Viewport(MapProjection.mapX(state.x), MapProjection.mapY(state.z), unitsPerPixel, width, height)
    }
    // Overview: the whole island
    val upp = max((map.right - map.left) / width, (map.bottom - map.top) / height) * 1.04f
    return Viewport((map.left + map.right) / 2, (map.top + map.bottom) / 2, upp, width, height)
}

private fun DrawScope.drawIsland(map: MapArt, view: Viewport) {
    val scale = 1f / view.unitsPerPixel
    withTransform({
        translate(view.width / 2 - view.centreX * scale, view.height / 2 - view.centreY * scale)
        scale(scale, scale, pivot = Offset.Zero)
    }) {
        for (path in map.ground) drawPath(path, GROUND)
        for ((color, path) in map.fills) drawPath(path, color)
        // The outlines are 2 px on the game's map; keep them at least a hairline when zoomed out
        for ((color, width, path) in map.lines) drawPath(path, color, style = Stroke(width = max(width, 1.2f * view.unitsPerPixel)))
    }
}

// The pause map's mcMilitaryLayer / mcInfectedLayer: a flat colour added (blend mode "add") inside the union of the
// active zones' circles
private fun DrawScope.drawZones(zones: List<Zone>, view: Viewport) {
    for ((military, color) in listOf(true to MILITARY_ADD, false to INFECTED_ADD)) {
        val area = Path()
        for (z in zones.filter { it.military == military }) {
            val c = view.toScreen(z.mapX, z.mapY)
            val r = z.radius / view.unitsPerPixel
            area.addOval(androidx.compose.ui.geometry.Rect(c.x - r, c.y - r, c.x + r, c.y + r))
        }
        if (!area.isEmpty) drawPath(area, color, blendMode = BlendMode.Plus)
    }
}

private fun DrawScope.drawLabels(map: MapArt, strings: Map<String, String>, view: Viewport, measurer: TextMeasurer, style: TextStyle) {
    val scale = 1f / view.unitsPerPixel
    // The game's labels scale with the map; keep them readable at both ends of the zoom range
    val size = (14f * scale).coerceIn(9.sp.toPx(), 15.sp.toPx())
    for (label in map.labels) {
        val text = strings[label.key]?.takeIf { it.isNotBlank() } ?: continue
        val anchor = view.toScreen(label.centerX, label.top)
        val width = max(label.width * scale, size * 4)
        if (anchor.x + width / 2 < 0 || anchor.x - width / 2 > view.width || anchor.y > view.height || anchor.y < -size * 3) continue
        val layout = measurer.measure(
            text,
            style.copy(color = label.color, fontSize = size.toSp()),
            constraints = Constraints(maxWidth = width.roundToInt()),
        )
        drawText(layout, topLeft = Offset(anchor.x - layout.size.width / 2f, anchor.y))
    }
}

private fun DrawScope.drawGrid(view: Viewport) {
    // Every 40 map units (about 100 m)
    val step = 40f
    val left = view.centreX - view.width / 2 * view.unitsPerPixel
    val top = view.centreY - view.height / 2 * view.unitsPerPixel
    var gx = kotlin.math.floor(left / step) * step
    while (view.toScreen(gx, 0f).x <= view.width) {
        val sx = view.toScreen(gx, 0f).x
        drawLine(Gentek.Line.copy(alpha = 0.4f), Offset(sx, 0f), Offset(sx, view.height), strokeWidth = 1.5f)
        gx += step
    }
    var gy = kotlin.math.floor(top / step) * step
    while (view.toScreen(0f, gy).y <= view.height) {
        val sy = view.toScreen(0f, gy).y
        drawLine(Gentek.Line.copy(alpha = 0.4f), Offset(0f, sy), Offset(view.width, sy), strokeWidth = 1.5f)
        gy += step
    }
}

// The pause map's reticle: thin orange lines through Alex, open around him
private fun DrawScope.drawCrosshair(at: Offset) {
    val gap = 16.dp.toPx()
    val w = 1.5.dp.toPx()
    val color = Gentek.Crosshair.copy(alpha = 0.85f)
    drawLine(color, Offset(0f, at.y), Offset(at.x - gap, at.y), w)
    drawLine(color, Offset(at.x + gap, at.y), Offset(size.width, at.y), w)
    drawLine(color, Offset(at.x, 0f), Offset(at.x, at.y - gap), w)
    drawLine(color, Offset(at.x, at.y + gap), Offset(at.x, size.height), w)
}

private fun DrawScope.drawMarker(marker: Marker, icon: ImageBitmap?, at: Offset, selected: Boolean) {
    val event = marker.icon in 17..30
    // Events are drawn at 125 % (protomap SetEventProperties)
    val side = (if (event) 30.dp else 24.dp).toPx() * if (selected) 1.25f else 1f
    // Types without a picture in the pause map's indicator clip (areas, enemies, NONE) are not drawn there either
    if (icon == null) return
    drawImage(
        icon,
        dstOffset = IntOffset((at.x - side / 2).roundToInt(), (at.y - side / 2).roundToInt()),
        dstSize = IntSize(side.roundToInt(), side.roundToInt()),
        colorFilter = when {
            event -> eventTint(marker.medal)
            marker.icon == DESTROY -> ENEMY_TINT
            else -> null
        },
    )
}

// ColourEventMarker: the medal won in that event, or green when none yet
private fun eventTint(medal: Int): ColorFilter {
    val (r, g, b) = when (medal) {
        1 -> Triple(0.51f, 0.21f, 0.02f)
        2 -> Triple(0.61f, 0.65f, 0.69f)
        3 -> Triple(0.94f, 0.68f, 0.24f)
        4 -> Triple(1f, 1f, 1f)
        else -> Triple(0.60f, 0.97f, 0.58f)
    }
    return ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(r, g, b, 1f) })
}

// The pause map's player marker: a white arrowhead with a dark edge
private fun DrawScope.drawAlex(at: Offset, headingDegrees: Float) {
    val size = 13.dp.toPx()
    rotate(headingDegrees, pivot = at) {
        val arrow = Path().apply {
            moveTo(at.x, at.y - size)
            lineTo(at.x + size * 0.78f, at.y + size * 0.8f)
            lineTo(at.x, at.y + size * 0.3f)
            lineTo(at.x - size * 0.78f, at.y + size * 0.8f)
            close()
        }
        drawPath(arrow, Color.Black.copy(alpha = 0.85f), style = Stroke(width = 4.dp.toPx()))
        drawPath(arrow, Color.White)
    }
}

private val GROUND = Color(0f, 0f, 0f, 179f / 256f)

// Targets to destroy: the engine hands the pause map a colour for them (red there); the icon itself is white
private const val DESTROY = 0
private val ENEMY_TINT = ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(0.8f, 0.12f, 0.08f, 1f) })
private val MILITARY_ADD = Color(1, 20, 50)
private val INFECTED_ADD = Color(74, 0, 0)

private const val MIN_UPP = 0.12f
private const val MAX_UPP = 3f
private const val TAP_RADIUS_DP = 22f
