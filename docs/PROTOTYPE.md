# Technical notes

A single-purpose fork of [GameNative](https://github.com/utkarshdalal/GameNative) v1.2.1 that launches Prototype (2009).

## Where things live

| What | Where |
|---|---|
| Game files | `prt/` at the root of internal storage or an SD card (internal storage is checked first); else a GameNative download on any volume: `GameNative/Steam/steamapps/common/Prototype` (its public install root) or `Android/data/app.gamenative/files/Steam/steamapps/common/Prototype` (older versions, where Android allows reading it). GameNative's default, its private app data, is out of reach |
| Saves | `prt/saves/` (the prefix's `Documents\Prototype` is a symlink to it) |
| Wine prefix, runtime | app data (`/data/data/app.prototype.thor`), rebuilt from the APK if cleared |

A `d3d9.dll` in `prt/` that is not DXVK (PC mods, overlays) is renamed to `d3d9.dll.disabled` on launch, because it
would be loaded instead of DXVK.

## The tested stack (AYN Thor, Adreno 740, Android 13)

Hardcoded in `PrototypeGame.bakedConfig()`; every component ships inside the APK.

| Setting | Value |
|---|---|
| Container | Bionic, WoW64 |
| Wine | `proton-10.0-arm64ec-2` |
| x86 emulator | FEXCore 2605, preset **INTERMEDIATE**, env **`FEX_STRICTINPROCESSSPLITLOCKS=1`** |
| Graphics | Wrapper + Turnip v26.2.0 R4, DXVK 2.4.1-gplasync |
| Resolution | 1280×720 |

Measured in the city: ~56 FPS (51–60) with INTERMEDIATE, ~32 FPS with STABILITY; menus hold 60 FPS.

**Why `FEX_STRICTINPROCESSSPLITLOCKS=1`:** the engine keeps critical sections at odd addresses, so the lock word
straddles a 16-byte boundary. x86 does that atomically; ARM64 can't, and without FEX's strict split-lock mode the lock
corrupts and two game threads deadlock right after start (black screen, Wine logs
`RtlpWaitForCriticalSection ... blocked by 0000`). Box64/wowbox64 deadlocks the same way.

## Building

```powershell
pwsh tools/Fetch-Bundle.ps1          # imagefs, Proton, Turnip into app/bundled-assets (gitignored), SHA-256 checked
./gradlew :app:assembleLegacyDebug
```

If the packed assets change, delete `app/build/outputs/apk/legacy/debug` before building: incremental packaging keeps
the old file size.

## Tuning on the device

Files in `prt/` that override the launcher without rebuilding:

| File | Effect |
|---|---|
| `.prt-config.json` | JSON merged over the container config, e.g. `{"fexcorePreset":"COMPATIBILITY"}` |
| `.prt-debug` | Turns on the Wine log (`Android/data/app.prototype.thor/files/wine_logs/wine_debug.log`); its text lists channels, e.g. `seh,loaddll,d3d9` |

## Second-screen companion

While the game runs on the top screen, the AYN Thor's bottom screen (a presentation display, 1240×1080) shows a
companion styled after the game's own pause screens: the GENTEK menu on the left (map, upgrades, then save and
screenshot), the pause map or the upgrade store on the right. It closes when the game goes to the
background (activity stopped) and comes back with it. On a device without a second screen nothing of it runs: the
helper below is not installed (an earlier copy is removed from `prt/`) and the companion is not started. Code:
`app/.../prototype/companion/`.

**Helper inside the game.** `assets/companion/d3d9.dll` is the opprobe d3d9 proxy from open_prototype
(`tools/re/d3d9proxy`, GPL-3.0, built with MSVC for Win32). The launcher copies it and `opprobe_d3d9.ini`
(`windowed=0`, `keepActive=0`) into `prt/` on every launch when a second screen is present; any other `d3d9.dll` there is renamed to
`d3d9.dll.disabled`. The proxy forwards to DXVK (loaded from the system directory) and leaves the game's display mode
alone with `windowed=0`. It only works with the 2025 Steam build of `prototypeenginef.dll`.

**Channel.** The app writes `prt/opprobe_cmd.request` (`lua <chunk>` or `keys <script>`, through a temp file and a
rename); the proxy runs it on the game's main thread every second frame and answers in `prt/opprobe_cmd.result`.
Round trip on the Thor: 30–60 ms. `GameBridge.kt` serialises requests and withdraws unanswered ones.

**What is read** (`CompanionModel.kt`; every call goes through `pcall`, since natives differ between menu and world):

| Data | Natives | Rate |
|---|---|---|
| Alex's position and facing | `go_GetPosition(PLAYER_1)`, `go_GetOrientation` (x, z) | 4/s |
| In world / paused | `fe_GetHudLoaded`, `gameflow_IsPaused` | 4/s |
| Objective, EP | `om_GetCurObjectives(true)` (header), `om_GetCurObjectives(false)` (mission and objective lines), `db_GetInt(DB_PROTO_SAVE_EVOLUTIONPOINTS, 0, 0)` | every 2 s |
| Map markers | `fe_GetMaxMarkerIndex`, `fe_GetMarkerXPos/YPos` (pause-map units), `IconType`, `Description`; unused slots answer icon 38 at (0, 0); event medals `fre_GetCategoryEventAchievementByGOH(fe_GetMarkerGOH(i, true), fe_GetMarkerGOH(i, false))` | every 2 s |
| District names | `fe_GetLocalizedString("META_DISTRICT_n")` | once |
| Upgrades | as the store movie (`protopowerpurchase` CreateSubCategoryArrays): `um_GetUnlockablesListCount(list)` and, per 0-based entry, `um_GetFEIdentifier`, `GetPurchaseType` (20 = sub-category header), `GetPurchaseCost`, `GetNecessityType` (1 = CORE), `GetAcquired`/`GetAvailable` (or `unlockable_IsActivated(0, um_GetUnlockableEnum(list, entry))`), `GetPrerequisiteStatisfied`, `GetDisplayedInFE`; names and descriptions `fe_GetLocalizedString("Unlock_<id>_Name"/"_Desc")`, list names `POWERS`, `LOCOMOTION`, `HEALTH`, `COMBAT`, `DISGUISE`, `VEHICLE`, `WEAPON` | every 4 s while the tab is open |

Like the store, the list shows what can be bought and what was bought; entries that are hidden or still need a
prerequisite are left out.

**Actions.** Save: `sgm_AutoSave()` writes the current slot (checked: the slot file is rewritten and the game shows
its saving indicator); `sgm_SaveCheckpoint` only records an in-memory mission checkpoint and writes nothing.
Screenshot: `PixelCopy` of the X server surface into `Pictures/Prototype/`.

**Game art.** Everything visual comes from the player's own `art.rcf` on the device and is never shipped
(`GameAssets.kt`; RCF 2.1 → RZ/zlib → Pure3D or Scaleform):
- Map: the pause map's vector island in `art/hud/protomap.gfx` (the movie's largest shape: land filled, blocks cut out
  and outlined) and its district labels (`$META_DISTRICT_n` text fields in the `mcDistricts` sprite). A marker sits at
  (XPos, −YPos) in its units; Alex at (0.375·x, −0.375·z) from his world position. North is up, as on the pause map.
