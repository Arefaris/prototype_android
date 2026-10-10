package app.gamenative.prototype

import android.content.Context
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.prototype.companion.CompanionController
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.CustomGameScanner
import app.gamenative.utils.GameMetadata
import app.gamenative.utils.GameMetadataManager
import com.winlator.container.Container
import com.winlator.fexcore.FEXCorePreset
import com.winlator.xenvironment.ImageFs
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

/**
 * Everything specific to Prototype: where the game lives, its DRM state and its container setup.
 * The game is a regular GameNative custom game with a fixed id, so the stock launch path does the rest.
 */
object PrototypeGame {
    const val FOLDER_NAME = "prt"
    const val EXE_NAME = "prototypef.exe"

    // Steam appid of Prototype; reused as the custom game id so the container id never changes
    const val GAME_ID = 10150
    val APP_ID = "${GameSource.CUSTOM_GAME.name}_$GAME_ID"

    // Optional JSON with container overrides, read from the game folder (used to tune settings over adb)
    private const val DEV_CONFIG_NAME = ".prt-config.json"
    private const val DEV_DEBUG_NAME = ".prt-debug"
    private const val SPLIT_LOCK_ENV = "FEX_STRICTINPROCESSSPLITLOCKS=1"
    const val SAVES_DIR = "saves"

    // The companion helper, shipped in assets/companion and copied into the game folder
    private const val HELPER_ASSETS = "companion"
    private const val HELPER_DLL = "d3d9.dll"
    private const val HELPER_INI = "opprobe_d3d9.ini"

    fun isPrototype(appId: String?): Boolean = appId == APP_ID

    // Where GameNative puts a Steam download on a storage volume: its public install root, or Android/data in older
    // versions (only readable where Android still allows it). Its default, the app's private data, is not reachable.
    private val GAMENATIVE_INSTALLS = listOf(
        "GameNative/Steam/steamapps/common/Prototype",
        "Android/data/app.gamenative/files/Steam/steamapps/common/Prototype",
    )

    /**
     * Finds the game: `prt` at the root of internal storage or of any mounted SD card first, then a copy that
     * GameNative downloaded to one of those volumes, so it does not have to be moved.
     */
    fun findGameDir(): File? {
        val roots = buildList {
            add(File("/storage/emulated/0"))
            File("/storage").listFiles()
                ?.filter { it.isDirectory && it.name != "emulated" && it.name != "self" }
                ?.let { addAll(it) }
        }
        val candidates = roots.map { File(it, FOLDER_NAME) } + roots.flatMap { root -> GAMENATIVE_INSTALLS.map { File(root, it) } }
        return candidates.firstOrNull { isGameDir(it) }
    }

    private fun isGameDir(dir: File): Boolean =
        File(dir, EXE_NAME).isFile && dir.listFiles { f -> f.name.endsWith(".rcf", ignoreCase = true) }?.isNotEmpty() == true

    /** True when the exe still carries the SteamStub wrapper, which lives in a PE section named `.bind`. */
    fun hasSteamStub(exe: File): Boolean = peSectionNames(exe).contains(".bind")

    private fun peSectionNames(exe: File): List<String> = try {
        RandomAccessFile(exe, "r").use { f ->
            fun u16(at: Long): Int { f.seek(at); return f.read() or (f.read() shl 8) }
            fun u32(at: Long): Long { f.seek(at); return (u16(at).toLong()) or (u16(at + 2).toLong() shl 16) }
            val pe = u32(0x3C)
            val count = u16(pe + 6)
            val optSize = u16(pe + 20)
            val table = pe + 24 + optSize
            (0 until count).map { i ->
                val name = ByteArray(8)
                f.seek(table + 40L * i)
                f.readFully(name)
                String(name, Charsets.US_ASCII).trimEnd('\u0000')
            }
        }
    } catch (e: Exception) {
        Timber.w(e, "Could not read PE sections of $exe")
        emptyList()
    }

