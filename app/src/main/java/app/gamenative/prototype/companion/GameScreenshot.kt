package app.gamenative.prototype.companion

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Saves the game's current frame (the X server's surface) as a PNG in Pictures/Prototype. */
object GameScreenshot {
    fun capture(context: Context, view: SurfaceView?, onDone: (File?) -> Unit) {
        if (view == null || view.width == 0 || view.height == 0) {
            onDone(null)
            return
        }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val main = Handler(Looper.getMainLooper())
        PixelCopy.request(view, bitmap, { result ->
            if (result != PixelCopy.SUCCESS) {
                Timber.w("Screenshot PixelCopy failed: $result")
                onDone(null)
                return@request
            }
            thread(name = "prototype-screenshot") {
                val file = save(bitmap)
                file?.let { MediaScannerConnection.scanFile(context, arrayOf(it.absolutePath), arrayOf("image/png"), null) }
                main.post { onDone(file) }
            }
        }, main)
    }

    private fun save(bitmap: Bitmap): File? = try {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Prototype").apply { mkdirs() }
        val name = "prototype_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
        File(dir, name).also { file -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    } catch (e: Exception) {
        Timber.e(e, "Saving screenshot failed")
        null
    } finally {
        bitmap.recycle()
    }
}