- Marker icons: `protomap__I*` textures in `art/hud/fe_pause.p3d`, one per icon type as in the movie's
  `indicator_image` frames (frame = type + 1); free-roam events are tinted by medal as in `ColourEventMarker`.
- Fonts: `PrototypeBodyPC` and `PrototypeTitlePC` (DefineFont3 in `art/hud/fonts_latin.gfx`), rewritten as TrueType
  files in `files/companion/fonts-1/` (`TtfWriter.kt`).
- Upgrade pictures: `art/hud/<id>.p3d` (the store loads `<id>.gfx`), the drawn part of the texture.
The game's DDS images are stored bottom row first and are flipped when decoded.

**Open points.**
- Military and infected zones (the pause map's coloured circles) come from the engine through
  `fe_AddStaticMarkersToPauseMap` and are not drawn yet.
- Each request is a file write in shared storage, and Android's MediaProvider logs a quota warning for each; a socket
  channel in the proxy would avoid that.

## What changed from GameNative

- `app/.../prototype/`: launcher screen, boot splash, game discovery, container setup, save link.
- `PluviaMain.kt`: the Prototype screen is the start destination; launch progress and errors go to it instead of
  dialogs; leaving the game closes the app; Steam never connects.
- `applicationId` is `app.prototype.thor` (installs next to GameNative); hardcoded `/data/data/app.gamenative` paths
  now use the application id, and `EVSHIM_BASE_PATH` is set so the gamepad shared memory is found.
- `ManifestInstaller.kt` / `ManifestRepository.kt`: components come from `assets/bundled/` and the bundled
  `manifest.json`; the network is only a fallback.
- `ImageFsInstaller.java`: the guest setup continues when `redirect.tzst` is absent.
- No notification permission prompt, no boot-screen tips or sponsor cards; own label and icon.
- Removed from the repository: GameNative's CI, docs and tests, the XR and modern build flavors, assets this stack
  doesn't use (other GPU drivers, DXVK/VKD3D/FEX/Box64 versions), and all closed-source or proprietary binaries:
  `libredirect` (`redirect.tzst`, the container runs with `disableLibredirect=true`), `libsteambootstrap.so`,
  `libkgslshim.so`, Valve's `steam_api*.dll` and Qualcomm's proprietary Adreno driver packages.
