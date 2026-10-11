package app.gamenative.prototype.companion

import android.graphics.Bitmap
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * Reads files out of the player's own copy of the game (nothing from it is shipped): the .rcf archives, their zlib
 * "RZ" containers, Pure3D texture chunks and DDS images. Formats as in open_prototype components/vfs (RCF 2.1, RZ)
 * and components/p3d (chunks, textures).
 */
class GameFiles(private val gameDir: File) {
    // art.rcf first: that is where the HUD art lives
    private val archives by lazy {
        gameDir.listFiles { f -> f.name.endsWith(".rcf", ignoreCase = true) }.orEmpty()
            .sortedBy { if (it.name.startsWith("art", ignoreCase = true)) 0 else 1 }
    }

    /** A file by its archive path ("art/hud/protomap.gfx"); a stored "….rz" copy is unpacked. */
    fun read(path: String): ByteArray? {
        for (archive in archives) {
            val stored = try {
                Rcf.read(archive, path.lowercase())
            } catch (e: Exception) {
                Timber.w(e, "Reading ${archive.name} failed")
                null
            } ?: continue
            return if (Rz.isRz(stored)) Rz.decompress(stored) else stored
        }
        return null
    }

    /** The DDS files of the Pure3D package at [path], by texture name without ".dds". */
    fun textures(path: String, wanted: (String) -> Boolean = { true }): Map<String, ByteArray> =
        read(path)?.let { P3d.textures(it, wanted) }.orEmpty()
}

/** Radical "ATG CORE CEMENT LIBRARY" 2.1 archive: header, a directory sorted by hash, a name table sorted by offset. */
private object Rcf {
    private const val HEADER_SIZE = 0x3C
    private const val MAGIC = "ATG CORE CEMENT LIBRARY"

    fun read(file: File, wanted: String): ByteArray? = RandomAccessFile(file, "r").use { raf ->
        val header = raf.bytesAt(0, HEADER_SIZE)
        if (String(header, 0, MAGIC.length, Charsets.US_ASCII) != MAGIC) return null
        val h = header.le()
        val directoryOffset = h.getInt(0x24).toLong()
        val namesOffset = h.getInt(0x2C).toLong()
        val namesSize = h.getInt(0x30)
        val count = h.getInt(0x38)

        val directory = raf.bytesAt(directoryOffset, count * 12).le()
        val byOffset = (0 until count).map { i -> directory.getInt(i * 12 + 4).toLong() to directory.getInt(i * 12 + 8) }
            .sortedBy { it.first }

        val names = raf.bytesAt(namesOffset, namesSize).le()
        names.position(8) // alignment, reserved
        for ((offset, size) in byOffset) {
            names.position(names.position() + 12) // timestamp, alignment, reserved
            val length = names.getInt()
            val raw = ByteArray(length).also { names.get(it) }
            names.position(names.position() + 3)
            val name = normalize(String(raw, Charsets.US_ASCII).trimEnd('\u0000'))
            if (name == wanted || name == "$wanted.rz") return raf.bytesAt(offset, size)
        }
        null
    }

    private fun normalize(name: String) = name.replace('\\', '/').trimStart('/').lowercase()
}

/** "RZ\0\0", u32 reserved, u32 uncompressed size, u32 reserved, then a zlib stream. */
private object Rz {
    fun isRz(data: ByteArray) = data.size >= 16 && data[0] == 'R'.code.toByte() && data[1] == 'Z'.code.toByte() &&
        data[2] == 0.toByte() && data[3] == 0.toByte()

    fun decompress(data: ByteArray): ByteArray {
        val output = ByteArray(data.le().getInt(8))
        val inflater = Inflater()
        try {
            inflater.setInput(data, 16, data.size - 16)
            var done = 0
            while (done < output.size && !inflater.finished()) {
                val n = inflater.inflate(output, done, output.size - done)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                done += n
            }
        } finally {
            inflater.end()
        }
        return output
    }
}

/** Pure3D chunks: u32 id, u32 header size, u32 total size, payload, then child chunks up to the total size. */
private object P3d {
    private const val TEXTURE = 0x00019000
    private const val IMAGE = 0x00019006
    private const val IMAGE_DATA = 0x00019002

    fun textures(data: ByteArray, wanted: (String) -> Boolean): Map<String, ByteArray> {
        val found = LinkedHashMap<String, ByteArray>()
        search(data.le(), 0, data.size, wanted, found)
        return found
    }

    private fun search(b: ByteBuffer, offset: Int, limit: Int, wanted: (String) -> Boolean, found: MutableMap<String, ByteArray>) {
        var pos = offset
        while (pos + 12 <= limit) {
            val id = b.getInt(pos)
            val headerSize = b.getInt(pos + 4)
            val totalSize = b.getInt(pos + 8)
            if (headerSize < 12 || totalSize < headerSize || pos + totalSize > limit) return
            if (id == TEXTURE) {
                // Texture names carry the source file's extension ("protohud__I1C7.dds")
                val name = pstring(b, pos + 12).removeSuffix(".dds")
                if (wanted(name)) imageData(b, pos + headerSize, pos + totalSize)?.let { found[name] = it }
            } else {
                search(b, pos + headerSize, pos + totalSize, wanted, found)
            }
            pos += totalSize
        }
    }

