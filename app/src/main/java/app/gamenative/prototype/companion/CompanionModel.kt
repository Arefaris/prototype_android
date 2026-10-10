package app.gamenative.prototype.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A pause map marker (`fe_GetMarker*`) in map units: the pause map places it at (XPos, −YPos). [medal] colours
 * free-roam event icons (0 none, 1 bronze … 4 platinum, from `fre_GetCategoryEventAchievementByGOH`).
 */
data class Marker(val mapX: Float, val mapY: Float, val icon: Int, val name: String, val medal: Int)

/** A military or infected area as the pause map shades it, in map units. */
data class Zone(val military: Boolean, val mapX: Float, val mapY: Float, val radius: Float)

/** The upgrade store's states, as the store movie derives them (protopowerpurchase GetObjectStatus). */
enum class UpgradeStatus { PURCHASED, AVAILABLE, NEW, UNAVAILABLE, HIDDEN }

data class UpgradeItem(val id: String, val name: String, val cost: Int, val core: Boolean, val status: UpgradeStatus)

/** A store sub-category ("AIR UPGRADES") with the entries under it. */
data class UpgradeSection(val name: String, val items: List<UpgradeItem>)

/** One of the store's seven lists ("POWERS", "MOVEMENT" …). */
data class UpgradeCategory(val name: String, val sections: List<UpgradeSection>)

/** The store's description panel for the selected entry. */
data class UpgradeDetails(val id: String, val description: String, val picture: ImageBitmap?)

data class CompanionState(
    // The helper answered recently
    val connected: Boolean = false,
    // The HUD is up: Alex is in the world (not a menu, loading screen or tutorial card)
    val inWorld: Boolean = false,
    // Alex in world metres, facing as a world direction
    val x: Float = 0f,
    val z: Float = 0f,
    val dirX: Float = 0f,
    val dirZ: Float = 1f,
    // As on the pause map: a header ("MISSION ACQUISITION") and the lines under it
    val objectiveHeader: String = "",
    val objectiveLines: List<String> = emptyList(),
    val ep: Int = 0,
    val markers: List<Marker> = emptyList(),
    val zones: List<Zone> = emptyList(),
    // Text bible lookups (district names)
    val strings: Map<String, String> = emptyMap(),
    val categories: List<UpgradeCategory> = emptyList(),
    val details: UpgradeDetails? = null,
    val saving: Boolean = false,
    val message: String? = null,
)

enum class CompanionTab { MAP, UPGRADES }

/**
 * Polls the game through [bridge] while the second screen is up and runs its actions. A fast loop follows Alex
 * (a few times a second); a slow one reads the objective, EP and the map markers; the store is read only while its
 * tab is open.
 */
