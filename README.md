# GodotDroid

<div align="center">

**High-Performance Android Runtime Host, Binary Emulator & Standalone APK Exporter for PC Godot Games**

[![Android CI](https://github.com/GodotDroid/GodotDroid/actions/workflows/android-ci.yml/badge.svg)](https://github.com/GodotDroid/GodotDroid/actions/workflows/android-ci.yml)
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
├── runtime-host/         # Multi-version core loader, JNI proxy, Steam stub & compatibility injector
├── overlay-controls/     # Virtual gamepad, mouse trackpad, layout editor & hybrid input bridge
├── apk-exporter/         # Binary AXML patcher, 4-byte ZIP alignment & on-device APK signer
└── scripts/              # Standalone Python CLI tools for runtime core packaging
```

### Module Responsibilities

| Module | Purpose | Key Components |
|---|---|---|
| `:pck-inspector` | Binary inspection and virtual filesystem patching | `PckBinaryInspector`, `PeExecutableExtractor`, `ProjectConfigParser`, `PckVirtualPatcher` |
| `:runtime-host` | Engine core management and dynamic execution | `CoreManager`, `DynamicGodotLoader`, `RuntimePatchInjector`, `PluginCompatManager`, `GodotRunnerActivity` |
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

## Core Features in Detail

### 1. In-PCK Virtual File Rewriting (`PckVirtualPatcher`)
Godot resolves assets through its virtual filesystem (`res://`). When desktop games use `.gdextension` or `.gdnlib` configuration files, they often omit Android `arm64-v8a` binary paths. Rather than unpacking gigabytes of game assets, `PckVirtualPatcher`:
- Traverses the PCK binary directory table (supporting Format versions 1, 2, 3, and 4).
- Appends or replaces file payloads at the end of the package.
- Injects `res://override.cfg`, `res://godotdroid_steam_stub.gd`, and patched `.gdextension` / `.gdnlib` tables.
- Updates header file counts, directory offsets, and 4-byte boundaries with zero data corruption.

### 2. Steam API Bypass & Autoload Stubbing
Many PC games include `GodotSteam` and crash immediately on Android when `Steam.steamInit()` cannot find desktop dynamic libraries (`steam_api64.dll` / `libsteam_api.so`). GodotDroid generates an engine-specific GDScript singleton:
- **Godot 4.x**: Strongly typed GDScript (`godotdroid_steam_stub.gd`) returning valid dictionary responses for initialization, achievements, persona names, and mock Steam IDs (`76561197960287930`).
- **Godot 3.x**: Untyped GDScript compatible with Godot 3 syntax.
- The stub is registered via `[autoload]` in `override.cfg` so `Steam` is accessible globally before game scenes load.

### 3. Touch Overlay & Hybrid Input Dispatch (`GodotInputBridge`)
The input system translates mobile touch events into desktop game actions using a dual-path bridge:
1. **Direct JNI Reflection**: Calls internal `org.godotengine.godot.GodotLib` methods (`joybutton`, `joyaxis`, `key`, `touch`) inside the running engine.
2. **Synthetic Android View Events**: Dispatches native `MotionEvent` and `KeyEvent` with proper source flags (`SOURCE_GAMEPAD`, `SOURCE_KEYBOARD`, `SOURCE_MOUSE`) to Godot's active `SurfaceView`.
3. **PC Trackpad Emulation**: Includes a dedicated trackpad control translating finger swipes into relative mouse movement, alongside physical Left Click and Right Click buttons.

### 4. Standalone APK Repackaging (`:apk-exporter`)
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
git clone https://github.com/GodotDroid/GodotDroid.git
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
- [ ] Phase 6: Custom Shaders & Texture Upscaling (FSR / Bilinear scaling filters)
- [ ] Phase 7: Bluetooth Controller Profiles (Xbox, DualShock, DualSense, Switch Pro)

---

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

Godot Engine is a trademark of the Godot Engine project / Software Freedom Conservancy.
GodotDroid is an independent open-source project and is not affiliated with or endorsed by the Godot Engine project.
