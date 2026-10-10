# Technical notes

A single-purpose fork of [GameNative](https://github.com/utkarshdalal/GameNative) v1.2.1 that launches Prototype (2009).

## Where things live

| What | Where |
|---|---|
| Game files | `prt/` at the root of internal storage or an SD card (internal storage is checked first) |
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
