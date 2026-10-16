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
    data class PromptCoreInstall(val versionKey: String, val gameTitle: String) : UiEvent()
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
    private val _installedCores = MutableStateFlow<List<InstalledCore>>(emptyList())
    val installedCores: StateFlow<List<InstalledCore>> = _installedCores.asStateFlow()

    private val _isDownloadingCore = MutableStateFlow(false)
    val isDownloadingCore: StateFlow<Boolean> = _isDownloadingCore.asStateFlow()

    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress: StateFlow<DownloadProgress?> = _downloadProgress.asStateFlow()

    private val _registryCores = MutableStateFlow<List<CoreDescriptor>>(coreManager.getRegistryCatalog())
    val registryCores: StateFlow<List<CoreDescriptor>> = _registryCores.asStateFlow()

    private val _isSyncingManifest = MutableStateFlow(false)
    val isSyncingManifest: StateFlow<Boolean> = _isSyncingManifest.asStateFlow()

    private val _uiEvents = MutableSharedFlow<UiEvent>()
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    init {
        loadGames()
        refreshCores()
        refreshRegistryCatalog()
    }

    fun refreshRegistryCatalog() {
        _registryCores.value = coreManager.getRegistryCatalog()
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
            _installedCores.value = coreManager.getInstalledCores()
        }
    }

    fun importGame(uri: Uri) {
        viewModelScope.launch {
            _isImporting.value = true
            val result = repository.importGameFromUri(uri)
            _isImporting.value = false

            result.onSuccess { item ->
                _games.value = repository.getGames()
                _selectedGame.value = item
                _uiEvents.emit(UiEvent.ShowMessage("Successfully imported: ${item.report.gameTitle}"))
            }.onFailure { error ->
                _uiEvents.emit(UiEvent.ShowMessage("Failed to import game: ${error.message}"))
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

            val matchingCore = coreManager.getCore(versionKey)
                ?: coreManager.findMatchingCore(major, minor)

            if (matchingCore == null) {
                _uiEvents.emit(UiEvent.PromptCoreInstall(versionKey, game.report.gameTitle))
                return@launch
            }

            val intent = GodotRunnerActivity.createIntent(
                context = context,
                gameId = game.id,
                gameTitle = game.report.gameTitle,
                filePath = game.filePath,
                renderer = game.selectedRenderer,
                versionKey = matchingCore.versionKey,
                mainSceneOverride = game.report.mainScene,
                injectSteamStub = injectSteamStub,
                controlPreset = controlPreset
            )

            _uiEvents.emit(UiEvent.LaunchGame(intent))
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
            try {
                val context = getApplication<Application>().applicationContext
                val input = context.contentResolver.openInputStream(uri)
                if (input == null) {
                    _uiEvents.emit(UiEvent.ShowMessage("Could not open template file"))
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
                    _uiEvents.emit(UiEvent.ShowMessage("Failed to import core: ${error.message}"))
                }
            } catch (e: Exception) {
                _isDownloadingCore.value = false
                _downloadProgress.value = null
                _uiEvents.emit(UiEvent.ShowMessage("Error importing template: ${e.message}"))
            }
        }
    }

    fun downloadCore(descriptor: CoreDescriptor) {
        viewModelScope.launch {
            _isDownloadingCore.value = true
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
                _uiEvents.emit(UiEvent.ShowMessage("Failed to download core: ${error.message}"))
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
            if (!customUrl.isNullOrBlank()) {
                coreManager.setRegistryBaseUrl(customUrl)
            }
            val result = coreManager.fetchRemoteManifest()
            _isSyncingManifest.value = false

            result.onSuccess { list ->
                refreshRegistryCatalog()
                _uiEvents.emit(UiEvent.ShowMessage("Registry synced: ${list.size} core(s) available"))
            }.onFailure { error ->
                _uiEvents.emit(UiEvent.ShowMessage("Manifest sync failed: ${error.message}"))
            }
        }
    }
}
