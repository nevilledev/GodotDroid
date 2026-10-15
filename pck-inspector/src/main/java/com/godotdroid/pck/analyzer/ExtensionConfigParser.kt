package com.godotdroid.pck.analyzer

import com.godotdroid.pck.model.PluginDependency

object ExtensionConfigParser {

    /**
     * Parses a .gdextension (Godot 4) or .gdnlib (Godot 3) text INI file.
     */
    fun parse(configPath: String, textContent: String): PluginDependency {
        val isGodot4 = configPath.endsWith(".gdextension", ignoreCase = true)
        val pluginName = extractPluginName(configPath)

        val desktopLibs = mutableMapOf<String, String>()
        val androidLibs = mutableMapOf<String, String>()

        var currentSection = ""

        textContent.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                return@forEach
            }

            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(1, line.length - 1).trim().lowercase()
            } else if (line.contains("=")) {
                val eqIdx = line.indexOf('=')
                val rawKey = line.substring(0, eqIdx).trim()
                val rawVal = line.substring(eqIdx + 1).trim()
                val cleanVal = stripQuotes(rawVal)

                val keyLower = rawKey.lowercase()

                // In Godot 4: section is usually [libraries]
                // In Godot 3: section is usually [entry] or [libraries]
                if (currentSection == "libraries" || currentSection == "entry" || currentSection.isEmpty()) {
                    if (isAndroidTarget(keyLower) || isAndroidTarget(cleanVal.lowercase())) {
                        androidLibs[rawKey] = cleanVal
                    } else if (isDesktopTarget(keyLower) || isDesktopTarget(cleanVal.lowercase())) {
                        desktopLibs[rawKey] = cleanVal
                    }
                }
            }
        }

        // Determine if Android arm64-v8a / arm64 is present
        val hasAndroidArm64 = androidLibs.keys.any { k ->
            val l = k.lowercase()
            l.contains("arm64") || l.contains("arm64-v8a") || l.contains("aarch64")
        } || androidLibs.values.any { v ->
            val l = v.lowercase()
            l.contains("arm64") || l.contains("aarch64")
        }

        // Detect GodotSteam
        val isSteam = pluginName.contains("steam", ignoreCase = true) ||
                configPath.contains("steam", ignoreCase = true) ||
                desktopLibs.values.any { it.contains("steam", ignoreCase = true) } ||
                androidLibs.values.any { it.contains("steam", ignoreCase = true) }

        return PluginDependency(
            name = pluginName,
            configPath = configPath,
            isGodot4Extension = isGodot4,
            desktopLibraries = desktopLibs,
            androidLibraries = androidLibs,
            hasAndroidArm64 = hasAndroidArm64,
            isGodotSteam = isSteam
        )
    }

    private fun extractPluginName(configPath: String): String {
        val fileName = configPath.substringAfterLast('/').substringAfterLast('\\')
        return fileName.substringBeforeLast('.')
    }

    private fun isAndroidTarget(str: String): Boolean {
        return str.contains("android") || str.contains("arm64") || str.contains("armeabi") || str.contains("armv7")
    }

    private fun isDesktopTarget(str: String): Boolean {
        return str.contains("windows") || str.contains("linux") || str.contains("x11") ||
                str.contains("osx") || str.contains("macos") || str.contains("win32") ||
                str.contains("win64") || str.endsWith(".dll")
    }

    private fun stripQuotes(value: String): String {
        var str = value.trim()
        if (str.startsWith("\"") && str.endsWith("\"") && str.length >= 2) {
            str = str.substring(1, str.length - 1)
        }
        return str
    }
}