    /**
     * Puts the companion helper next to the game when there is a second screen to show the companion on: the opprobe
     * d3d9 proxy (open_prototype tools/re/d3d9proxy), which forwards to DXVK and runs the second screen's Lua
     * requests, plus its handheld ini. Any other d3d9.dll there (PC mods, overlays, an older helper) is renamed to
     * d3d9.dll.disabled first, because it would load instead. Without a second screen the helper is taken out again,
     * so the game talks to DXVK directly.
     */
    fun installCompanionHelper(context: Context, gameDir: File) {
        if (!CompanionController.hasSecondScreen(context)) {
            removeCompanionHelper(gameDir)
            return
        }
        for (name in listOf(HELPER_DLL, HELPER_INI)) {
            val bundled = context.assets.open("$HELPER_ASSETS/$name").use { it.readBytes() }
            val target = File(gameDir, name)
            if (target.isFile && target.readBytes().contentEquals(bundled)) continue
            if (name == HELPER_DLL && target.isFile) {
                val disabled = File(gameDir, "$HELPER_DLL.disabled")
                disabled.delete()
                Timber.i("Moving foreign $target out of the way: ${target.renameTo(disabled)}")
            }
            target.writeBytes(bundled)
        }
    }

    // Only the helper is removed (any version: the proxy names its files "opprobe_…"); a foreign d3d9.dll stays disabled
    private fun removeCompanionHelper(gameDir: File) {
        val dll = File(gameDir, HELPER_DLL)
        if (dll.isFile && String(dll.readBytes(), Charsets.ISO_8859_1).contains("opprobe")) {
            Timber.i("No second screen: removing the companion helper (${dll.delete()})")
        }
        File(gameDir, HELPER_INI).delete()
    }

    /** Registers the folder as the custom game [APP_ID] so GameNative maps it to A:. */
    fun register(gameDir: File) {
        if (GameMetadataManager.getAppId(gameDir) != GAME_ID) {
            GameMetadataManager.write(gameDir, GameMetadata(appId = GAME_ID))
        }
        val path = gameDir.absolutePath
        val others = PrefManager.customGameManualFolders.filter { File(it, EXE_NAME).isFile && it != path }
        if (path !in PrefManager.customGameManualFolders || others.isNotEmpty()) {
            // Keep exactly one Prototype folder, so a copy moved to the SD card replaces the old one
            PrefManager.customGameManualFolders = PrefManager.customGameManualFolders - others.toSet() + path
            CustomGameScanner.invalidateCache()
        }
    }

    /** Creates or updates the container and points it at the current game folder. */
    fun prepareContainer(context: Context, gameDir: File): Container {
        val container = ContainerUtils.getOrCreateContainer(context, APP_ID)

        // The game folder may have moved between internal storage and the SD card
        // drivesIterator reuses one array for every item, so copy each pair out
        val drives = Container.drivesIterator(container.drives).map { it[0] to it[1] }
        if (drives.none { it == ("A" to gameDir.absolutePath) }) {
            val otherDrives = drives.filter { it.first != "A" }.joinToString("") { "${it.first}:${it.second}" }
            container.drives = "${otherDrives}A:${gameDir.absolutePath}"
        }
        container.executablePath = EXE_NAME
        // Start the exe directly instead of through GameNative's Steam launch helpers
        container.isUseLegacyDRM = true
        container.isLaunchRealSteam = false

        // The engine keeps critical sections at odd addresses, so their lock word straddles a 16-byte boundary.
        // ARM64 can't do that atomically; without FEX's strict split-lock mode the lock corrupts and the game deadlocks.
        if (!container.envVars.contains(SPLIT_LOCK_ENV)) {
            container.envVars = "${container.envVars} $SPLIT_LOCK_ENV".trim()
        }

        // The stock launch path runs Steamless on the exe when this flag is set
        if (hasSteamStub(File(gameDir, EXE_NAME))) {
            container.setNeedsUnpacking(true)
        } else {
            // A leftover unpacked copy would be copied over the exe on the next unpack pass (new container),
            // which could downgrade an updated exe; the .original.exe backup is kept
            File(gameDir, "$EXE_NAME.unpacked.exe").delete()
        }

        linkSaves(container, gameDir)

        // Overrides are merged into the saved JSON, so the changes above must be on disk first
        container.saveData()
        mergeConfig(container, bakedConfig())
        readDevConfig(File(gameDir, DEV_CONFIG_NAME))?.let { mergeConfig(container, it) }

        // A `.prt-debug` file turns on the Wine log (Android/data/<app>/files/wine_logs); its text lists channels
        val debugFile = File(gameDir, DEV_DEBUG_NAME)
        PrefManager.enableWineDebug = debugFile.isFile
        if (debugFile.isFile) {
            PrefManager.wineDebugChannels = debugFile.readText().trim().ifEmpty { "seh,loaddll,process,d3d9" }
        }
        container.saveData()
        return container
    }