    // The texture's IMAGE child holds IMAGE_DATA: u32 size, then the DDS file
    private fun imageData(b: ByteBuffer, offset: Int, limit: Int): ByteArray? {
        var pos = offset
        while (pos + 12 <= limit) {
            val id = b.getInt(pos)
            val headerSize = b.getInt(pos + 4)
            val totalSize = b.getInt(pos + 8)
            if (headerSize < 12 || totalSize < headerSize) return null
            if (id == IMAGE_DATA) {
                val size = b.getInt(pos + 12)
                return ByteArray(size).also { out -> b.duplicate().apply { position(pos + 16) }.get(out) }
            }
            if (id == IMAGE) imageData(b, pos + headerSize, pos + totalSize)?.let { return it }
            pos += totalSize
        }
        return null
    }

    private fun pstring(b: ByteBuffer, at: Int): String {
        val length = b.get(at).toInt() and 0xFF
        val bytes = ByteArray(length).also { out -> b.duplicate().apply { position(at + 1) }.get(out) }
        return String(bytes, Charsets.US_ASCII).trimEnd('\u0000')
    }
}

/** DDS with DXT1 or DXT5 data: the top mip level only. The game's HUD images are stored bottom row first. */
object Dds {
    fun decode(dds: ByteArray, flip: Boolean = true): Bitmap? {
        val b = dds.le()
        if (dds.size < 128 || b.getInt(0) != 0x20534444) return null // "DDS "
        val height = b.getInt(12)
        val width = b.getInt(16)
        val fourCc = String(dds, 84, 4, Charsets.US_ASCII)
        val blockSize = when (fourCc) {
            "DXT1" -> 8
            "DXT5" -> 16
            else -> return null.also { Timber.w("Unsupported texture format $fourCc") }
        }
        if (128L + ((width + 3) / 4).toLong() * ((height + 3) / 4) * blockSize > dds.size) return null
        val pixels = IntArray(width * height)
        var src = 128
        for (by in 0 until (height + 3) / 4) {
            for (bx in 0 until (width + 3) / 4) {
                val alpha = if (blockSize == 16) alphaBlock(b, src) else null
                colorBlock(b, if (blockSize == 16) src + 8 else src, alpha, pixels, width, height, bx * 4, by * 4)
                src += blockSize
            }
        }
        if (flip) {
            val row = IntArray(width)
            for (y in 0 until height / 2) {
                val top = y * width
                val bottom = (height - 1 - y) * width
                System.arraycopy(pixels, top, row, 0, width)
                System.arraycopy(pixels, bottom, pixels, top, width)
                System.arraycopy(row, 0, pixels, bottom, width)
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    // BC3 alpha: two 8-bit endpoints, then 16 three-bit indices
    private fun alphaBlock(b: ByteBuffer, at: Int): IntArray {
        val a0 = b.get(at).toInt() and 0xFF
        val a1 = b.get(at + 1).toInt() and 0xFF
        val table = IntArray(8)
        table[0] = a0
        table[1] = a1
        if (a0 > a1) {
            for (i in 1..6) table[i + 1] = ((7 - i) * a0 + i * a1) / 7
        } else {
            for (i in 1..4) table[i + 1] = ((5 - i) * a0 + i * a1) / 5
            table[6] = 0
            table[7] = 255
        }
        var bits = 0L
        for (i in 0 until 6) bits = bits or ((b.get(at + 2 + i).toLong() and 0xFF) shl (8 * i))
        return IntArray(16) { table[((bits shr (3 * it)) and 7).toInt()] }
    }

    private fun colorBlock(b: ByteBuffer, at: Int, alpha: IntArray?, out: IntArray, width: Int, height: Int, x0: Int, y0: Int) {
        val c0 = b.getShort(at).toInt() and 0xFFFF
        val c1 = b.getShort(at + 2).toInt() and 0xFFFF
        val colors = IntArray(4)
        colors[0] = rgb565(c0)
        colors[1] = rgb565(c1)
        // DXT5 color blocks always use four colors; DXT1 uses three plus transparent when c0 <= c1
        if (alpha != null || c0 > c1) {
            colors[2] = mix(colors[0], colors[1], 2, 1, 3)
            colors[3] = mix(colors[0], colors[1], 1, 2, 3)
        } else {
            colors[2] = mix(colors[0], colors[1], 1, 1, 2)
            colors[3] = 0
        }
        val indices = b.getInt(at + 4)
        for (i in 0 until 16) {
            val x = x0 + (i and 3)
            val y = y0 + (i shr 2)
            if (x >= width || y >= height) continue
            val index = (indices ushr (2 * i)) and 3
            val argb = when {
                alpha != null -> (colors[index] and 0x00FFFFFF) or (alpha[i] shl 24)
                index == 3 && c0 <= c1 -> 0
                else -> colors[index] or (0xFF shl 24)
            }
            out[y * width + x] = argb
        }
    }

    private fun rgb565(c: Int): Int {
        val r = (c shr 11 and 0x1F) * 255 / 31
        val g = (c shr 5 and 0x3F) * 255 / 63
        val bl = (c and 0x1F) * 255 / 31
        return (r shl 16) or (g shl 8) or bl
    }

    private fun mix(a: Int, b: Int, wa: Int, wb: Int, div: Int): Int {
        fun ch(shift: Int) = (((a shr shift and 0xFF) * wa + (b shr shift and 0xFF) * wb) / div) shl shift
        return ch(16) or ch(8) or ch(0)
    }
}

internal fun ByteArray.le(): ByteBuffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)

private fun RandomAccessFile.bytesAt(offset: Long, size: Int): ByteArray {
    seek(offset)
    return ByteArray(size).also { readFully(it) }
}
