package no.ringlog.app.ui.hatches

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import no.ringlog.app.data.api.Hatch
import no.ringlog.app.data.api.HatchesResponse
import no.ringlog.app.data.repository.HatchRepository
import javax.inject.Inject

@HiltViewModel
class HatchViewModel @Inject constructor(private val repo: HatchRepository) : ViewModel() {

    sealed class ListState { object Loading : ListState()
        data class Success(val data: HatchesResponse) : ListState()
        data class Error(val msg: String) : ListState() }

    sealed class DetailState { object Loading : DetailState()
        data class Success(val hatch: Hatch) : DetailState()
        data class Error(val msg: String) : DetailState() }

    private val _listState   = MutableStateFlow<ListState>(ListState.Loading)
    private val _detailState = MutableStateFlow<DetailState>(DetailState.Loading)

    val listState   = _listState.asStateFlow()
    val detailState = _detailState.asStateFlow()

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
}
