package app.gamenative.prototype.companion

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import timber.log.Timber
import java.io.File

/** A district name on the pause map, centred in a box (map units); [key] is the text bible key. */
class MapLabel(val key: String, val centerX: Float, val top: Float, val width: Float, val size: Float, val color: Color)

/**
 * The pause map's vector Manhattan (protomap.gfx): land filled, blocks cut out and outlined. Map units are the
 * pause map's pixels: a marker sits at (fe_GetMarkerXPos, −fe_GetMarkerYPos), Alex at (0.375 x, −0.375 z).
 */
class MapArt(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    // mcGround: dark shapes under the island (black at 70 %), so the blocks cut out of the land read as near black
    val ground: List<Path>,
    val fills: List<Pair<Color, Path>>,
    val lines: List<Triple<Color, Float, Path>>,
    val labels: List<MapLabel>,
)

/** Everything the companion borrows from the player's game files; any part may be missing (then plain styling). */
class GameAssets(
    val titleFont: FontFamily?,
    val bodyFont: FontFamily?,
    val map: MapArt?,
    // Pause map icons by marker icon type
    val icons: Map<Int, ImageBitmap>,
) {
    companion object {
        val EMPTY = GameAssets(null, null, null, emptyMap())

        fun load(context: Context, gameDir: File): GameAssets {
            val files = GameFiles(gameDir)
            val fonts = step("fonts") { loadFonts(context, files) }.orEmpty()
            return GameAssets(
                titleFont = fonts["PrototypeTitlePC"],
                bodyFont = fonts["PrototypeBodyPC"],
                map = step("map") { loadMap(files) },
                icons = step("icons") { loadIcons(files) }.orEmpty(),
            )
        }

        /** An upgrade's store picture (`art/hud/<id>.p3d`), cropped to the drawn part. */
        fun upgradePicture(gameDir: File, id: String): ImageBitmap? = step("picture $id") {
            val dds = GameFiles(gameDir).textures("art/hud/$id.p3d").values.firstOrNull() ?: return@step null
            Dds.decode(dds)?.let(::cropTransparent)?.asImageBitmap()
        }

        private fun <T> step(what: String, block: () -> T?): T? = try {
            block()
        } catch (e: Exception) {
            Timber.w(e, "Companion: loading the game's $what failed")
            null
        }

        // The movie's DefineFont3 outlines, written once as TrueType files in app data
        private fun loadFonts(context: Context, files: GameFiles): Map<String, FontFamily> {
            // Bump the folder when TtfWriter changes, so old files are not reused
            val dir = File(context.filesDir, "companion/fonts-1")
            val wanted = listOf("PrototypeTitlePC", "PrototypeBodyPC")
            if (wanted.any { !File(dir, "$it.ttf").isFile }) {
                val movie = SwfMovie(files.read("art/hud/fonts_latin.gfx") ?: return emptyMap())
                val fonts = movie.fonts()
                dir.mkdirs()
                for (name in wanted) {
                    val font = fonts[name] ?: continue
                    File(dir, "$name.ttf").writeBytes(TtfWriter.write(font, name, bold = false))
                }
            }
            return wanted.mapNotNull { name ->
                val file = File(dir, "$name.ttf").takeIf { it.isFile } ?: return@mapNotNull null
                val typeface = android.graphics.Typeface.createFromFile(file)
                name to FontFamily(androidx.compose.ui.text.font.Typeface(typeface))
            }.toMap()
        }

        private fun loadMap(files: GameFiles): MapArt? {
            val movie = SwfMovie(files.read("art/hud/protomap.gfx") ?: return null)
            val shapes = movie.shapes()
            val texts = movie.editTexts()
            val placements = movie.placements()
            // The district labels' sprite sits in the map clip (mcMap) next to the island, its largest shape
            val districts = placements.filter { texts[it.character]?.text?.startsWith("\$META_DISTRICT") == true }
            val districtsClip = districts.firstOrNull()?.let { d -> placements.firstOrNull { it.character == d.sprite } } ?: return null
            val offset = districtsClip.matrix
            val island = placements.filter { it.sprite == districtsClip.sprite }.mapNotNull { shapes[it.character] }
                .maxByOrNull { (it.bounds[1] - it.bounds[0]).toLong() * (it.bounds[3] - it.bounds[2]) } ?: return null
            val labels = districts.mapNotNull { p ->
                val text = texts[p.character] ?: return@mapNotNull null
                val left = (p.matrix.translateX + offset.translateX + text.bounds[0]) / 20f
                val top = (p.matrix.translateY + offset.translateY + text.bounds[2]) / 20f
                val width = (text.bounds[1] - text.bounds[0]) / 20f
                MapLabel(text.text.removePrefix("$"), left + width / 2, top, width, text.heightTwips / 20f, Color(text.argb))
            }
            // mcGround: a clip of dark shapes placed under the island in the same map clip (translations only)
            val groundClip = placements.firstOrNull { it.sprite == districtsClip.sprite && it.name == "mcGround" }
            val ground = placements.filter { groundClip != null && it.sprite == groundClip.character }.flatMap { p ->
                val shape = shapes[p.character] ?: return@flatMap emptyList()
                val dx = groundClip!!.matrix.translateX + p.matrix.translateX
                val dy = groundClip.matrix.translateY + p.matrix.translateY
                fillPaths(shape, dx, dy).map { it.second }
            }
            return MapArt(
                island.bounds[0] / 20f,
                island.bounds[2] / 20f,
                island.bounds[1] / 20f,
                island.bounds[3] / 20f,
                ground,
                fillPaths(island),
                linePaths(island),
                labels,
            )
        }

        // SWF fills are given per edge (fill on the left and right); chain each style's edges into closed contours
        // [dx]/[dy]: the shape's placement, in twips
        private fun fillPaths(shape: SwfShape, dx: Int = 0, dy: Int = 0): List<Pair<Color, Path>> {
            val groups = LinkedHashMap<Pair<Int, Int>, MutableList<SwfEdge>>()
            for (raw in shape.edges) {
                val e = raw.moved(dx, dy)
                if (e.fill1 != 0) groups.getOrPut(e.table to e.fill1) { mutableListOf() } += e
                if (e.fill0 != 0) groups.getOrPut(e.table to e.fill0) { mutableListOf() } += e.reversed()
            }
            return groups.map { (style, edges) ->
                val byStart = HashMap<Long, ArrayDeque<SwfEdge>>()
                for (e in edges) byStart.getOrPut(key(e.x0, e.y0)) { ArrayDeque() } += e
                val path = Path().apply { fillType = PathFillType.EvenOdd }
                for (e0 in edges) {
                    val queue = byStart[key(e0.x0, e0.y0)] ?: continue
                    if (!queue.remove(e0)) continue
                    path.moveTo(e0.x0 / 20f, e0.y0 / 20f)
                    var e = e0
                    while (true) {
                        path.edgeTo(e)
                        if (e.x1 == e0.x0 && e.y1 == e0.y0) break
                        e = byStart[key(e.x1, e.y1)]?.removeFirstOrNull() ?: break
                    }
                    path.close()
                }
                Color(shape.tables[style.first].fills[style.second - 1]) to path
            }
        }

        private fun linePaths(shape: SwfShape): List<Triple<Color, Float, Path>> {
            val groups = LinkedHashMap<Pair<Int, Int>, Path>()
            val ends = HashMap<Pair<Int, Int>, Long>()
            for (e in shape.edges) {
                if (e.line == 0) continue
                val style = e.table to e.line
                val path = groups.getOrPut(style) { Path() }
                if (ends[style] != key(e.x0, e.y0)) path.moveTo(e.x0 / 20f, e.y0 / 20f)
                path.edgeTo(e)
                ends[style] = key(e.x1, e.y1)
            }
            return groups.map { (style, path) ->
                val line = shape.tables[style.first].lines[style.second - 1]
                Triple(Color(line.argb), line.widthTwips / 20f, path)
            }
        }

        private fun Path.edgeTo(e: SwfEdge) {
            if (e.controlX != null && e.controlY != null) {
                quadraticTo(e.controlX / 20f, e.controlY / 20f, e.x1 / 20f, e.y1 / 20f)
            } else {
                lineTo(e.x1 / 20f, e.y1 / 20f)
            }
        }

        private fun SwfEdge.moved(dx: Int, dy: Int) = if (dx == 0 && dy == 0) this else copy(
            x0 = x0 + dx, y0 = y0 + dy, x1 = x1 + dx, y1 = y1 + dy, controlX = controlX?.plus(dx), controlY = controlY?.plus(dy),
        )

        private fun SwfEdge.reversed() = copy(fill0 = fill1, fill1 = fill0, x0 = x1, y0 = y1, x1 = x0, y1 = y0)

        private fun key(x: Int, y: Int) = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

        // protomap.gfx's "indicator_image" frames, one per icon type (frame = type + 1), and the bitmap each shows
        private val ICON_TEXTURES = mapOf(
            0 to "I1", 1 to "I4", 2 to "I7", 3 to "IA", 4 to "ID", 5 to "I10", 16 to "I1E",
            17 to "I21", 18 to "I25", 19 to "I29", 20 to "I2D", 21 to "I31", 22 to "I35", 23 to "I31",
            24 to "I21", 25 to "I25", 26 to "I29", 27 to "I2D", 28 to "I31", 29 to "I35", 30 to "I31",
            31 to "I3B", 32 to "I3F", 33 to "I3F", 34 to "I42", 35 to "I42", 37 to "I7", 39 to "I46",
        )

        private fun loadIcons(files: GameFiles): Map<Int, ImageBitmap> {
            val names = ICON_TEXTURES.values.map { "protomap__$it" }.toSet()
            val textures = files.textures("art/hud/fe_pause.p3d") { it in names }
            val bitmaps = textures.mapNotNull { (name, dds) -> Dds.decode(dds)?.let { name.removePrefix("protomap__") to it.asImageBitmap() } }.toMap()
            return ICON_TEXTURES.mapNotNull { (type, texture) -> bitmaps[texture]?.let { type to it } }.toMap()
        }

        private fun cropTransparent(bitmap: Bitmap): Bitmap {
            var left = bitmap.width
            var top = bitmap.height
            var right = -1
            var bottom = -1
            val row = IntArray(bitmap.width)
            for (y in 0 until bitmap.height) {
                bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                for (x in row.indices) {
                    if (row[x] ushr 24 > 16) {
                        if (x < left) left = x
                        if (x > right) right = x
                        if (y < top) top = y
                        bottom = y
                    }
                }
            }
            if (right < left || bottom < top) return bitmap
            return Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1)
        }
    }
}
