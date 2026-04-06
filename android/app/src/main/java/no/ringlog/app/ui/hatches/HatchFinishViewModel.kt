package no.ringlog.app.ui.hatches

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import no.ringlog.app.data.api.Flock
import no.ringlog.app.data.api.HatchFinishRequest
import no.ringlog.app.data.repository.FlockRepository
import no.ringlog.app.data.repository.HatchRepository
import javax.inject.Inject

@HiltViewModel
class HatchFinishViewModel @Inject constructor(
    private val hatchRepo: HatchRepository,
    private val flockRepo: FlockRepository,
) : ViewModel() {

    sealed class FlocksState {
        object Loading : FlocksState()
        data class Success(val flocks: List<Flock>) : FlocksState()
        data class Error(val msg: String) : FlocksState()
    }

    sealed class FinishState {
        object Idle : FinishState()
        object Loading : FinishState()
        data class Success(val flockId: Int?) : FinishState()
        data class Error(val msg: String) : FinishState()
    }

    private val _flocksState = MutableStateFlow<FlocksState>(FlocksState.Loading)
    private val _finishState = MutableStateFlow<FinishState>(FinishState.Idle)
    val flocksState = _flocksState.asStateFlow()
    val finishState = _finishState.asStateFlow()

    fun loadFlocks() {
        viewModelScope.launch {
            flockRepo.getFlocks().fold(
                onSuccess = {
                    val flocks = it.owned + it.shared.filter { f -> f.can_edit }
                    _flocksState.value = FlocksState.Success(flocks)
                },
                onFailure = { _flocksState.value = FlocksState.Error(it.message ?: "Error") },
            )
        }
    }

    fun finish(hatchId: Int, req: HatchFinishRequest) {
        viewModelScope.launch {
            _finishState.value = FinishState.Loading
            hatchRepo.finishHatch(hatchId, req).fold(
                onSuccess = { _finishState.value = FinishState.Success(it.flock_id) },
                onFailure = { _finishState.value = FinishState.Error(it.message ?: "Error") },
            )
        }
    }

    fun resetFinish() { _finishState.value = FinishState.Idle }
}
