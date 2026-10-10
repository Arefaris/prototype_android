package app.gamenative.prototype.companion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/** What the helper answered: [ok] is false for a Lua error, [lines] are the returned values (or the error). */
data class HelperResult(val ok: Boolean, val lines: List<String>)

/**
 * Talks to the helper inside the game (the opprobe d3d9 proxy) through files in the game folder: a request is
 * written to `opprobe_cmd.request` (atomically, via a temp file and a rename); the proxy picks it up every second
 * frame, runs it on the game's main thread and writes `opprobe_cmd.result` ("ok"/"error", then one value per line).
 */
class GameBridge(gameDir: File) {
    private val request = File(gameDir, "opprobe_cmd.request")
    private val requestTmp = File(gameDir, "opprobe_cmd.request.tmp")
    private val result = File(gameDir, "opprobe_cmd.result")
    private val mutex = Mutex()

    /** Runs a Lua chunk in the game; null when the game did not pick the request up in time. */
    suspend fun lua(chunk: String, timeoutMs: Long = 1500): HelperResult? = command("lua $chunk", timeoutMs)

    /** The city's zone objects, one "type|x|z|radius|active|effects|strength" line each (the proxy's "zones"). */
    suspend fun zones(): HelperResult? = command("zones", 1500)

    private suspend fun command(text: String, timeoutMs: Long): HelperResult? = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                result.delete()
                request.delete()
                requestTmp.writeText(text)
                if (!requestTmp.renameTo(request)) {
                    requestTmp.delete()
                    return@withContext null
                }
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    if (result.isFile) {
                        val lines = result.readLines()
                        result.delete()
                        return@withContext HelperResult(lines.firstOrNull() == "ok", lines.drop(1))
                    }
                    delay(POLL_MS)
                }
                // Not picked up (no frames are presented, or no helper): withdraw it so it can't run later
                request.delete()
                null
            } catch (e: Exception) {
                Timber.w(e, "Companion request failed")
                null
            }
        }
    }

    private companion object {
        const val POLL_MS = 15L
    }
}
