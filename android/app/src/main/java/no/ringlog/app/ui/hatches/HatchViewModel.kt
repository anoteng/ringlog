package no.ringlog.app.ui.hatches

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import no.ringlog.app.data.api.Hatch
import no.ringlog.app.data.api.HatchRequest
import no.ringlog.app.data.api.HatchesResponse
import no.ringlog.app.data.repository.HatchRepository
import javax.inject.Inject

@HiltViewModel
class HatchViewModel @Inject constructor(private val repo: HatchRepository) : ViewModel() {

    sealed class ListState {
        object Loading : ListState()
        data class Success(val data: HatchesResponse) : ListState()
        data class Error(val msg: String) : ListState()
    }

    sealed class DetailState {
        object Loading : DetailState()
        data class Success(val hatch: Hatch) : DetailState()
        data class Error(val msg: String) : DetailState()
    }

    sealed class SaveState {
        object Idle : SaveState()
        object Loading : SaveState()
        data class Success(val id: Int) : SaveState()
        data class Error(val msg: String) : SaveState()
    }

    private val _listState   = MutableStateFlow<ListState>(ListState.Loading)
    private val _detailState = MutableStateFlow<DetailState>(DetailState.Loading)
    private val _saveState   = MutableStateFlow<SaveState>(SaveState.Idle)

    val listState   = _listState.asStateFlow()
    val detailState = _detailState.asStateFlow()
    val saveState   = _saveState.asStateFlow()

    fun loadHatches() {
        viewModelScope.launch {
            _listState.value = ListState.Loading
            repo.getHatches().fold(
                onSuccess = { _listState.value = ListState.Success(it) },
                onFailure = { _listState.value = ListState.Error(it.message ?: "Error") },
            )
        }
    }

    fun loadHatch(id: Int) {
        viewModelScope.launch {
            _detailState.value = DetailState.Loading
            repo.getHatchDetail(id).fold(
                onSuccess = { _detailState.value = DetailState.Success(it) },
                onFailure = { _detailState.value = DetailState.Error(it.message ?: "Error") },
            )
        }
    }

    fun saveHatch(hatchId: Int?, req: HatchRequest) {
        viewModelScope.launch {
            _saveState.value = SaveState.Loading
            val result = if (hatchId == null)
                repo.createHatch(req)
            else
                repo.updateHatch(hatchId, req).map { hatchId }
            result.fold(
                onSuccess = { _saveState.value = SaveState.Success(it) },
                onFailure = { _saveState.value = SaveState.Error(it.message ?: "Error") },
            )
        }
    }

    fun resetSave() { _saveState.value = SaveState.Idle }
}