class CompanionModel(
    private val bridge: GameBridge,
    private val gameDir: File,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(CompanionState())
    val state: StateFlow<CompanionState> = _state.asStateFlow()

    // Screen state kept here so it survives the second screen being recreated
    var tab by mutableStateOf(CompanionTab.MAP)
    var category by mutableIntStateOf(0)
    var selectedUpgrade by mutableStateOf<String?>(null)
        private set

    private var jobs = emptyList<Job>()
    private var misses = 0
    @Volatile private var wantedStrings = emptySet<String>()

    fun start() {
        if (jobs.isNotEmpty()) return
        jobs = listOf(
            scope.launch { loop(FAST_MS) { pollFast() } },
            scope.launch { loop(SLOW_MS) { if (_state.value.inWorld) pollSlow() } },
            scope.launch { loop(UPGRADES_MS) { if (_state.value.inWorld && tab == CompanionTab.UPGRADES) pollUpgrades() } },
        )
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }

    /** Text bible keys to look up once the game is in the world (they need the language loaded). */
    fun requestStrings(keys: Collection<String>) {
        wantedStrings = wantedStrings + keys
    }

    fun showUpgrades() {
        tab = CompanionTab.UPGRADES
        scope.launch { if (_state.value.inWorld) pollUpgrades() }
    }

    fun selectUpgrade(id: String?) {
        selectedUpgrade = id
        if (id == null || _state.value.details?.id == id) return
        scope.launch {
            val result = bridge.lua(detailsChunk(id))
            val text = result?.takeIf { it.ok }?.lines?.joinToString(" ")?.trim().orEmpty()
            val picture = withContext(Dispatchers.IO) { GameAssets.upgradePicture(gameDir, id) }
            if (selectedUpgrade == id) _state.update { it.copy(details = UpgradeDetails(id, text, picture)) }
        }
    }

    /** The game's own autosave into the current slot; the game shows its saving indicator meanwhile. */
    fun save() = scope.launch {
        if (_state.value.saving) return@launch
        _state.update { it.copy(saving = true) }
        val started = bridge.lua(SAVE_CHUNK)
        var done = false
        if (started?.ok == true) {
            // The save runs in the background; sgm_IsActive turns false when it is written (about a second)
            for (attempt in 1..40) {
                delay(500)
                if (bridge.lua("return tostring(sgm_IsActive())")?.lines?.firstOrNull() == "false") {
                    done = true
                    break
                }
            }
        }
        _state.update { it.copy(saving = false, message = if (done) "GAME SAVED" else "SAVE FAILED") }
    }

    fun showMessage(text: String) = _state.update { it.copy(message = text) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private suspend fun loop(periodMs: Long, body: suspend () -> Unit) {
        while (scope.isActive) {
            body()
            delay(periodMs)
        }
    }

    private suspend fun pollFast() {
        val result = bridge.lua(FAST_CHUNK, timeoutMs = 600)
        if (result == null || !result.ok) {
            // A few missed answers in a row mean the game is gone (exited, or a long loading screen)
            if (++misses >= 4) _state.update { it.copy(connected = false, inWorld = false) }
            return
        }
        misses = 0
        val values = keyValues(result.lines)
        val pos = values["pos"]?.floats()
        val dir = values["dir"]?.floats()
        _state.update {
            it.copy(
                connected = true,
                inWorld = values["hud"] == "true" && pos != null,
                x = pos?.getOrNull(0) ?: it.x,
                z = pos?.getOrNull(1) ?: it.z,
                dirX = dir?.getOrNull(0) ?: it.dirX,
                dirZ = dir?.getOrNull(1) ?: it.dirZ,
            )
        }
    }

    private suspend fun pollSlow() {
        val missing = wantedStrings - _state.value.strings.keys
        val result = bridge.lua(slowChunk(missing)) ?: return
        if (!result.ok) return
        val markers = result.lines.filter { it.startsWith("mk=") }.mapNotNull { line ->
            val parts = line.removePrefix("mk=").split('|', limit = 5)
            val x = parts.getOrNull(0)?.toFloatOrNull() ?: return@mapNotNull null
            val y = parts.getOrNull(1)?.toFloatOrNull() ?: return@mapNotNull null
            Marker(x, -y, parts.getOrNull(2)?.toIntOrNull() ?: 0, parts.getOrNull(4).orEmpty().trim(), parts.getOrNull(3)?.toIntOrNull() ?: 0)
        }
        val strings = result.lines.filter { it.startsWith("str=") }.mapNotNull { line ->
            val parts = line.removePrefix("str=").split('|', limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()
        val values = keyValues(result.lines)
        val zones = readZones()
        _state.update {
            it.copy(
                zones = zones ?: it.zones,
                objectiveHeader = values["objh"].orEmpty().trim(),
                objectiveLines = values["obj"].orEmpty().split('\t').map(String::trim).filter(String::isNotEmpty),
                ep = values["ep"]?.toDoubleOrNull()?.toInt() ?: it.ep,
                markers = markers,
                strings = it.strings + strings,
            )
        }
    }

    // Active zones of type 0 (Military) and 1 (Outbreak); the engine hands the pause map the same set
    private suspend fun readZones(): List<Zone>? {
        val result = bridge.zones() ?: return null
        if (!result.ok) return null
        return result.lines.mapNotNull { line ->
            val p = line.split('|')
            if (p.size < 6 || p[4] != "1" || p[5] != "1") return@mapNotNull null
            val type = p[0].toIntOrNull()?.takeIf { it <= 1 } ?: return@mapNotNull null
            val x = p[1].toFloatOrNull() ?: return@mapNotNull null
            val z = p[2].toFloatOrNull() ?: return@mapNotNull null
            val r = p[3].toFloatOrNull() ?: return@mapNotNull null
            Zone(type == 0, MapProjection.mapX(x), MapProjection.mapY(z), MapProjection.UNITS_PER_METRE * r)
        }
    }

    private suspend fun pollUpgrades() {
        val result = bridge.lua(UPGRADES_CHUNK, timeoutMs = 2500) ?: return
        if (!result.ok) return
        val names = HashMap<Int, String>()
        val rows = HashMap<Int, MutableList<List<String>>>()
        for (line in result.lines) {
            when {
                line.startsWith("cat=") -> line.removePrefix("cat=").split('|', limit = 2).let { p ->
                    p[0].toIntOrNull()?.let { names[it] = p.getOrNull(1).orEmpty() }
                }
                line.startsWith("up=") -> line.removePrefix("up=").split('|', limit = 10).let { p ->
                    p[0].toIntOrNull()?.let { rows.getOrPut(it) { mutableListOf() } += p }
                }
            }
        }
        val categories = names.keys.sorted().map { list ->
            val sections = mutableListOf<UpgradeSection>()
            var header: String? = null
            var items = mutableListOf<UpgradeItem>()
            fun flush() {
                header?.let { h -> if (items.isNotEmpty()) sections += UpgradeSection(h, items) }
                items = mutableListOf()
            }
            for (p in rows[list].orEmpty()) {
                if (p.size < 10) continue
                val name = p[9].trim()
                if (p[2] == SUBCATEGORY_TYPE) {
                    flush()
                    header = name
                    continue
                }
                val status = when {
                    p[5] == "true" -> UpgradeStatus.PURCHASED
                    p[6] != "true" -> UpgradeStatus.HIDDEN
                    p[7] != "true" -> UpgradeStatus.UNAVAILABLE
                    p[8] != "true" -> UpgradeStatus.NEW
                    else -> UpgradeStatus.AVAILABLE
                }
                // The store lists what can be bought and what was bought, nothing else
                if (status == UpgradeStatus.HIDDEN || status == UpgradeStatus.UNAVAILABLE) continue
                items += UpgradeItem(p[1], name, p[3].toDoubleOrNull()?.toInt() ?: 0, p[4] == "1", status)
            }
            flush()
            UpgradeCategory(names[list].orEmpty(), sections)
        }
        _state.update { it.copy(categories = categories) }
    }

    private fun keyValues(lines: List<String>): Map<String, String> =
        lines.mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) } }
            .toMap()

    private fun String.floats(): List<Float>? = split(',').map { it.toFloatOrNull() ?: return null }

    private companion object {
        const val FAST_MS = 250L
        const val SLOW_MS = 2000L
        const val UPGRADES_MS = 4000L

        // The store marks sub-category headers with this purchase type (protopowerpurchase SUBCATEGORY_VALUE)
        const val SUBCATEGORY_TYPE = "20"

        // call(name, ...): the native's results, or nil when it is missing in this state or fails;
        // clean(s): one line, no field separators; L(key): the text bible entry ("!BAD!" when missing)
        const val HELPERS = "local function call(name, ...) local fn = _G[name] if type(fn) ~= \"function\" then return nil end " +
            "local r = {pcall(fn, ...)} if r[1] then return unpack(r, 2) end end " +
            "local function clean(s) return (tostring(s or \"\"):gsub(\"[\\r\\n|]+\", \" \")) end " +
            "local function L(key) local s = call(\"fe_GetLocalizedString\", key) if s == nil or s == \"!BAD!\" then return \"\" end return clean(s) end "

        const val FAST_CHUNK = HELPERS + """
local o = {}
local hud = call("fe_GetHudLoaded")
o[#o+1] = "hud=" .. tostring(hud)
if hud and PLAYER_1 then
  local p, d = call("go_GetPosition", PLAYER_1), call("go_GetOrientation", PLAYER_1)
  if p then o[#o+1] = "pos=" .. p.x .. "," .. p.z end
  if d then o[#o+1] = "dir=" .. d.x .. "," .. d.z end
end
return table.concat(o, string.char(10))"""

        const val SAVE_CHUNK = HELPERS + "call(\"sgm_AutoSave\") return \"started\""

        // As the pause map: om_GetCurObjectives(true) is the header, (false) the text (lines joined by tabs).
        // Marker slots past the used ones answer icon 38 ("NONE") at (0, 0); icons 17-30 are free-roam events.
        fun slowChunk(strings: Collection<String>) = HELPERS + """
local o = {}
o[#o+1] = "objh=" .. clean(call("om_GetCurObjectives", true))
o[#o+1] = "obj=" .. (tostring(call("om_GetCurObjectives", false) or ""):gsub("[\r\n]+", "\t"):gsub("|", " "))
o[#o+1] = "ep=" .. tostring(call("db_GetInt", DB_PROTO_SAVE_EVOLUTIONPOINTS, 0, 0) or 0)
local n = tonumber(call("fe_GetMaxMarkerIndex")) or 0
for i = 0, n do
  local icon = call("fe_GetMarkerIconType", i)
  local x, y = call("fe_GetMarkerXPos", i), call("fe_GetMarkerYPos", i)
  if icon ~= 38 and x and y and (x ~= 0 or y ~= 0) then
    local medal = 0
    if icon >= 17 and icon <= 30 then
      medal = tonumber(call("fre_GetCategoryEventAchievementByGOH", call("fe_GetMarkerGOH", i, true), call("fe_GetMarkerGOH", i, false))) or 0
    end
    o[#o+1] = "mk=" .. x .. "|" .. y .. "|" .. tostring(icon) .. "|" .. medal .. "|" .. clean(call("fe_GetMarkerDescription", i))
  end
end
""" + strings.joinToString("") { "o[#o+1] = \"str=$it|\" .. L(\"$it\")\n" } + "return table.concat(o, string.char(10))"

        // The store movie's reading (CreateSubCategoryArrays): entries are 0-based; owned also counts what the
        // story activated (unlockable_IsActivated); names are the text bible's "Unlock_<id>_Name"
        const val UPGRADES_CHUNK = HELPERS + """
local o = {}
local lists = {"POWERS", "LOCOMOTION", "HEALTH", "COMBAT", "DISGUISE", "VEHICLE", "WEAPON"}
for l = 0, 6 do
  o[#o+1] = "cat=" .. l .. "|" .. L(lists[l + 1])
  local n = tonumber(call("um_GetUnlockablesListCount", l)) or 0
  for e = 0, n - 1 do
    local id = call("um_GetFEIdentifier", l, e)
    if id then
      local active = call("unlockable_IsActivated", 0, call("um_GetUnlockableEnum", l, e)) == true
      o[#o+1] = "up=" .. l .. "|" .. tostring(id) .. "|" .. tostring(call("um_GetPurchaseType", l, e)) .. "|" ..
        tostring(call("um_GetPurchaseCost", l, e)) .. "|" .. tostring(call("um_GetNecessityType", l, e)) .. "|" ..
        tostring(call("um_GetAcquired", l, e) == true or active) .. "|" .. tostring(call("um_GetAvailable", l, e) == true or active) .. "|" ..
        tostring(call("um_GetPrerequisiteStatisfied", l, e)) .. "|" .. tostring(call("um_GetDisplayedInFE", l, e)) .. "|" ..
        L("Unlock_" .. tostring(id) .. "_Name")
    end
  end
end
return table.concat(o, string.char(10))"""

        fun detailsChunk(id: String) = HELPERS + "return L(\"Unlock_${id}_Desc\")"
    }
}
