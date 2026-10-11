package app.gamenative.prototype.companion

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.math.roundToInt

/**
 * Turns a Scaleform font (DefineFont3) into a minimal TrueType file, so the game's own lettering can be used as a
 * regular Typeface. SWF glyphs are quadratic outlines like TrueType's, so the points carry over as they are:
 * 1024 units per em (SWF uses 1024 × 20), y flipped (SWF is y-down). No hinting, no kerning.
 */
internal object TtfWriter {
    private const val UNITS_PER_EM = 1024

    private class Glyph(val code: Int, val advance: Int, val contours: List<List<GlyphPoint>>) {
        val points = contours.flatten()
        val xMin = points.minOfOrNull { it.x } ?: 0
        val yMin = points.minOfOrNull { it.y } ?: 0
        val xMax = points.maxOfOrNull { it.x } ?: 0
        val yMax = points.maxOfOrNull { it.y } ?: 0
    }

    fun write(font: SwfFont, family: String, bold: Boolean): ByteArray {
        // Glyph 0 is .notdef (empty); the font's glyphs follow, converted to font units with y up
        val glyphs = listOf(Glyph(-1, UNITS_PER_EM / 2, emptyList())) + font.glyphs.map { g ->
            Glyph(
                g.code,
                (g.advance / 20f).roundToInt(),
                g.contours.map { c -> c.map { GlyphPoint((it.x / 20f).roundToInt(), (-it.y / 20f).roundToInt(), it.onCurve) } },
            )
        }
        val ascent = (font.ascent / 20f).roundToInt()
        val descent = (font.descent / 20f).roundToInt()
        val lineGap = (font.leading / 20f).roundToInt().coerceAtLeast(0)

        val glyf = ByteArrayOutputStream()
        val offsets = IntArray(glyphs.size + 1)
        glyphs.forEachIndexed { i, g ->
            offsets[i] = glyf.size()
            if (g.contours.isNotEmpty()) glyf.write(glyphData(g))
            while (glyf.size() % 4 != 0) glyf.write(0)
        }
        offsets[glyphs.size] = glyf.size()

        val drawn = glyphs.filter { it.contours.isNotEmpty() }
        val xMin = drawn.minOfOrNull { it.xMin } ?: 0
        val yMin = drawn.minOfOrNull { it.yMin } ?: 0
        val xMax = drawn.maxOfOrNull { it.xMax } ?: 0
        val yMax = drawn.maxOfOrNull { it.yMax } ?: 0
        val mapped = glyphs.withIndex().filter { it.value.code in 0..0xFFFE }.sortedBy { it.value.code }
            .distinctBy { it.value.code }

        val tables = sortedMapOf(
            "OS/2" to os2(glyphs, mapped, ascent, descent, lineGap, bold),
            "cmap" to cmap(mapped.map { it.value.code to it.index }),
            "glyf" to glyf.toByteArray(),
            "head" to bytes {
                writeInt(0x00010000)
                writeInt(0x00010000)
                writeInt(0) // checksum adjustment, not checked by the loaders
                writeInt(0x5F0F3CF5)
                writeShort(0x0003)
                writeShort(UNITS_PER_EM)
                writeLong(0)
                writeLong(0)
                writeShort(xMin)
                writeShort(yMin)
                writeShort(xMax)
                writeShort(yMax)
                writeShort(if (bold) 1 else 0)
                writeShort(8)
                writeShort(2)
                writeShort(1) // long loca
                writeShort(0)
            },
            "hhea" to bytes {
                writeInt(0x00010000)
                writeShort(ascent)
                writeShort(-descent)
                writeShort(lineGap)
                writeShort(glyphs.maxOf { it.advance })
                writeShort(drawn.minOfOrNull { it.xMin } ?: 0)
                writeShort(drawn.minOfOrNull { it.advance - it.xMax } ?: 0)
                writeShort(xMax)
                writeShort(1)
                writeShort(0)
                writeShort(0)
                repeat(4) { writeShort(0) }
                writeShort(0)
                writeShort(glyphs.size)
            },
            "hmtx" to bytes {
                for (g in glyphs) {
                    writeShort(g.advance)
                    writeShort(g.xMin)
                }
            },
            "loca" to bytes { offsets.forEach { writeInt(it) } },
            "maxp" to bytes {
                writeInt(0x00010000)
                writeShort(glyphs.size)
                writeShort(glyphs.maxOf { it.points.size })
                writeShort(glyphs.maxOf { it.contours.size })
                writeShort(0)
                writeShort(0)
                writeShort(2)
                repeat(8) { writeShort(0) }
            },
            "name" to name(family, if (bold) "Bold" else "Regular"),
            "post" to bytes {
                writeInt(0x00030000)
                writeInt(0)
                writeShort(-100)
                writeShort(50)
                repeat(5) { writeInt(0) }
            },
        )
        return assemble(tables)
    }

