package com.godotdroid.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.godotdroid.data.GameLibraryRepository
import com.godotdroid.data.SavedGameItem
import com.godotdroid.pck.inspector.PckBinaryInspector
import com.godotdroid.pck.model.RendererType
import com.godotdroid.runtime.activity.GodotRunnerActivity
import com.godotdroid.runtime.core.CoreDescriptor
import com.godotdroid.runtime.core.CoreManager
import com.godotdroid.runtime.core.DownloadProgress
import com.godotdroid.runtime.core.InstalledCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed class UiEvent {
    data class ShowMessage(val message: String) : UiEvent()
    data class ShowLaunchDialog(val gameTitle: String, val renderer: RendererType) : UiEvent()
    data class ShowExportDialog(val gameTitle: String) : UiEvent()
    data class PromptCoreInstall(
        val versionKey: String,
        val gameTitle: String,
        val fallbackCore: InstalledCore? = null,
        val game: SavedGameItem,
        val controlPreset: String = com.godotdroid.overlay.model.DefaultControlPresets.PRESET_PC_KEYBOARD,
        val injectSteamStub: Boolean = false
    ) : UiEvent()
    data class LaunchGame(val intent: Intent) : UiEvent()
}

class GameLibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = GameLibraryRepository(application.applicationContext)
    private val inspector = PckBinaryInspector()
    val coreManager = CoreManager(application.applicationContext)

    private val _games = MutableStateFlow<List<SavedGameItem>>(emptyList())
    val games: StateFlow<List<SavedGameItem>> = _games.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _selectedGame = MutableStateFlow<SavedGameItem?>(null)
    val selectedGame: StateFlow<SavedGameItem?> = _selectedGame.asStateFlow()

    // Core Management States
    val installedCores: StateFlow<List<InstalledCore>> = coreManager.installedCores

    private val _isDownloadingCore = MutableStateFlow(false)
    val isDownloadingCore: StateFlow<Boolean> = _isDownloadingCore.asStateFlow()

    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress: StateFlow<DownloadProgress?> = _downloadProgress.asStateFlow()

    val registryCores: StateFlow<List<CoreDescriptor>> = coreManager.registryCatalog

    private val _isSyncingManifest = MutableStateFlow(false)
    val isSyncingManifest: StateFlow<Boolean> = _isSyncingManifest.asStateFlow()

    private val _coreErrorMessage = MutableStateFlow<String?>(null)
    val coreErrorMessage: StateFlow<String?> = _coreErrorMessage.asStateFlow()

    private val _uiEvents = MutableSharedFlow<UiEvent>()
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    init {
        loadGames()
        refreshCores()
    }

    fun clearCoreError() {
        _coreErrorMessage.value = null
    }

    fun refreshRegistryCatalog() {
        // Automatically reactive via coreManager.registryCatalog
    }

    fun loadGames() {
        viewModelScope.launch {
            _isLoading.value = true
            _games.value = repository.getGames()
            _isLoading.value = false
        }
    }

    fun refreshCores() {
        viewModelScope.launch {
            coreManager.refreshInstalledCores()
        }
    }

    fun importGame(uri: Uri) = importGames(listOf(uri))

    fun importGames(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _isImporting.value = true
            val result = repository.importGameFromUris(uris)
            _isImporting.value = false

            result.onSuccess { item ->
                _games.value = repository.getGames()
                _selectedGame.value = item
                val companionCount = repository.getCompanionFiles(item).size
                val msg = if (companionCount > 0) {
                    "Successfully imported: ${item.report.gameTitle} ($companionCount companion file(s) attached)"
                } else {
                    "Successfully imported: ${item.report.gameTitle}"
                }
                _uiEvents.emit(UiEvent.ShowMessage(msg))
            }.onFailure { error ->
                _uiEvents.emit(UiEvent.ShowMessage("Failed to import game: ${error.message}"))
            }
        }
    }

    fun resolveParentFolder(uri: Uri): File? {
        return repository.resolveParentDirectory(uri)
    }

    fun getCompanionFiles(game: SavedGameItem): List<File> {
        return repository.getCompanionFiles(game)
    }

    fun addCompanionFiles(game: SavedGameItem, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val result = repository.addCompanionFiles(game.id, uris)
            result.onSuccess {
                _games.value = repository.getGames()
                val updated = repository.getGames().find { it.id == game.id }
                if (updated != null) {
                    _selectedGame.value = updated
                }
                _uiEvents.emit(UiEvent.ShowMessage("Attached ${uris.size} companion file(s) to ${game.report.gameTitle}"))
            }.onFailure { error ->
                _uiEvents.emit(UiEvent.ShowMessage("Failed to attach companion file: ${error.message}"))
            }
        }
    }

    fun removeCompanionFile(game: SavedGameItem, fileName: String) {
        viewModelScope.launch {
            val success = repository.removeCompanionFile(game.id, fileName)
            if (success) {
                _games.value = repository.getGames()
                val updated = repository.getGames().find { it.id == game.id }
                if (updated != null) {
                    _selectedGame.value = updated
                }
                _uiEvents.emit(UiEvent.ShowMessage("Removed $fileName"))
            }
        }
    }

    fun selectGame(game: SavedGameItem?) {
        _selectedGame.value = game
    }

    fun updateRendererOverride(game: SavedGameItem, renderer: RendererType) {
        viewModelScope.launch {
            repository.updateRendererOverride(game.id, renderer)
            _games.value = repository.getGames()
            if (_selectedGame.value?.id == game.id) {
                _selectedGame.value = _selectedGame.value?.copy(selectedRenderer = renderer)
            }
        }
    }

    fun deleteGame(game: SavedGameItem) {
        viewModelScope.launch {
            repository.deleteGame(game.id)
            _games.value = repository.getGames()
            if (_selectedGame.value?.id == game.id) {
                _selectedGame.value = null
            }
            _uiEvents.emit(UiEvent.ShowMessage("Removed ${game.report.gameTitle} from library"))
        }
    }

    fun extractStandalonePck(game: SavedGameItem) {
        viewModelScope.launch {
            try {
                val sourceFile = File(game.filePath)
                val targetFile = File(sourceFile.parentFile, "${sourceFile.nameWithoutExtension}_standalone.pck")

                val transferred = withContext(Dispatchers.IO) {
                    inspector.extractStandalonePck(sourceFile, targetFile, game.report.pckHeader)
                }

                _uiEvents.emit(
                    UiEvent.ShowMessage("Extracted standalone package ($transferred bytes) to: ${targetFile.name}")
                )
            } catch (e: Exception) {
                _uiEvents.emit(UiEvent.ShowMessage("Extraction failed: ${e.message}"))
            }
        }
    }

    fun launchGame(
        context: Context,
        game: SavedGameItem,
        controlPreset: String = com.godotdroid.overlay.model.DefaultControlPresets.PRESET_PC_KEYBOARD,
        injectSteamStub: Boolean = false
    ) {
        viewModelScope.launch {
            val major = game.report.pckHeader.engineMajor
            val minor = game.report.pckHeader.engineMinor
            val versionKey = "$major.$minor"

            val exactCore = coreManager.getCore(versionKey)
            if (exactCore == null) {
                val fallbackCore = coreManager.findMatchingCore(major, minor)
                _uiEvents.emit(
                    UiEvent.PromptCoreInstall(
                        versionKey = versionKey,
                        gameTitle = game.report.gameTitle,
                        fallbackCore = fallbackCore,
                        game = game,
                        controlPreset = controlPreset,
                        injectSteamStub = injectSteamStub
                    )
                )
                return@launch
            }

            val intent = GodotRunnerActivity.createIntent(
                context = context,
                gameId = game.id,
                gameTitle = game.report.gameTitle,
                filePath = game.filePath,
                renderer = game.selectedRenderer,
                versionKey = exactCore.versionKey,
                mainSceneOverride = game.report.mainScene,
                injectSteamStub = injectSteamStub,
                controlPreset = controlPreset
            )

            _uiEvents.emit(UiEvent.LaunchGame(intent))
        }
    }

    fun launchWithInstalledCore(
        context: Context,
        game: SavedGameItem,
        installedCore: InstalledCore,
        controlPreset: String = com.godotdroid.overlay.model.DefaultControlPresets.PRESET_PC_KEYBOARD,
        injectSteamStub: Boolean = false
    ) {
        viewModelScope.launch {
            val intent = GodotRunnerActivity.createIntent(
                context = context,
                gameId = game.id,
                gameTitle = game.report.gameTitle,
                filePath = game.filePath,
                renderer = game.selectedRenderer,
                versionKey = installedCore.versionKey,
                mainSceneOverride = game.report.mainScene,
                injectSteamStub = injectSteamStub,
                controlPreset = controlPreset
            )
            _uiEvents.emit(UiEvent.LaunchGame(intent))
        }
    }

    fun autoDownloadAndLaunch(
        context: Context,
        game: SavedGameItem,
        versionKey: String,
        controlPreset: String = com.godotdroid.overlay.model.DefaultControlPresets.PRESET_PC_KEYBOARD,
        injectSteamStub: Boolean = false
    ) {
        viewModelScope.launch {
            _isDownloadingCore.value = true
            _coreErrorMessage.value = null

            val descriptor = coreManager.getRegistryCatalog().firstOrNull { it.versionKey == versionKey }
                ?: CoreDescriptor(
                    versionKey = versionKey,
                    displayName = "Godot $versionKey",
                    engineVersion = "$versionKey.0",
                    downloadUrl = "${coreManager.getRegistryBaseUrl()}/core_$versionKey.zip"
                )

            val result = coreManager.downloadCore(
                descriptor = descriptor,
                onProgress = { progress -> _downloadProgress.value = progress }
            )

            _isDownloadingCore.value = false
            _downloadProgress.value = null

            result.onSuccess { installedCore ->
                refreshCores()
                _uiEvents.emit(UiEvent.ShowMessage("Godot $versionKey core installed! Launching ${game.report.gameTitle}..."))
                val intent = GodotRunnerActivity.createIntent(
                    context = context,
                    gameId = game.id,
                    gameTitle = game.report.gameTitle,
                    filePath = game.filePath,
                    renderer = game.selectedRenderer,
                    versionKey = installedCore.versionKey,
                    mainSceneOverride = game.report.mainScene,
                    injectSteamStub = injectSteamStub,
                    controlPreset = controlPreset
                )
                _uiEvents.emit(UiEvent.LaunchGame(intent))
            }.onFailure { error ->
                val msg = "Auto-download failed: ${error.message}"
                _coreErrorMessage.value = msg
                _uiEvents.emit(UiEvent.ShowMessage(msg))
            }
        }
    }

    fun onLaunchGameClicked(game: SavedGameItem) {
        viewModelScope.launch {
            _uiEvents.emit(UiEvent.ShowLaunchDialog(game.report.gameTitle, game.selectedRenderer))
        }
    }

    fun onExportApkClicked(game: SavedGameItem) {
        viewModelScope.launch {
            _uiEvents.emit(UiEvent.ShowExportDialog(game.report.gameTitle))
        }
    }

    // Core Management Operations
    fun importCoreFromTemplate(uri: Uri, versionKey: String) {
        viewModelScope.launch {
            _isDownloadingCore.value = true
            _coreErrorMessage.value = null
            try {
                val context = getApplication<Application>().applicationContext
                val input = context.contentResolver.openInputStream(uri)
                if (input == null) {
                    val msg = "Could not open template file"
                    _coreErrorMessage.value = msg
                    _uiEvents.emit(UiEvent.ShowMessage(msg))
                    _isDownloadingCore.value = false
                    return@launch
                }

                val result = coreManager.importCoreFromTemplate(
                    inputStream = input,
                    versionKey = versionKey,
                    onProgress = { progress -> _downloadProgress.value = progress }
                )

                _isDownloadingCore.value = false
                _downloadProgress.value = null

                result.onSuccess { core ->
                    refreshCores()
                    _uiEvents.emit(UiEvent.ShowMessage("Successfully imported Godot ${core.versionKey} core!"))
                }.onFailure { error ->
                    val msg = "Failed to import core: ${error.message}"
                    _coreErrorMessage.value = msg
                    _uiEvents.emit(UiEvent.ShowMessage(msg))
                }
            } catch (e: Exception) {
                _isDownloadingCore.value = false
                _downloadProgress.value = null
                val msg = "Error importing template: ${e.message}"
                _coreErrorMessage.value = msg
                _uiEvents.emit(UiEvent.ShowMessage(msg))
            }
        }
    }

    fun downloadCore(descriptor: CoreDescriptor) {
        viewModelScope.launch {
            _isDownloadingCore.value = true
            _coreErrorMessage.value = null
            val result = coreManager.downloadCore(
                descriptor = descriptor,
                onProgress = { progress -> _downloadProgress.value = progress }
            )
            _isDownloadingCore.value = false
            _downloadProgress.value = null

            result.onSuccess { core ->
                refreshCores()
                _uiEvents.emit(UiEvent.ShowMessage("Installed ${descriptor.displayName}!"))
            }.onFailure { error ->
                val msg = "Failed to download core: ${error.message}"
                _coreErrorMessage.value = msg
                _uiEvents.emit(UiEvent.ShowMessage(msg))
            }
        }
    }

    fun deleteCore(versionKey: String) {
        viewModelScope.launch {
            val deleted = coreManager.deleteCore(versionKey)
            if (deleted) {
                refreshCores()
                _uiEvents.emit(UiEvent.ShowMessage("Removed Godot $versionKey core"))
            } else {
                _uiEvents.emit(UiEvent.ShowMessage("Failed to remove core"))
            }
        }
    }

    fun getRegistryBaseUrl(): String = coreManager.getRegistryBaseUrl()

    fun syncRemoteRegistry(customUrl: String? = null) {
        viewModelScope.launch {
            _isSyncingManifest.value = true
            _coreErrorMessage.value = null
            if (!customUrl.isNullOrBlank()) {
                coreManager.setRegistryBaseUrl(customUrl)
            }
            val result = coreManager.syncRemoteManifest()
            _isSyncingManifest.value = false

            result.onSuccess { list ->
                _uiEvents.emit(UiEvent.ShowMessage("Registry synced: ${list.size} core(s) available"))
            }.onFailure { error ->
                val msg = "Manifest sync failed: ${error.message}"
                _coreErrorMessage.value = msg
                _uiEvents.emit(UiEvent.ShowMessage(msg))
            }
        }
    }
}
