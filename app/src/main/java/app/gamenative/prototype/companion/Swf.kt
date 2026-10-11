package app.gamenative.prototype.companion

import java.util.zip.Inflater

/**
 * A minimal reader for the game's Scaleform movies (SWF with a "GFX" signature, docs/re/hud.md): the tags, vector
 * shapes, embedded fonts (DefineFont3), text fields and the placements inside sprites. Coordinates stay in twips
 * (1/20 px) until the caller converts them.
 */
internal class SwfMovie(data: ByteArray) {
    val body: ByteArray = when (String(data, 0, 3, Charsets.US_ASCII)) {
        "GFX", "FWS" -> data
        "CFX", "CWS" -> data.copyOf(8) + inflate(data, 8)
        else -> throw IllegalArgumentException("Not a SWF/GFX movie")
    }

    /** Every tag, sprites' children included, with the sprite they belong to (0 for the main timeline). */
    val tags: List<SwfTag> = buildList {
        val start = Bits(body, 8).apply { rect() }.bytePos + 4
        collect(start, body.size, 0, this)
    }

    private fun collect(from: Int, to: Int, sprite: Int, out: MutableList<SwfTag>) {
        var pos = from
        while (pos + 2 <= to) {
            val codeAndLength = u16(body, pos)
            pos += 2
            var length = codeAndLength and 0x3F
            if (length == 0x3F) {
                length = u32(body, pos)
                pos += 4
            }
            val tag = SwfTag(codeAndLength shr 6, pos, length, sprite)
            out += tag
            if (tag.code == DEFINE_SPRITE) collect(pos + 4, pos + length, u16(body, pos), out)
            if (tag.code == END && sprite != 0) return
            pos += length
        }
    }

    /** DefineShape 1–4 drawn with solid colours, by character id (shapes with bitmap or gradient fills are skipped). */
    fun shapes(): Map<Int, SwfShape> = tags.filter { it.code in SHAPE_TAGS }.mapNotNull { tag ->
        try {
            u16(body, tag.start) to parseShape(tag)
        } catch (_: RuntimeException) {
            null
        }
    }.toMap()

    /** DefineFont3 by font name. */
    fun fonts(): Map<String, SwfFont> = tags.filter { it.code == DEFINE_FONT3 }.mapNotNull { parseFont(it) }.associateBy { it.name }

    /** DefineEditText: id → (bounds in twips, initial text). */
    fun editTexts(): Map<Int, SwfEditText> = tags.filter { it.code == DEFINE_EDIT_TEXT }.mapNotNull { tag ->
        try {
            editText(tag)
        } catch (_: RuntimeException) {
            null
        }
    }.toMap()

    private fun editText(tag: SwfTag): Pair<Int, SwfEditText> {
        val id = u16(body, tag.start)
        val bits = Bits(body, tag.start + 2)
        val bounds = bits.rect()
        var p = bits.bytePos
        val flags1 = body[p].toInt() and 0xFF
        val flags2 = body[p + 1].toInt() and 0xFF
        p += 2
        var height = 0
        if (flags1 and 0x01 != 0) {
            height = u16(body, p + 2)
            p += 4
        }
        if (flags2 and 0x80 != 0) p = cstring(body, p).second
        var color = 0xFFFFFFFF.toInt()
        if (flags1 and 0x04 != 0) {
            color = ((body[p + 3].toInt() and 0xFF) shl 24) or ((body[p].toInt() and 0xFF) shl 16) or
                ((body[p + 1].toInt() and 0xFF) shl 8) or (body[p + 2].toInt() and 0xFF)
            p += 4
        }
        if (flags1 and 0x02 != 0) p += 2
        if (flags2 and 0x20 != 0) p += 9
        val (_, afterVariable) = cstring(body, p)
        val text = if (flags1 and 0x80 != 0) cstring(body, afterVariable).first else ""
        return id to SwfEditText(bounds, height, color, text)
    }