    private fun glyphData(g: Glyph): ByteArray = bytes {
        writeShort(g.contours.size)
        writeShort(g.xMin)
        writeShort(g.yMin)
        writeShort(g.xMax)
        writeShort(g.yMax)
        var end = -1
        for (c in g.contours) {
            end += c.size
            writeShort(end)
        }
        writeShort(0) // no instructions
        // Flags: on-curve bit only, so both coordinates are written as 16-bit deltas
        for (p in g.points) writeByte(if (p.onCurve) 1 else 0)
        var last = 0
        for (p in g.points) {
            writeShort(p.x - last)
            last = p.x
        }
        last = 0
        for (p in g.points) {
            writeShort(p.y - last)
            last = p.y
        }
    }

    // Format 4, one segment per character (a few hundred at most), then the closing 0xFFFF segment
    private fun cmap(codes: List<Pair<Int, Int>>): ByteArray {
        val segments = codes + (0xFFFF to 0)
        val segCount = segments.size
        val searchRange = 2 * Integer.highestOneBit(segCount)
        val subtable = bytes {
            writeShort(4)
            writeShort(16 + segCount * 8)
            writeShort(0)
            writeShort(segCount * 2)
            writeShort(searchRange)
            writeShort(Integer.numberOfTrailingZeros(searchRange / 2))
            writeShort(segCount * 2 - searchRange)
            segments.forEach { writeShort(it.first) }
            writeShort(0)
            segments.forEach { writeShort(it.first) }
            segments.forEach { (code, glyph) -> writeShort(if (code == 0xFFFF) 1 else (glyph - code) and 0xFFFF) }
            segments.forEach { writeShort(0) }
        }
        return bytes {
            writeShort(0)
            writeShort(1)
            writeShort(3)
            writeShort(1)
            writeInt(12)
            write(subtable)
        }
    }

    private fun os2(glyphs: List<Glyph>, mapped: List<IndexedValue<Glyph>>, ascent: Int, descent: Int, lineGap: Int, bold: Boolean) = bytes {
        writeShort(4)
        writeShort(glyphs.filter { it.advance > 0 }.map { it.advance }.average().roundToInt())
        writeShort(if (bold) 700 else 400)
        writeShort(5)
        writeShort(0)
        repeat(10) { writeShort(0) } // sub/superscript, strikeout
        writeShort(0)
        repeat(10) { writeByte(0) } // panose
        writeInt(1) // Basic Latin
        repeat(3) { writeInt(0) }
        writeBytes("PRTA")
        writeShort(if (bold) 0x20 else 0x40)
        writeShort(mapped.firstOrNull()?.value?.code ?: 0x20)
        writeShort(mapped.lastOrNull()?.value?.code ?: 0x7E)
        writeShort(ascent)
        writeShort(-descent)
        writeShort(lineGap)
        writeShort(ascent)
        writeShort(descent)
        writeInt(1) // Latin 1
        writeInt(0)
        writeShort(ascent / 2)
        writeShort(ascent * 2 / 3)
        writeShort(0)
        writeShort(0x20)
        writeShort(1)
    }

    private fun name(family: String, style: String): ByteArray {
        val strings = listOf(1 to family, 2 to style, 4 to "$family $style", 6 to "$family-$style".replace(" ", ""))
        val encoded = strings.map { it.first to it.second.toByteArray(Charsets.UTF_16BE) }
        return bytes {
            writeShort(0)
            writeShort(encoded.size)
            writeShort(6 + encoded.size * 12)
            var offset = 0
            for ((id, data) in encoded) {
                writeShort(3)
                writeShort(1)
                writeShort(0x0409)
                writeShort(id)
                writeShort(data.size)
                writeShort(offset)
                offset += data.size
            }
            encoded.forEach { write(it.second) }
        }
    }

    // Offset table and table directory (tags sorted), then the tables, each padded to four bytes
    private fun assemble(tables: Map<String, ByteArray>): ByteArray {
        val count = tables.size
        val searchRange = 16 * Integer.highestOneBit(count)
        return bytes {
            writeInt(0x00010000)
            writeShort(count)
            writeShort(searchRange)
            writeShort(Integer.numberOfTrailingZeros(searchRange / 16))
            writeShort(count * 16 - searchRange)
            var offset = 12 + count * 16
            for ((tag, data) in tables) {
                writeBytes(tag)
                writeInt(checksum(data))
                writeInt(offset)
                writeInt(data.size)
                offset += (data.size + 3) and 3.inv()
            }
            for (data in tables.values) {
                write(data)
                repeat(((data.size + 3) and 3.inv()) - data.size) { writeByte(0) }
            }
        }
    }

    private fun checksum(data: ByteArray): Int {
        var sum = 0
        for (i in data.indices step 4) {
            var word = 0
            for (k in 0 until 4) word = (word shl 8) or (if (i + k < data.size) data[i + k].toInt() and 0xFF else 0)
            sum += word
        }
        return sum
    }

    private inline fun bytes(block: DataOutputStream.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply(block).flush()
        return out.toByteArray()
    }
}