    /**
     * The tested stack. Every component here ships inside the APK (app/bundled-assets or the stock assets),
     * so a fresh install never depends on GameNative's GPU-based defaults or on a download.
     */
    private fun bakedConfig() = JSONObject().apply {
        put("containerVariant", Container.BIONIC)
        put("wineVersion", "proton-10.0-arm64ec-2")
        put("wow64Mode", true)
        put("emulator", "FEXCore")
        put("fexcoreVersion", "2605")
        // INTERMEDIATE ran city gameplay at ~56 FPS vs ~32 with STABILITY on the Thor, with no visible physics issues;
        // the deadlock fix comes from FEX_STRICTINPROCESSSPLITLOCKS, not from the preset
        put("fexcorePreset", FEXCorePreset.INTERMEDIATE)
        put("graphicsDriver", "Wrapper")
        put(
            "graphicsDriverConfig",
            "vulkanVersion=1.3,version=Turnip v26.2.0 R4,blacklistedExtensions=,maxDeviceMemory=0," +
                "presentMode=mailbox,syncFrame=0,disablePresentWait=0,resourceType=auto,bcnEmulation=auto," +
                "bcnEmulationType=compute,bcnEmulationCache=0,gpuName=Device",
        )
        put("dxwrapper", "dxvk")
        put(
            "dxwrapperConfig",
            "version=2.4.1-gplasync,framerate=0,maxDeviceMemory=0,async=1,asyncCache=1,vkd3dVersion=2.14.1," +
                "vkd3dLevel=12_1,ddrawrapper=none,csmt=3,gpuName=NVIDIA GeForce GTX 480,videoMemorySize=2048," +
                "strict_shader_math=1,OffscreenRenderingMode=fbo,renderer=gl",
        )
        put("screenSize", "1280x720")
        // libredirect is closed source and not redistributable; Prototype runs fine on the Thor without it
        put("disableLibredirect", true)
    }

    /**
     * The game saves to Documents\Prototype inside the Wine prefix, which lives in app data and is lost
     * on uninstall. Point it at `prt/saves` so saves stay next to the game files.
     */
    private fun linkSaves(container: Container, gameDir: File) {
        val saves = File(gameDir, SAVES_DIR).apply { mkdirs() }
        val docs = File(container.rootDir, ".wine/drive_c/users/${ImageFs.USER}/Documents/Prototype")
        val docsPath = docs.toPath()
        try {
            if (Files.isSymbolicLink(docsPath)) {
                if (Files.readSymbolicLink(docsPath) == saves.toPath()) return
                Files.delete(docsPath)
            } else if (docs.isDirectory) {
                // Saves made before this link existed move to the game folder, without overwriting newer copies
                docs.listFiles()?.forEach { f ->
                    val target = File(saves, f.name)
                    if (!target.exists()) f.copyTo(target)
                }
                docs.deleteRecursively()
            }
            docs.parentFile?.mkdirs()
            Files.createSymbolicLink(docsPath, saves.toPath())
        } catch (e: Exception) {
            Timber.e(e, "Could not link saves to $saves")
        }
    }

    private fun readDevConfig(file: File): JSONObject? {
        if (!file.isFile) return null
        return try {
            JSONObject(file.readText()).also { Timber.i("Dev container config from $file: $it") }
        } catch (e: Exception) {
            Timber.e(e, "Bad dev container config $file")
            null
        }
    }

    private fun mergeConfig(container: Container, overrides: JSONObject) {
        container.loadData(JSONObject(container.containerJson).apply {
            overrides.keys().forEach { put(it, overrides.get(it)) }
        })
        container.saveData()
    }
}
