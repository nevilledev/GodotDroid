package com.godotdroid.data

import com.godotdroid.pck.model.GodotGameReport
import com.godotdroid.pck.model.RendererType
import org.json.JSONArray
import org.json.JSONObject

data class SavedGameItem(
    val id: String,
    val filePath: String,
    val originalFileName: String,
    val fileSize: Long,
    val report: GodotGameReport,
    val selectedRenderer: RendererType,
    val dateAdded: Long
) {
    fun toJson(): JSONObject {
        val root = JSONObject()
        root.put("id", id)
        root.put("filePath", filePath)
        root.put("originalFileName", originalFileName)
        root.put("fileSize", fileSize)
        root.put("selectedRenderer", selectedRenderer.name)
        root.put("dateAdded", dateAdded)

        val reportObj = JSONObject()
        reportObj.put("gameTitle", report.gameTitle)
        reportObj.put("mainScene", report.mainScene ?: "")
        reportObj.put("iconPath", report.iconPath ?: "")
        reportObj.put("isEmbeddedExe", report.isEmbeddedExe)
        reportObj.put("pckStartOffset", report.pckStartOffset)
        reportObj.put("totalFilesCount", report.totalFilesCount)
        reportObj.put("renderingMethod", report.renderingMethod ?: "")
        reportObj.put("detectedRenderer", report.detectedRenderer.name)
        reportObj.put("usesDotNet", report.usesDotNet)
        reportObj.put("hasGodotSteam", report.hasGodotSteam)
        reportObj.put("isEncrypted", report.isEncrypted)

        // Header
        val headerObj = JSONObject()
        val h = report.pckHeader
        headerObj.put("magic", h.magic)
        headerObj.put("formatVersion", h.formatVersion)
        headerObj.put("engineMajor", h.engineMajor)
        headerObj.put("engineMinor", h.engineMinor)
        headerObj.put("enginePatch", h.enginePatch)
        headerObj.put("packFlags", h.packFlags)
        headerObj.put("fileBase", h.fileBase)
        headerObj.put("effectiveFileBase", h.effectiveFileBase)
        headerObj.put("directoryOffset", h.directoryOffset)
        headerObj.put("isEncrypted", h.isEncrypted)
        headerObj.put("isEmbeddedExe", h.isEmbeddedExe)
        headerObj.put("pckStartOffset", h.pckStartOffset)
        headerObj.put("fileLength", h.fileLength)
        reportObj.put("header", headerObj)

        // Plugins
        val pluginsArr = JSONArray()
        for (p in report.plugins) {
            val pObj = JSONObject()
            pObj.put("name", p.name)
            pObj.put("configPath", p.configPath)
            pObj.put("isGodot4Extension", p.isGodot4Extension)
            pObj.put("hasAndroidArm64", p.hasAndroidArm64)
            pObj.put("isGodotSteam", p.isGodotSteam)
            val dLibs = JSONObject()
            p.desktopLibraries.forEach { (k, v) -> dLibs.put(k, v) }
            pObj.put("desktopLibraries", dLibs)
            val aLibs = JSONObject()
            p.androidLibraries.forEach { (k, v) -> aLibs.put(k, v) }
            pObj.put("androidLibraries", aLibs)
            pluginsArr.put(pObj)
        }
        reportObj.put("plugins", pluginsArr)

        root.put("report", reportObj)
        return root
    }

    companion object {
        fun fromJson(json: JSONObject): SavedGameItem {
            val id = json.getString("id")
            val filePath = json.getString("filePath")
            val originalFileName = json.getString("originalFileName")
            val fileSize = json.getLong("fileSize")
            val selectedRendererStr = json.optString("selectedRenderer", RendererType.MOBILE.name)
            val selectedRenderer = try {
                RendererType.valueOf(selectedRendererStr)
            } catch (_: Exception) {
                RendererType.MOBILE
            }
            val dateAdded = json.optLong("dateAdded", System.currentTimeMillis())

            val reportObj = json.getJSONObject("report")
            val headerObj = reportObj.getJSONObject("header")
            val header = com.godotdroid.pck.model.PckHeader(
                magic = headerObj.getInt("magic"),
                formatVersion = headerObj.getInt("formatVersion"),
                engineMajor = headerObj.getInt("engineMajor"),
                engineMinor = headerObj.getInt("engineMinor"),
                enginePatch = headerObj.getInt("enginePatch"),
                packFlags = headerObj.getInt("packFlags"),
                fileBase = headerObj.getLong("fileBase"),
                effectiveFileBase = headerObj.getLong("effectiveFileBase"),
                directoryOffset = headerObj.getLong("directoryOffset"),
                isEncrypted = headerObj.getBoolean("isEncrypted"),
                isEmbeddedExe = headerObj.getBoolean("isEmbeddedExe"),
                pckStartOffset = headerObj.getLong("pckStartOffset"),
                fileLength = headerObj.getLong("fileLength")
            )

            val plugins = mutableListOf<com.godotdroid.pck.model.PluginDependency>()
            val missingAndroidPlugins = mutableListOf<com.godotdroid.pck.model.PluginDependency>()
            val pluginsArr = reportObj.optJSONArray("plugins") ?: JSONArray()
            for (i in 0 until pluginsArr.length()) {
                val pObj = pluginsArr.getJSONObject(i)
                val dLibs = mutableMapOf<String, String>()
                val dJson = pObj.optJSONObject("desktopLibraries")
                dJson?.keys()?.forEach { k -> dLibs[k] = dJson.getString(k) }

                val aLibs = mutableMapOf<String, String>()
                val aJson = pObj.optJSONObject("androidLibraries")
                aJson?.keys()?.forEach { k -> aLibs[k] = aJson.getString(k) }

                val plugin = com.godotdroid.pck.model.PluginDependency(
                    name = pObj.getString("name"),
                    configPath = pObj.getString("configPath"),
                    isGodot4Extension = pObj.getBoolean("isGodot4Extension"),
                    desktopLibraries = dLibs,
                    androidLibraries = aLibs,
                    hasAndroidArm64 = pObj.getBoolean("hasAndroidArm64"),
                    isGodotSteam = pObj.getBoolean("isGodotSteam")
                )
                plugins.add(plugin)
                if (plugin.desktopLibraries.isNotEmpty() && !plugin.hasAndroidArm64) {
                    missingAndroidPlugins.add(plugin)
                }
            }

            val detectedRendererStr = reportObj.optString("detectedRenderer", RendererType.UNKNOWN.name)
            val detectedRenderer = try {
                RendererType.valueOf(detectedRendererStr)
            } catch (_: Exception) {
                RendererType.UNKNOWN
            }

            val report = GodotGameReport(
                gameTitle = reportObj.getString("gameTitle"),
                mainScene = reportObj.optString("mainScene").ifBlank { null },
                iconPath = reportObj.optString("iconPath").ifBlank { null },
                pckHeader = header,
                isEmbeddedExe = reportObj.getBoolean("isEmbeddedExe"),
                pckStartOffset = reportObj.getLong("pckStartOffset"),
                totalFilesCount = reportObj.getInt("totalFilesCount"),
                renderingMethod = reportObj.optString("renderingMethod").ifBlank { null },
                detectedRenderer = detectedRenderer,
                usesDotNet = reportObj.optBoolean("usesDotNet", false),
                plugins = plugins,
                missingAndroidPlugins = missingAndroidPlugins,
                hasGodotSteam = reportObj.optBoolean("hasGodotSteam", false),
                isEncrypted = reportObj.optBoolean("isEncrypted", false)
            )

            return SavedGameItem(
                id = id,
                filePath = filePath,
                originalFileName = originalFileName,
                fileSize = fileSize,
                report = report,
                selectedRenderer = selectedRenderer,
                dateAdded = dateAdded
            )
        }
    }
}
