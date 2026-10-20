# GodotDroid

<div align="center">

**High-Performance Android Runtime Host, Binary Emulator & Standalone APK Exporter for PC Godot Games**

[![Android CI](https://github.com/nevilledev/GodotDroid/actions/workflows/android-ci.yml/badge.svg)](https://github.com/nevilledev/GodotDroid/actions/workflows/android-ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-purple.svg)](https://kotlinlang.org)
[![MinSDK](https://img.shields.io/badge/MinSDK-26%20(Android%208.0)-green.svg)](https://developer.android.com)
[![TargetSDK](https://img.shields.io/badge/TargetSDK-34%20(Android%2014)-brightgreen.svg)](https://developer.android.com)
[![Godot](https://img.shields.io/badge/Godot%20Engine-3.2%20--%204.6+-blue.svg)](https://godotengine.org)

</div>

---

## Overview

**GodotDroid** is a modular, high-performance Android host environment and standalone repackager designed to run desktop PC Godot games directly on Android devices. It supports both **Godot 4.x** (v4.0 through v4.6+) and **Godot 3.x** (v3.2 through v3.6) games packaged as standalone `.pck` files or embedded Windows PE `.exe` executables.

GodotDroid solves the common challenges of running desktop Godot builds on mobile:
- **Zero-Copy Binary Parsing & Extraction**: Inspects multi-gigabyte `.pck` and `.exe` archives without loading full files into memory.
- **W^X Compliant Dynamic Loading**: Adheres strictly to Android 14+ (API 34) SELinux security policies with explicit `setReadOnly()` staging before `DexClassLoader` instantiation.
- **Isolated Process Architecture**: Games run in an isolated `:godot_runner` process with full-screen immersive rendering and clean PID termination on exit, eliminating static JNI state corruption.
- **Modern Android Bionic Audio Deadlock Guard**: Low-level Global Offset Table (GOT) patching in `libgodot_compat.so` to intercept `pthread_mutex_lock` `EAGAIN` returns, preventing OpenSL ES audio thread aborts on Android 15/16.
- **Dual-Source Core Delivery Architecture**: Two-tier runtime engine provisioning: Tier 1 downloads fast, lightweight (12–24 MB) pre-packaged `core_*.zip` archives from GitHub Releases, falling back to Tier 2 HTTP Range requests that decompress `android_release.apk` directly from official Godot `.tpz` archives on-the-fly.
- **Multi-Pack & Sibling Companion Pipeline**: Precise PCK Format 1 (Godot 3) and Format 2+ (Godot 4) directory parsing with automatic sibling file discovery (`*.pck`, `*.zip`, `*.dll`, `*.so`), runtime staging, and dynamic `godotdroid_companion_mount.gd` mounting into `res://`.
- **Universal Native Plugin & Windows DLL Stubs**: Dynamic C and GDScript stub generation (`libgdnative_stub.so`) that provides typed defaults for missing desktop Windows `.dll` libraries (e.g. `optimizations.dll`, `MovementManagerR`, `VisualSyncR`).
- **Desktop-to-Mobile Compatibility Layer**: Automatic rendering fallback (`forward_plus` → `mobile` / `gl_compatibility` / `GLES3` / `GLES2`), viewport stretch overrides, and desktop VRAM texture decompression flags.
- **Native Steam API Stubbing**: Drop-in autoload stubbing for `GodotSteam` (`godotdroid_steam_stub.gd`) that prevents instant crashes when PC games call `Steam.steamInit()`.
- **In-PCK Binary Rewriting**: Dynamically rewrites `.gdextension` (Godot 4) and `.gdnlib` (Godot 3) virtual files directly inside the PCK directory table to bind Android `arm64-v8a` native libraries.
- **Customizable Touch & Mouse Controls**: Hardware-accelerated virtual gamepad overlay, analog sticks, buttons, keyboard shortcuts, and PC trackpad mouse emulation with an interactive visual keymapper.
- **On-Device Standalone APK Exporter**: Directly repackages any imported game into an independent, signed Android APK with custom package names, app icons, and first-run auto-boot extraction.

---

## Architecture & Multi-Module Structure

GodotDroid is architected as five clean, decoupled modules:

```
GodotDroid/
├── app/                  # Main Jetpack Compose UI (Game Library, Core Manager, Inspector, Exporter)
├── pck-inspector/        # Zero-copy binary parser (PCK v1-v4 & PE .exe) and virtual file patcher
├── runtime-host/         # Multi-version core loader, JNI proxy, Steam stub, Bionic compat & stub injector
├── overlay-controls/     # Virtual gamepad, mouse trackpad, layout editor & hybrid input bridge
├── apk-exporter/         # Binary AXML patcher, 4-byte ZIP alignment & on-device APK signer
└── scripts/              # Standalone Python CLI tools for runtime core packaging
```

### Module Responsibilities

| Module | Purpose | Key Components |
|---|---|---|
| `:pck-inspector` | Binary inspection and virtual filesystem patching | `PckBinaryInspector`, `PeExecutableExtractor`, `ProjectConfigParser`, `PckVirtualPatcher` |
| `:runtime-host` | Engine core management, compatibility, and dynamic execution | `CoreManager`, `DynamicGodotLoader`, `OfficialTpzRangeDownloader`, `RuntimePatchInjector`, `PluginCompatManager`, `libgodot_compat.so`, `libgdnative_stub.so`, `GodotRunnerActivity` |
| `:overlay-controls` | Touch controls and input bridging | `TouchOverlayView`, `ControlLayoutEditor`, `GodotInputBridge`, `GamepadModels` |
| `:apk-exporter` | Standalone APK repackaging and signing | `AxmlManifestPatcher`, `ZipAlignedApkBuilder`, `ApkSignerEngine`, `StandaloneBootManager` |
| `:app` | User interface and presentation layer | `GameLibraryScreen`, `GameInspectorSheet`, `CoreManagerSheet`, `ApkExportSheet` |

---

## Engine Compatibility Matrix

GodotDroid supports the full spectrum of Godot Engine releases:

| Engine Version | PCK Format | Graphics Drivers | Plugin Architecture | Notes |
|---|---|---|---|---|
| **Godot 4.6 (Dev)** | v4 | Vulkan Mobile, OpenGL 3 | GDExtension (`.gdextension`) | Experimental core |
| **Godot 4.5** | v3 | Vulkan Mobile, OpenGL 3 | GDExtension (`.gdextension`) | Full mobile overrides |
| **Godot 4.4** | v3 | Vulkan Mobile, OpenGL 3 | GDExtension (`.gdextension`) | Full mobile overrides |
| **Godot 4.3** | v3 | Vulkan Mobile, OpenGL 3 | GDExtension (`.gdextension`) | **Recommended Godot 4 Core** |
| **Godot 4.2** | v2 | Vulkan Mobile, OpenGL 3 | GDExtension (`.gdextension`) | Full mobile overrides |
| **Godot 4.1** | v2 | Vulkan Mobile, OpenGL 3 | GDExtension (`.gdextension`) | Full mobile overrides |
| **Godot 4.0** | v2 | Vulkan Mobile, OpenGL 3 | GDExtension (`.gdextension`) | Full mobile overrides |
| **Godot 3.6** | v1 | GLES3, GLES2 | GDNative (`.gdnlib`) | **Recommended Godot 3 Core** |
| **Godot 3.5** | v1 | GLES3, GLES2 | GDNative (`.gdnlib`) | Full GLES fallback |
| **Godot 3.4** | v1 | GLES3, GLES2 | GDNative (`.gdnlib`) | Full GLES fallback |
| **Godot 3.3** | v1 | GLES3, GLES2 | GDNative (`.gdnlib`) | Full GLES fallback |
| **Godot 3.2** | v1 | GLES3, GLES2 | GDNative (`.gdnlib`) | Full GLES fallback |

---

## Tested Games & Compatibility Results

The following table documents verification on physical ARM64 Android devices:

| Game | Engine Version | Core | Result |
| :--- | :--- | :--- | :--- |
| **Café-Seduction** (`v0.2.1`) | Godot 4.6.3 (PCK v3) | 4.6 | **PASS** |
| **Coerced Hero Chronicles** (`v0.2.1`) | Godot 4.5.0 (PCK v3) | 4.5 | **PASS** |
| **Somatic Hex** | Godot 3.6.0 (PCK v1) | 3.6 | **PASS** |
| **Rhythm Rider** | Godot 3.3.2 (PCK v1) | 3.3 | **PASS** |
| **Princess Loves Goblin** | Godot 3.3.2 (PCK v1) | 3.3 | **PASS** |
| **Screamer Creamer** | Godot 3.3.2 (PCK v1) | 3.3 | **PASS** |
| **StripFlip** | Godot 3.3.2 (PCK v1) | 3.3 | **PASS** |
| **SuccManor** | Godot 3.3.2 (PCK v1) | 3.3 | **PASS** |
| **Kink's Quest** | Godot 3.5.x (PCK v1) | 3.5 | **PASS** |

---

## Core Features in Detail

### 1. Modern Android Bionic Audio Deadlock Guard (`libgodot_compat.so`)
On modern Android releases (Android 15 / 16 preview), Bionic `libc.so` enforces a strict 11-bit limit on recursive mutex locks (`MUTEX_COUNTER_MASK = 2047`). Godot 3's `AudioDriverOpenSL` mixer thread repeatedly locks a shared `std::recursive_mutex` during high-frequency audio playback and dialog stream transitions. Once the recursive depth exceeds 2,047, Bionic's `pthread_mutex_lock` returns `EAGAIN` (`Try again`). Standard LLVM libc++ (`libc++_shared.so`) handles any non-zero return code by throwing `std::__ndk1::system_error: recursive_mutex lock failed: Try again`, resulting in an instant `SIGABRT`.

GodotDroid resolves this natively via `libgodot_compat.so`:
- Uses `dl_iterate_phdr` to dynamically discover the load addresses and ELF program headers of `libgodot_android.so` and `libc++_shared.so`.
- Scans their `.rela.plt` and `.rela.dyn` relocation tables across both 4KB and 16KB memory page boundaries.
- Replaces the Global Offset Table (GOT) entries for `pthread_mutex_lock` and `pthread_mutex_trylock` with custom interceptors.
- When Bionic returns `EAGAIN`, the hook intercepts the return value and substitutes `0` (success). Because the calling thread already holds exclusive ownership of the mutex, execution proceeds without crashing or starving the audio pipeline.

### 2. Dual-Source Runtime Core Architecture
GodotDroid provides seamless, multi-version runtime core installation through an intelligent two-tier architecture:
- **Tier 1: Pre-Packaged Release Packages (Fast & Minimal)**:
  - Connects to the official GitHub Release registry (`cores-latest`) at `https://github.com/nevilledev/GodotDroid/releases/download/cores-latest`.
  - Downloads standalone `core_<versionKey>.zip` packages (12–24 MB each), containing only the stripped ARM64 `libgodot_android.so` and `godot-lib.dex`.
  - Verifies integrity using SHA-256 hashes published in `cores_manifest.json`.
- **Tier 2: Official Godot TPZ Range Downloader (Zero-Hosting Fallback)**:
  - If a pre-built core is not hosted (HTTP 404) or fails verification, GodotDroid seamlessly falls back to `OfficialTpzRangeDownloader`.
  - Connects directly to official Godot releases on GitHub (`https://github.com/godotengine/godot/releases/download/<tag>/Godot_v<tag>_export_templates.tpz`).
  - Utilizes HTTP `Range: bytes=...` requests to read only the ZIP End-of-Central-Directory (EOCD) and Central Directory records (~64 KB).
  - Identifies `templates/android_release.apk` and streams/inflates **only** the APK payload (~25–90 MB) directly to disk on-the-fly, completely bypassing the download of the rest of the ~600 MB–1 GB archive.
  - Automatically iterates through version candidate tags (e.g. `3.6.2-stable`, `3.6.1-stable`, `3.6.0-stable`, `3.6-stable`) until a match is acquired.

### 3. Multi-Pack & Companion Resource Pipeline
Many PC Godot games distribute additional assets across companion packs (e.g., `love.pck`, DLC packs, mod packages) or rely on auxiliary native libraries (`optimizations.dll`):
- **Format 1 vs Format 2+ Header Parsing**:
  - **PCK Format 1 (Godot 3.x)**: Directory table entries consist of 32 bytes of metadata (8B offset + 8B size + 16B MD5 checksum) followed by an unpadded string.
  - **PCK Format 2+ (Godot 4.x)**: Directory table entries use 36 bytes of metadata (+ 4B flags) and 4-byte aligned (padded) string paths.
  - `PckBinaryInspector` and `RuntimePatchInjector` dynamically adjust directory table strides to prevent byte-drift and corrupted path length parsing.
- **Universal Sibling Auto-Discovery**:
  - When importing a `.pck` or `.exe`, GodotDroid queries the source directory and automatically imports all sibling `*.pck`, `*.zip`, `*.dll`, `*.so`, and `*.dylib` files.
  - Users can also manually attach additional companion packs via the "+ Add Companion Pack" action in the Game Inspector sheet.
- **Dynamic Virtual File Mounting (`godotdroid_companion_mount.gd`)**:
  - Automatically generates an engine-version-appropriate bootstrap autoload script executed on launch.
  - Uses `ProjectSettings.load_resource_pack()` with exact absolute runtime staging paths, safely mounting companion archives directly into the engine's virtual filesystem (`res://`).

### 4. Universal Native Stubs (`libgdnative_stub.so`)
PC games often compile game logic, movement controllers, or visual synchronization into desktop Windows DLLs (`optimizations.dll`). When running on Android, these DLLs cannot execute:
- GodotDroid provides a native universal GDNative stub library (`libgdnative_stub.so`) and rich GDScript virtual fallback resources.
- Discovered classes (such as `MovementManagerR`, `VisualSyncR`, `TileOracle`, `Oracle`) are populated with functional default return values (`Vector2.ZERO`, `Transform2D`, `bool`, `float`) conforming to Godot calling conventions.
- Prevents script runtime crashes (`Nil` method calls or uninitialized pointers) and enables full progression into gameplay.

### 5. In-PCK Virtual File Rewriting (`PckVirtualPatcher`)
Godot resolves assets through its virtual filesystem (`res://`). When desktop games use `.gdextension` or `.gdnlib` configuration files, they often omit Android `arm64-v8a` binary paths. Rather than unpacking gigabytes of game assets, `PckVirtualPatcher`:
- Traverses the PCK binary directory table (supporting Format versions 1, 2, 3, and 4).
- Appends or replaces file payloads at the end of the package.
- Injects `res://override.cfg`, `res://godotdroid_steam_stub.gd`, and patched `.gdextension` / `.gdnlib` tables.
- Updates header file counts, directory offsets, and 4-byte boundaries with zero data corruption.

### 6. Steam API Bypass & Autoload Stubbing
Many PC games include `GodotSteam` and crash immediately on Android when `Steam.steamInit()` cannot find desktop dynamic libraries (`steam_api64.dll` / `libsteam_api.so`). GodotDroid generates an engine-specific GDScript singleton:
- **Godot 4.x**: Strongly typed GDScript (`godotdroid_steam_stub.gd`) returning valid dictionary responses for initialization, achievements, persona names, and mock Steam IDs (`76561197960287930`).
- **Godot 3.x**: Untyped GDScript compatible with Godot 3 syntax.
- The stub is registered via `[autoload]` in `override.cfg` so `Steam` is accessible globally before game scenes load.

### 7. Touch Overlay & Hybrid Input Dispatch (`GodotInputBridge`)
The input system translates mobile touch events into desktop game actions using a dual-path bridge:
1. **Direct JNI Reflection**: Calls internal `org.godotengine.godot.GodotLib` methods (`joybutton`, `joyaxis`, `key`, `touch`) inside the running engine.
2. **Synthetic Android View Events**: Dispatches native `MotionEvent` and `KeyEvent` with proper source flags (`SOURCE_GAMEPAD`, `SOURCE_KEYBOARD`, `SOURCE_MOUSE`) to Godot's active `SurfaceView`.
3. **PC Trackpad Emulation**: Includes a dedicated trackpad control translating finger swipes into relative mouse movement, alongside physical Left Click and Right Click buttons.

### 8. Standalone APK Repackaging (`:apk-exporter`)
GodotDroid can export imported PC games as standalone Android APKs:
- **Binary AXML String Pool Patcher (`AxmlManifestPatcher`)**: Rewrites package identifiers (`com.godotdroid` → `com.godotdroid.game.<slug>`) and application labels directly inside Android Binary XML (`0x00080003`) without needing `apktool` or JVM recompilation.
- **4-Byte ZIP Alignment (`ZipAlignedApkBuilder`)**: Ensures `assets/main.pck` and uncompressed native libraries are aligned on 4-byte boundaries for direct memory mapping (`mmap`).
- **On-Device Cryptographic Signing (`ApkSignerEngine`)**: Creates an RSA-2048 keypair with self-signed X.509 certificate and signs the resulting APK using Android's official `apksig` library (V1 JAR + V2 + V3 signing schemes).
- **First-Run Standalone Auto-Boot**: Exported APKs detect `assets/standalone_config.json`, extract engine binaries, and launch directly into the game without displaying the GodotDroid management UI.

---

## Building from Source

### Prerequisites
- **JDK 17** (Temurin or OpenJDK recommended)
- **Android SDK** with Platform `android-34` and Build-Tools `34.0.0`
- **Gradle 8.2+** (included via `./gradlew`)
- **Python 3.8+** (for runtime core generation script)

### Compilation & Testing

```bash
# Clone the repository
git clone https://github.com/nevilledev/GodotDroid.git
cd GodotDroid

# Run all unit test suites across all 5 modules
./gradlew test

# Assemble debug APK
./gradlew assembleDebug

# Output APK location:
# app/build/outputs/apk/debug/app-debug.apk
```

---

## Runtime Core Generation Tool

GodotDroid includes a standalone Python CLI tool to extract, package, and verify Godot Android runtime cores from official export template archives:

```bash
# Display help and options
python scripts/build_core_registry.py --help

# Extract cores from local export templates (.tpz or .apk)
python scripts/build_core_registry.py --local-tpz /path/to/templates --output-dir ./core_release

# Download and build specific Godot versions
python scripts/build_core_registry.py --versions 4.3-stable 3.6-stable --output-dir ./core_release
```

The script produces:
- `core_<version>.zip` containing `libgodot_android.so` and `godot-lib.dex`.
- `cores_manifest.json` containing SHA-256 digests, file sizes, and download URLs.

---

## Security & SELinux Compliance

GodotDroid is engineered to comply with modern Android security standards:
- **W^X Enforcement (Android 10 - 15)**: Memory pages cannot be simultaneously writable and executable. Extracted `.dex` and `.so` files are staged into app-internal storage and explicitly marked with `setReadOnly()` prior to loading.
- **Process Isolation**: Game execution runs inside `android:process=":godot_runner"`. Terminating a game forces a complete process exit (`android.os.Process.killProcess`), preventing dynamic state leakage between runs.
- **Scoped Storage & SAF**: Files are imported using Android Storage Access Framework (`ACTION_OPEN_DOCUMENT`) with persistent URI permissions.

---

## Roadmap

- [x] Phase 1: Repository Scaffolding, Zero-Copy PCK/EXE Inspector & Compose Library UI
- [x] Phase 2: Isolated Process Host (`:godot_runner`), Multi-Version Core Manager & Dynamic Loader
- [x] Phase 3: Hardware-Accelerated Touch Overlay, Trackpad Mouse & Hybrid Input Bridge
- [x] Phase 4: Standalone APK Repackager, Binary AXML Patcher, 4-Byte ZIP Alignment & APK Signer
- [x] Phase 5: Godot 3.x Backport, In-PCK Plugin Rewriting, Steam Bypass & CI Pipeline
- [x] Phase 6: Modern Bionic Audio Deadlock Guard (`libgodot_compat.so`), Dual-Source HTTP Range Core Provisioning & Companion Mount Pipeline
- [ ] Phase 7: Custom Shaders & Texture Upscaling (FSR / Bilinear scaling filters)
- [ ] Phase 8: Bluetooth Controller Profiles (Xbox, DualShock, DualSense, Switch Pro)

---

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

Godot Engine is a trademark of the Godot Engine project / Software Freedom Conservancy.
GodotDroid is an independent open-source project and is not affiliated with or endorsed by the Godot Engine project.
