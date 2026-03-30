package no.ringlog.app.ui.flocks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import no.ringlog.app.data.api.Bird
import no.ringlog.app.data.api.Flock
import no.ringlog.app.data.api.FlocksResponse
import no.ringlog.app.data.repository.FlockRepository
import javax.inject.Inject

@HiltViewModel
class FlockViewModel @Inject constructor(private val repo: FlockRepository) : ViewModel() {

    sealed class ListState { object Loading : ListState()
        data class Success(val data: FlocksResponse) : ListState()
        data class Error(val msg: String) : ListState() }

    sealed class DetailState { object Loading : DetailState()
        data class Success(val flock: Flock) : DetailState()
        data class Error(val msg: String) : DetailState() }

    sealed class BirdState { object Loading : BirdState()
        data class Success(val bird: Bird) : BirdState()
        data class Error(val msg: String) : BirdState() }

    sealed class ImageUploadState {
        object Idle : ImageUploadState()
        object Loading : ImageUploadState()
        object Success : ImageUploadState()
        data class Error(val msg: String) : ImageUploadState()
    }

    sealed class UpdateBirdState {
        object Idle : UpdateBirdState()
        object Loading : UpdateBirdState()
        object Success : UpdateBirdState()
        data class Error(val msg: String) : UpdateBirdState()
    }

    private val _listState   = MutableStateFlow<ListState>(ListState.Loading)
    private val _detailState = MutableStateFlow<DetailState>(DetailState.Loading)
    private val _birdState   = MutableStateFlow<BirdState>(BirdState.Loading)

    private val _imageUploadState  = MutableStateFlow<ImageUploadState>(ImageUploadState.Idle)
    private val _updateBirdState   = MutableStateFlow<UpdateBirdState>(UpdateBirdState.Idle)

    val listState        = _listState.asStateFlow()
    val detailState      = _detailState.asStateFlow()
    val birdState        = _birdState.asStateFlow()
    val imageUploadState = _imageUploadState.asStateFlow()
    val updateBirdState  = _updateBirdState.asStateFlow()

    fun loadFlocks() {
        viewModelScope.launch {
            _listState.value = ListState.Loading
            repo.getFlocks().fold(
                onSuccess = { _listState.value = ListState.Success(it) },
                onFailure = { _listState.value = ListState.Error(it.message ?: "Error") },
            )
        }
    }

    fun loadFlock(id: Int) {
        viewModelScope.launch {
            _detailState.value = DetailState.Loading
            repo.getFlockDetail(id).fold(
                onSuccess = { _detailState.value = DetailState.Success(it) },
                onFailure = { _detailState.value = DetailState.Error(it.message ?: "Error") },
            )
        }
    }

    fun loadBird(id: Int) {
        viewModelScope.launch {
            _birdState.value = BirdState.Loading
            repo.getBirdDetail(id).fold(
                onSuccess = { _birdState.value = BirdState.Success(it) },
                onFailure = { _birdState.value = BirdState.Error(it.message ?: "Error") },
            )
        }
    }

    fun deleteBird(birdId: Int, flockId: Int, onDone: () -> Unit) {
        viewModelScope.launch {
            repo.deleteBird(birdId).onSuccess { loadFlock(flockId) }
            onDone()
        }
    }

    fun uploadImage(birdId: Int, bytes: ByteArray, mime: String) {
        viewModelScope.launch {
            _imageUploadState.value = ImageUploadState.Loading
            repo.uploadBirdImage(birdId, bytes, mime).fold(
                onSuccess = {
                    _imageUploadState.value = ImageUploadState.Success
                    loadBird(birdId)
                },
                onFailure = {
                    _imageUploadState.value = ImageUploadState.Error(it.message ?: "Upload failed")
                }
            )
        }
    }

    fun resetImageUpload() { _imageUploadState.value = ImageUploadState.Idle }

    fun updateBird(birdId: Int, fields: Map<String, String>) {
        viewModelScope.launch {
            _updateBirdState.value = UpdateBirdState.Loading
            repo.updateBird(birdId, fields).fold(
                onSuccess = {
                    _birdState.value = BirdState.Success(it)
                    _updateBirdState.value = UpdateBirdState.Success
                },
                onFailure = {
                    _updateBirdState.value = UpdateBirdState.Error(it.message ?: "Update failed")
                }
            )
        }
    }

    fun resetUpdateBird() { _updateBirdState.value = UpdateBirdState.Idle }

    fun addNote(birdId: Int, date: String, content: String) {
        viewModelScope.launch {
            repo.addNote(birdId, date, content)
            loadBird(birdId)
        }
    }
}
