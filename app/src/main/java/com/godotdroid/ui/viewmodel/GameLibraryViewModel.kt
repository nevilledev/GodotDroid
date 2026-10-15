package com.godotdroid.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.godotdroid.data.GameLibraryRepository
import com.godotdroid.data.SavedGameItem
import com.godotdroid.pck.inspector.PckBinaryInspector
import com.godotdroid.pck.model.RendererType
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
}

class GameLibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = GameLibraryRepository(application.applicationContext)
    private val inspector = PckBinaryInspector()

    private val _games = MutableStateFlow<List<SavedGameItem>>(emptyList())
    val games: StateFlow<List<SavedGameItem>> = _games.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _selectedGame = MutableStateFlow<SavedGameItem?>(null)
    val selectedGame: StateFlow<SavedGameItem?> = _selectedGame.asStateFlow()

    private val _uiEvents = MutableSharedFlow<UiEvent>()
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    init {
        loadGames()
    }

    fun loadGames() {
        viewModelScope.launch {
            _isLoading.value = true
            _games.value = repository.getGames()
            _isLoading.value = false
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
                val targetFile = File(sourceFile.parentFile, "${sourceFile.nameWithoutExtension}_extracted.pck")

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
}