    /** PlaceObject2/3 records with a character, by the sprite they are in. */
    fun placements(): List<SwfPlacement> = tags.filter { it.code == PLACE_OBJECT2 || it.code == PLACE_OBJECT3 }.mapNotNull { tag ->
        try {
            placement(tag)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun placement(tag: SwfTag): SwfPlacement? {
        val flags = body[tag.start].toInt() and 0xFF
        var p = tag.start + if (tag.code == PLACE_OBJECT3) 4 else 3
        if (tag.code == PLACE_OBJECT3) {
            val flags2 = body[tag.start + 1].toInt() and 0xFF
            if (flags2 and 0x08 != 0) p = cstring(body, p).second // class name
        }
        if (flags and 0x02 == 0) return null
        val character = u16(body, p)
        p += 2
        var matrix = SwfMatrix()
        if (flags and 0x04 != 0) {
            val bits = Bits(body, p)
            matrix = bits.matrix()
            p = bits.bytePos
        }
        if (flags and 0x08 != 0) {
            val bits = Bits(body, p)
            val hasAdd = bits.u(1) == 1
            val hasMult = bits.u(1) == 1
            val n = bits.u(4)
            repeat((if (hasMult) 4 else 0) + (if (hasAdd) 4 else 0)) { bits.s(n) }
            p = bits.bytePos
        }
        if (flags and 0x10 != 0) p += 2
        val name = if (flags and 0x20 != 0) cstring(body, p).first else null
        return SwfPlacement(tag.sprite, character, matrix, name)
    }

    private fun parseShape(tag: SwfTag): SwfShape {
        val bits = Bits(body, tag.start + 2)
        val bounds = bits.rect()
        if (tag.code == DEFINE_SHAPE4) {
            bits.rect()
            bits.skipBytes(1)
        }
        val tables = mutableListOf<StyleTable>()
        var p = readStyles(bits.bytePos, tag.code, tables)
        var reader = Bits(body, p)
        var fillBits = reader.u(4)
        var lineBits = reader.u(4)
        val edges = mutableListOf<SwfEdge>()
        var x = 0
        var y = 0
        var fill0 = 0
        var fill1 = 0
        var line = 0
        while (true) {
            if (reader.u(1) == 0) {
                val flags = reader.u(5)
                if (flags == 0) break
                if (flags and 1 != 0) {
                    val n = reader.u(5)
                    x = reader.s(n)
                    y = reader.s(n)
                }
                if (flags and 2 != 0) fill0 = reader.u(fillBits)
                if (flags and 4 != 0) fill1 = reader.u(fillBits)
                if (flags and 8 != 0) line = reader.u(lineBits)
                if (flags and 16 != 0) {
                    p = readStyles(reader.bytePos, tag.code, tables)
                    reader = Bits(body, p)
                    fillBits = reader.u(4)
                    lineBits = reader.u(4)
                }
            } else if (reader.u(1) == 1) {
                val n = reader.u(4) + 2
                var dx = 0
                var dy = 0
                if (reader.u(1) == 1) {
                    dx = reader.s(n)
                    dy = reader.s(n)
                } else if (reader.u(1) == 1) {
                    dy = reader.s(n)
                } else {
                    dx = reader.s(n)
                }
                edges += SwfEdge(tables.size - 1, fill0, fill1, line, x, y, null, null, x + dx, y + dy)
                x += dx
                y += dy
            } else {
                val n = reader.u(4) + 2
                val cx = x + reader.s(n)
                val cy = y + reader.s(n)
                val ax = cx + reader.s(n)
                val ay = cy + reader.s(n)
                edges += SwfEdge(tables.size - 1, fill0, fill1, line, x, y, cx, cy, ax, ay)
                x = ax
                y = ay
            }
        }
        return SwfShape(bounds, tables, edges)
    }

    // FILLSTYLEARRAY then LINESTYLEARRAY; only solid fills and plain lines (all the HUD map uses)
    private fun readStyles(start: Int, code: Int, tables: MutableList<StyleTable>): Int {
        var p = start
        val withAlpha = code == DEFINE_SHAPE3 || code == DEFINE_SHAPE4
        fun count(): Int {
            var n = body[p++].toInt() and 0xFF
            if (n == 0xFF) {
                n = u16(body, p)
                p += 2
            }
            return n
        }
        fun color(): Int {
            val r = body[p].toInt() and 0xFF
            val g = body[p + 1].toInt() and 0xFF
            val b = body[p + 2].toInt() and 0xFF
            val a = if (withAlpha) body[p + 3].toInt() and 0xFF else 0xFF
            p += if (withAlpha) 4 else 3
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        val fills = List(count()) {
            val type = body[p++].toInt() and 0xFF
            require(type == 0) { "Unsupported fill type $type" }
            color()
        }
        val lines = List(count()) {
            val width = u16(body, p)
            p += 2
            if (code == DEFINE_SHAPE4) {
                val flags = body[p].toInt() and 0xFF
                p += 2
                require(flags and 0x08 == 0) { "Unsupported line fill" }
                if ((flags shr 4) and 3 == 2) p += 2 // miter limit
            }
            SwfLineStyle(width, color())
        }
        tables += StyleTable(fills, lines)
        return p
    }

    private fun parseFont(tag: SwfTag): SwfFont? {
        var p = tag.start + 2
        val flags = body[p].toInt() and 0xFF
        p += 2 // flags, language
        val nameLength = body[p++].toInt() and 0xFF
        val name = String(body, p, nameLength, Charsets.ISO_8859_1).trimEnd('\u0000')
        p += nameLength
        val count = u16(body, p)
        p += 2
        val wideOffsets = flags and 0x08 != 0
        val wideCodes = flags and 0x04 != 0
        val tableStart = p
        val offsets = IntArray(count + 1) { i -> if (wideOffsets) u32(body, tableStart + i * 4) else u16(body, tableStart + i * 2) }
        val glyphs = (0 until count).map { i -> glyphContours(tableStart + offsets[i]) }
        p = tableStart + offsets[count]
        val codes = IntArray(count) { i -> if (wideCodes) u16(body, p + i * 2) else body[p + i].toInt() and 0xFF }
        p += count * if (wideCodes) 2 else 1
        if (flags and 0x80 == 0) return null // no layout: no advances to set text with
        val ascent = u16(body, p)
        val descent = u16(body, p + 2)
        val leading = s16(body, p + 4)
        p += 6
        val advances = IntArray(count) { i -> s16(body, p + i * 2) }
        return SwfFont(name, ascent, descent, leading, (0 until count).map { SwfGlyph(codes[it], advances[it], glyphs[it]) })
    }

    // A glyph is a SHAPE without style arrays: each move starts a contour
    private fun glyphContours(start: Int): List<List<GlyphPoint>> {
        val reader = Bits(body, start)
        val fillBits = reader.u(4)
        val lineBits = reader.u(4)
        val contours = mutableListOf<MutableList<GlyphPoint>>()
        var current: MutableList<GlyphPoint>? = null
        var x = 0
        var y = 0
        fun begin() = mutableListOf(GlyphPoint(x, y, true)).also { contours += it; current = it }
        while (true) {
            if (reader.u(1) == 0) {
                val flags = reader.u(5)
                if (flags == 0) break
                if (flags and 1 != 0) {
                    val n = reader.u(5)
                    x = reader.s(n)
                    y = reader.s(n)
                    current = null
                }
                if (flags and 2 != 0) reader.u(fillBits)
                if (flags and 4 != 0) reader.u(fillBits)
                if (flags and 8 != 0) reader.u(lineBits)
            } else if (reader.u(1) == 1) {
                val n = reader.u(4) + 2
                val contour = current ?: begin()
                if (reader.u(1) == 1) {
                    x += reader.s(n)
                    y += reader.s(n)
                } else if (reader.u(1) == 1) {
                    y += reader.s(n)
                } else {
                    x += reader.s(n)
                }
                contour += GlyphPoint(x, y, true)
            } else {
                val n = reader.u(4) + 2
                val contour = current ?: begin()
                x += reader.s(n)
                y += reader.s(n)
                contour += GlyphPoint(x, y, false)
                x += reader.s(n)
                y += reader.s(n)
                contour += GlyphPoint(x, y, true)
            }
        }
        // A closed contour repeats its first point at the end; TrueType closes implicitly
        for (contour in contours) {
            if (contour.size > 1 && contour.last() == contour.first()) contour.removeAt(contour.size - 1)
        }
        return contours.filter { it.size >= 3 }
    }

    companion object {
        const val END = 0
        const val DEFINE_SHAPE = 2
        const val DEFINE_SHAPE2 = 22
        const val DEFINE_SHAPE3 = 32
        const val DEFINE_SHAPE4 = 83
        const val PLACE_OBJECT2 = 26
        const val PLACE_OBJECT3 = 70
        const val DEFINE_EDIT_TEXT = 37
        const val DEFINE_SPRITE = 39
        const val DEFINE_FONT3 = 75
        val SHAPE_TAGS = setOf(DEFINE_SHAPE, DEFINE_SHAPE2, DEFINE_SHAPE3, DEFINE_SHAPE4)

        private fun inflate(data: ByteArray, from: Int): ByteArray {
            val inflater = Inflater()
            inflater.setInput(data, from, data.size - from)
            val out = java.io.ByteArrayOutputStream(data.size * 3)
            val buffer = ByteArray(65536)
            try {
                while (!inflater.finished()) {
                    val n = inflater.inflate(buffer)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    out.write(buffer, 0, n)
                }
            } finally {
                inflater.end()
            }
            return out.toByteArray()
        }
    }
}

internal data class SwfTag(val code: Int, val start: Int, val length: Int, val sprite: Int)

internal data class SwfMatrix(
    val scaleX: Float = 1f,
    val rotate0: Float = 0f,
    val rotate1: Float = 0f,
    val scaleY: Float = 1f,
    val translateX: Int = 0,
    val translateY: Int = 0,
)

internal data class SwfPlacement(val sprite: Int, val character: Int, val matrix: SwfMatrix, val name: String?)

internal data class SwfEditText(val bounds: IntArray, val heightTwips: Int, val argb: Int, val text: String)

internal data class SwfLineStyle(val widthTwips: Int, val argb: Int)

internal data class StyleTable(val fills: List<Int>, val lines: List<SwfLineStyle>)

/** An edge in twips; [controlX]/[controlY] are set for a quadratic curve. Styles index the shape's [table] (1-based, 0 = none). */
internal data class SwfEdge(
    val table: Int,
    val fill0: Int,
    val fill1: Int,
    val line: Int,
    val x0: Int,
    val y0: Int,
    val controlX: Int?,
    val controlY: Int?,
    val x1: Int,
    val y1: Int,
)

internal class SwfShape(val bounds: IntArray, val tables: List<StyleTable>, val edges: List<SwfEdge>)

internal data class GlyphPoint(val x: Int, val y: Int, val onCurve: Boolean)

/** A glyph in font units (1024 × 20 per em, y down, baseline at 0). */
internal class SwfGlyph(val code: Int, val advance: Int, val contours: List<List<GlyphPoint>>)

internal class SwfFont(val name: String, val ascent: Int, val descent: Int, val leading: Int, val glyphs: List<SwfGlyph>)

/** SWF bit fields: most significant bit first, signed values two's complement. */
internal class Bits(private val data: ByteArray, start: Int) {
    private var bit = start.toLong() * 8

    val bytePos: Int get() = ((bit + 7) ushr 3).toInt()

    fun u(n: Int): Int {
        var v = 0
        repeat(n) {
            val byte = data[(bit ushr 3).toInt()].toInt()
            v = (v shl 1) or ((byte shr (7 - (bit and 7).toInt())) and 1)
            bit++
        }
        return v
    }

    fun s(n: Int): Int {
        if (n == 0) return 0
        val v = u(n)
        return if (v and (1 shl (n - 1)) != 0) v - (1 shl n) else v
    }

    fun skipBytes(n: Int) {
        bit = (bytePos + n).toLong() * 8
    }

    private fun align() {
        bit = bytePos.toLong() * 8
    }

    /** RECT (byte aligned): xMin, xMax, yMin, yMax in twips. */
    fun rect(): IntArray {
        align()
        val n = u(5)
        return intArrayOf(s(n), s(n), s(n), s(n))
    }

    fun matrix(): SwfMatrix {
        align()
        var sx = 1f
        var sy = 1f
        var r0 = 0f
        var r1 = 0f
        if (u(1) == 1) {
            val n = u(5)
            sx = s(n) / 65536f
            sy = s(n) / 65536f
        }
        if (u(1) == 1) {
            val n = u(5)
            r0 = s(n) / 65536f
            r1 = s(n) / 65536f
        }
        val n = u(5)
        return SwfMatrix(sx, r0, r1, sy, s(n), s(n))
    }
}

private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

private fun s16(b: ByteArray, at: Int) = u16(b, at).toShort().toInt()

private fun u32(b: ByteArray, at: Int) = u16(b, at) or (u16(b, at + 2) shl 16)

private fun cstring(b: ByteArray, at: Int): Pair<String, Int> {
    var end = at
    while (end < b.size && b[end] != 0.toByte()) end++
    return String(b, at, end - at, Charsets.ISO_8859_1) to end + 1
}
