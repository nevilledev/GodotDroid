package com.godotdroid.pck.analyzer

import com.godotdroid.pck.inspector.PckConstants
import com.godotdroid.pck.model.RendererType
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ParsedProjectConfig(
    val gameTitle: String?,
    val mainScene: String?,
    val iconPath: String?,
    val renderingMethod: String?,
    val rendererType: RendererType
)

object ProjectConfigParser {

    /**
     * Parses a text project.godot or binary project.binary (ECFG) file content.
     */
    fun parse(bytes: ByteArray): ParsedProjectConfig {
        if (bytes.size >= 4) {
            val magic = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
            if (magic == PckConstants.ECFG_MAGIC) {
                return parseBinaryConfig(bytes)
            }
        }
        return parseTextConfig(bytes)
    }

    /**
     * Parses standard Godot text INI configuration (project.godot).
     */
    fun parseTextConfig(bytes: ByteArray): ParsedProjectConfig {
        val text = String(bytes, Charsets.UTF_8)
        var currentSection = ""
        val properties = mutableMapOf<String, String>()

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) {
                return@forEach
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(1, line.length - 1).trim()
            } else if (line.contains("=")) {
                val eqIdx = line.indexOf('=')
                val rawKey = line.substring(0, eqIdx).trim()
                val rawValue = line.substring(eqIdx + 1).trim()

                val fullKey = if (currentSection.isNotEmpty()) "$currentSection/$rawKey" else rawKey
                val cleanValue = stripQuotes(rawValue)
                properties[fullKey] = cleanValue
                // Also store rawKey directly for convenience
                if (!properties.containsKey(rawKey)) {
                    properties[rawKey] = cleanValue
                }
            }
        }

        val gameTitle = properties["application/config/name"]
            ?: properties["config/name"]

        val mainScene = properties["application/run/main_scene"]
            ?: properties["run/main_scene"]

        val iconPath = properties["application/config/icon"]
            ?: properties["config/icon"]

        val renderingMethod = properties["rendering/renderer/rendering_method"]
            ?: properties["renderer/rendering_method"]
            ?: properties["rendering/quality/driver/driver_name"]
            ?: properties["quality/driver/driver_name"]

        val rendererType = mapRenderer(renderingMethod)

        return ParsedProjectConfig(
            gameTitle = gameTitle,
            mainScene = mainScene,
            iconPath = iconPath,
            renderingMethod = renderingMethod,
            rendererType = rendererType
        )
    }

    /**
     * Parses Godot binary ConfigFile (ECFG magic 0x47464345) with structured variant parsing
     * and regex scanner fallback.
     */
    fun parseBinaryConfig(bytes: ByteArray): ParsedProjectConfig {
        var gameTitle: String? = null
        var mainScene: String? = null
        var iconPath: String? = null
        var renderingMethod: String? = null

        // Strategy 1: Structured binary token scan
        try {
            val strings = extractStringsFromBinary(bytes)
            for (i in strings.indices) {
                val key = strings[i]
                if (key.equals("config/name", ignoreCase = true) ||
                    key.equals("application/config/name", ignoreCase = true)
                ) {
                    if (i + 1 < strings.size && !isConfigKey(strings[i + 1])) {
                        if (gameTitle == null) gameTitle = strings[i + 1]
                    }
                } else if (key.equals("run/main_scene", ignoreCase = true) ||
                    key.equals("application/run/main_scene", ignoreCase = true)
                ) {
                    if (i + 1 < strings.size && strings[i + 1].startsWith("res://")) {
                        if (mainScene == null) mainScene = strings[i + 1]
                    }
                } else if (key.equals("config/icon", ignoreCase = true) ||
                    key.equals("application/config/icon", ignoreCase = true)
                ) {
                    if (i + 1 < strings.size && (strings[i + 1].startsWith("res://") || strings[i + 1].endsWith(".png") || strings[i + 1].endsWith(".svg"))) {
                        if (iconPath == null) iconPath = strings[i + 1]
                    }
                } else if (key.equals("renderer/rendering_method", ignoreCase = true) ||
                    key.equals("rendering/renderer/rendering_method", ignoreCase = true)
                ) {
                    if (i + 1 < strings.size && !isConfigKey(strings[i + 1])) {
                        if (renderingMethod == null) renderingMethod = strings[i + 1]
                    }
                }
            }
        } catch (_: Exception) {
            // Fall through to regex scanner
        }

        // Strategy 2: Fallback regex scanner over printable strings
        if (gameTitle == null || mainScene == null || renderingMethod == null || iconPath == null) {
            val fullText = bytes.map { b ->
                val c = (b.toInt() and 0xFF).toChar()
                if (c in ' '..'~' || c == '\n' || c == '\r' || c == '\t') c else '\u0000'
            }.joinToString("")

            if (gameTitle == null) {
                val match = Regex("""(?:application/config/name|config/name)\x00+([A-Za-z0-9_\- .!?'":]+)""").find(fullText)
                if (match != null) {
                    gameTitle = match.groupValues[1].trim()
                }
            }
            if (mainScene == null) {
                val match = Regex("""res://[a-zA-Z0-9_\-/]+\.(?:tscn|scn)""").find(fullText)
                if (match != null) {
                    mainScene = match.value.trim()
                }
            }
            if (iconPath == null) {
                val match = Regex("""res://[a-zA-Z0-9_\-/]+\.(?:png|svg|webp)""").find(fullText)
                if (match != null) {
                    iconPath = match.value.trim()
                }
            }
            if (renderingMethod == null) {
                val match = Regex("""\b(forward_plus|mobile|gl_compatibility|GLES3|GLES2)\b""").find(fullText)
                if (match != null) {
                    renderingMethod = match.groupValues[1]
                }
            }
        }

        val rendererType = mapRenderer(renderingMethod)

        return ParsedProjectConfig(
            gameTitle = gameTitle,
            mainScene = mainScene,
            iconPath = iconPath,
            renderingMethod = renderingMethod,
            rendererType = rendererType
        )
    }

    private fun isConfigKey(s: String): Boolean {
        return s.contains("/") || s.startsWith("[") || s == "name" || s == "icon" || s == "main_scene"
    }

    private fun extractStringsFromBinary(bytes: ByteArray): List<String> {
        val result = mutableListOf<String>()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.remaining() >= 4) {
            buffer.getInt() // skip ECFG magic
        }

        val sb = StringBuilder()
        while (buffer.hasRemaining()) {
            val b = buffer.get()
            val c = (b.toInt() and 0xFF).toChar()
            if (c in ' '..'~') {
                sb.append(c)
            } else {
                if (sb.length >= 2) {
                    result.add(sb.toString().trim())
                }
                sb.setLength(0)
            }
        }
        if (sb.length >= 2) {
            result.add(sb.toString().trim())
        }
        return result
    }

    private fun stripQuotes(value: String): String {
        var str = value.trim()
        if (str.startsWith("\"") && str.endsWith("\"") && str.length >= 2) {
            str = str.substring(1, str.length - 1)
        }
        return str.replace("\\\"", "\"").replace("\\n", "\n")
    }

    fun mapRenderer(renderingMethod: String?): RendererType {
        if (renderingMethod == null) return RendererType.UNKNOWN
        val lower = renderingMethod.lowercase()
        return when {
            lower.contains("forward_plus") || lower.contains("forward+") -> RendererType.FORWARD_PLUS
            lower.contains("mobile") -> RendererType.MOBILE
            lower == "gles3" -> RendererType.GLES3
            lower == "gles2" -> RendererType.GLES2
            lower.contains("gl_compatibility") || lower.contains("compatibility") -> RendererType.GL_COMPATIBILITY
            else -> RendererType.UNKNOWN
        }
    }
}
