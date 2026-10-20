package com.godotdroid.runtime.patcher

import java.io.File

/**
 * Generates an autoload script (godotdroid_companion_mount.gd) that pre-mounts
 * secondary/companion .pck files (e.g. love.pck, DLCs, mods) into Godot's virtual
 * res:// filesystem before any game scenes or scripts load.
 *
 * Supports both Godot 4.x (DirAccess) and Godot 3.x (Directory).
 */
object CompanionPackMounterGenerator {

    fun escapeGdString(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    fun generateMountScript(
        companionPcks: List<String>,
        mainPackName: String,
        engineMajor: Int = 4,
        runtimeDirPath: String = ""
    ): String {
        val isGodot3 = engineMajor == 3
        val cleanDir = escapeGdString(runtimeDirPath.replace('\\', '/').trimEnd('/'))
        val escapedMainPack = escapeGdString(mainPackName)
        val sb = StringBuilder()

        sb.appendLine("# GodotDroid Companion Pack Auto-Mounter")
        sb.appendLine("# Automatically mounts secondary and DLC .pck files on Android")
        sb.appendLine("extends Node")
        sb.appendLine()
        sb.appendLine("func _init():")
        sb.appendLine("    print(\"[GodotDroid] Initializing companion pack auto-mounter...\")")

        val pckArrayLiteral = companionPcks.joinToString(", ") { "\"${escapeGdString(it)}\"" }
        sb.appendLine("    var known_packs = [$pckArrayLiteral]")
        sb.appendLine("    for pack_name in known_packs:")
        sb.appendLine("        _mount_pack(pack_name)")
        sb.appendLine()

        if (isGodot3) {
            sb.appendLine("    # Godot 3 dynamic directory scan fallback")
            sb.appendLine("    var dir = Directory.new()")
            sb.appendLine("    if dir.open(\".\") == OK:")
            sb.appendLine("        dir.list_dir_begin(true)")
            sb.appendLine("        var f = dir.get_next()")
            sb.appendLine("        while f != \"\":")
            sb.appendLine("            var lower_f = f.to_lower()")
            sb.appendLine("            if not dir.current_is_dir() and (lower_f.ends_with(\".pck\") or lower_f.ends_with(\".zip\")):")
            sb.appendLine("                if lower_f != \"runtime_staged.pck\" and f != \"$escapedMainPack\":")
            sb.appendLine("                    _mount_pack(f)")
            sb.appendLine("            f = dir.get_next()")
            sb.appendLine("    print(\"[GodotDroid] Companion pack auto-mounter finished.\")")
            sb.appendLine()
            sb.appendLine("func _mount_pack(pack_path):")
            sb.appendLine("    if pack_path == \"\" or ProjectSettings.has_setting(\"godotdroid/mounted/\" + pack_path):")
            sb.appendLine("        return")
            sb.appendLine("    var ok = ProjectSettings.load_resource_pack(pack_path)")
            if (cleanDir.isNotBlank()) {
                sb.appendLine("    if not ok and not pack_path.begins_with(\"/\"):")
                sb.appendLine("        ok = ProjectSettings.load_resource_pack(\"$cleanDir/\".plus_file(pack_path))")
            }
            sb.appendLine("    if not ok and not pack_path.begins_with(\"/\"):")
            sb.appendLine("        ok = ProjectSettings.load_resource_pack(\"./\" + pack_path)")
            sb.appendLine("    if not ok and not pack_path.begins_with(\"/\"):")
            sb.appendLine("        ok = ProjectSettings.load_resource_pack(\"res://\" + pack_path)")
            sb.appendLine("    if not ok and not pack_path.begins_with(\"/\"):")
            sb.appendLine("        var d = Directory.new()")
            sb.appendLine("        if d.open(\".\") == OK:")
            sb.appendLine("            ok = ProjectSettings.load_resource_pack(d.get_current_dir().plus_file(pack_path))")
            sb.appendLine("    if ok:")
            sb.appendLine("        ProjectSettings.set_setting(\"godotdroid/mounted/\" + pack_path, true)")
            sb.appendLine("        print(\"[GodotDroid] Successfully mounted companion pack: \", pack_path)")
            sb.appendLine("    else:")
            sb.appendLine("        print(\"[GodotDroid] Warning: Could not mount companion pack: \", pack_path)")
        } else {
            sb.appendLine("    # Godot 4 dynamic directory scan fallback")
            sb.appendLine("    var dir = DirAccess.open(\".\")")
            sb.appendLine("    if dir != null:")
            sb.appendLine("        dir.list_dir_begin()")
            sb.appendLine("        var f = dir.get_next()")
            sb.appendLine("        while f != \"\":")
            sb.appendLine("            var lower_f = f.to_lower()")
            sb.appendLine("            if not dir.current_is_dir() and (lower_f.ends_with(\".pck\") or lower_f.ends_with(\".zip\")):")
            sb.appendLine("                if lower_f != \"runtime_staged.pck\" and f != \"$escapedMainPack\":")
            sb.appendLine("                    _mount_pack(f)")
            sb.appendLine("            f = dir.get_next()")
            sb.appendLine("    print(\"[GodotDroid] Companion pack auto-mounter finished.\")")
            sb.appendLine()
            sb.appendLine("func _mount_pack(pack_path: String) -> void:")
            sb.appendLine("    if pack_path == \"\" or ProjectSettings.has_setting(\"godotdroid/mounted/\" + pack_path):")
            sb.appendLine("        return")
            sb.appendLine("    var ok = ProjectSettings.load_resource_pack(pack_path)")
            if (cleanDir.isNotBlank()) {
                sb.appendLine("    if not ok and not pack_path.begins_with(\"/\"):")
                sb.appendLine("        ok = ProjectSettings.load_resource_pack(\"$cleanDir/\" + pack_path)")
            }
            sb.appendLine("    if not ok and not pack_path.begins_with(\"/\"):")
            sb.appendLine("        ok = ProjectSettings.load_resource_pack(\"./\" + pack_path)")
            sb.appendLine("    if not ok and not pack_path.begins_with(\"/\"):")
            sb.appendLine("        ok = ProjectSettings.load_resource_pack(\"res://\" + pack_path)")
            sb.appendLine("    if not ok and not pack_path.begins_with(\"/\"):")
            sb.appendLine("        var d = DirAccess.open(\".\")")
            sb.appendLine("        if d != null:")
            sb.appendLine("            ok = ProjectSettings.load_resource_pack(d.get_current_dir() + \"/\" + pack_path)")
            sb.appendLine("    if ok:")
            sb.appendLine("        ProjectSettings.set_setting(\"godotdroid/mounted/\" + pack_path, true)")
            sb.appendLine("        print(\"[GodotDroid] Successfully mounted companion pack: \", pack_path)")
            sb.appendLine("    else:")
            sb.appendLine("        print(\"[GodotDroid] Warning: Could not mount companion pack: \", pack_path)")
        }

        return sb.toString()
    }

    fun writeScriptToDirectory(
        directory: File,
        companionPcks: List<String>,
        mainPackName: String,
        engineMajor: Int = 4,
        runtimeDirPath: String = directory.absolutePath
    ): File {
        val file = File(directory, "godotdroid_companion_mount.gd")
        if (file.exists()) file.setWritable(true)
        file.writeText(generateMountScript(companionPcks, mainPackName, engineMajor, runtimeDirPath), Charsets.UTF_8)
        file.setReadOnly()
        return file
    }
}
