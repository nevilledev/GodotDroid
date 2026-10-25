package com.godotdroid.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.godotdroid.pck.scanner.GameFolderScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class FolderImportProgress(
    val currentFileName: String,
    val filesCopied: Int,
    val totalFiles: Int,
    val bytesCopied: Long,
    val totalBytes: Long
) {
    val progressFraction: Float
        get() = if (totalBytes > 0L) {
            (bytesCopied.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else if (totalFiles > 0) {
            (filesCopied.toFloat() / totalFiles.toFloat()).coerceIn(0f, 1f)
        } else 0f
}

class GameFolderImporter(private val context: Context) {

    companion object {
        private const val TAG = "GameFolderImporter"
        private const val BUFFER_SIZE = 64 * 1024
    }

    private data class TreeFileEntry(
        val documentFile: DocumentFile,
        val relativePath: String,
        val size: Long
    )

    /**
     * Recursively imports the folder selected via ACTION_OPEN_DOCUMENT_TREE into targetDir.
     * Preserves exact relative folder structure, skips junk files, reports progress,
     * and supports user cancellation.
     */
    suspend fun importFolder(
        treeUri: Uri,
        targetDir: File,
        onProgress: (FolderImportProgress) -> Unit,
        isCancelled: () -> Boolean
    ): Result<File> = withContext(Dispatchers.IO) {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext Result.failure(IllegalArgumentException("Could not open selected folder URI"))

        if (!rootDoc.exists() || !rootDoc.isDirectory) {
            return@withContext Result.failure(IllegalArgumentException("Selected URI is not a readable directory"))
        }

        try {
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }
            targetDir.mkdirs()

            // Step 1: Traverse the document tree to collect all files, excluding junk
            val fileEntries = mutableListOf<TreeFileEntry>()

            fun collectTree(dir: DocumentFile, currentPathPrefix: String) {
                if (isCancelled()) throw CancellationException("Import cancelled by user")

                val children = dir.listFiles()
                for (child in children) {
                    if (isCancelled()) throw CancellationException("Import cancelled by user")
                    val childName = child.name ?: continue

                    if (GameFolderScanner.isJunk(childName)) {
                        Log.i(TAG, "Skipping junk path: $currentPathPrefix$childName")
                        continue
                    }

                    if (child.isDirectory) {
                        collectTree(child, "$currentPathPrefix$childName/")
                    } else if (child.isFile) {
                        fileEntries.add(
                            TreeFileEntry(
                                documentFile = child,
                                relativePath = "$currentPathPrefix$childName",
                                size = child.length()
                            )
                        )
                    }
                }
            }

            collectTree(rootDoc, "")

            if (fileEntries.isEmpty()) {
                targetDir.deleteRecursively()
                return@withContext Result.failure(IllegalStateException("No valid files found in selected folder"))
            }

            val totalFiles = fileEntries.size
            val totalBytes = fileEntries.sumOf { it.size }
            var filesCopied = 0
            var bytesCopied = 0L

            val buffer = ByteArray(BUFFER_SIZE)

            // Step 2: Copy each file preserving relative structure
            for (entry in fileEntries) {
                if (isCancelled()) throw CancellationException("Import cancelled by user")

                val destinationFile = File(targetDir, entry.relativePath)
                destinationFile.parentFile?.mkdirs()

                context.contentResolver.openInputStream(entry.documentFile.uri)?.use { input ->
                    FileOutputStream(destinationFile).use { output ->
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            if (isCancelled()) throw CancellationException("Import cancelled by user")
                            output.write(buffer, 0, bytesRead)
                            bytesCopied += bytesRead

                            onProgress(
                                FolderImportProgress(
                                    currentFileName = entry.relativePath,
                                    filesCopied = filesCopied,
                                    totalFiles = totalFiles,
                                    bytesCopied = bytesCopied,
                                    totalBytes = totalBytes
                                )
                            )
                        }
                        output.flush()
                        try { output.fd.sync() } catch (_: Throwable) {}
                    }
                } ?: run {
                    Log.w(TAG, "Could not open stream for: ${entry.relativePath}")
                }

                destinationFile.setReadable(true, false)
                filesCopied++

                onProgress(
                    FolderImportProgress(
                        currentFileName = entry.relativePath,
                        filesCopied = filesCopied,
                        totalFiles = totalFiles,
                        bytesCopied = bytesCopied,
                        totalBytes = totalBytes
                    )
                )
            }

            Log.i(TAG, "Folder import complete: copied $filesCopied files ($bytesCopied bytes) to ${targetDir.absolutePath}")
            Result.success(targetDir)
        } catch (c: CancellationException) {
            Log.i(TAG, "Folder import was cancelled by user, cleaning up ${targetDir.absolutePath}")
            targetDir.deleteRecursively()
            Result.failure(c)
        } catch (e: Exception) {
            Log.e(TAG, "Folder import error: ${e.message}", e)
            targetDir.deleteRecursively()
            Result.failure(e)
        }
    }
}
