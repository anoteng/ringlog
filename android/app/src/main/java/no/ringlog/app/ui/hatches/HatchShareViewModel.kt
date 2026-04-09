package no.ringlog.app.ui.hatches

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import no.ringlog.app.data.api.HatchShare
import no.ringlog.app.data.repository.HatchRepository
import javax.inject.Inject

@HiltViewModel
class HatchShareViewModel @Inject constructor(
    private val repo: HatchRepository,
) : ViewModel() {

    sealed class SharesState {
        object Loading : SharesState()
        data class Success(val shares: List<HatchShare>) : SharesState()
        data class Error(val msg: String) : SharesState()
    }

    private val _state = MutableStateFlow<SharesState>(SharesState.Loading)
    val state = _state.asStateFlow()

    private val _actionError = MutableStateFlow<String?>(null)
    val actionError = _actionError.asStateFlow()

    fun load(hatchId: Int) {
        viewModelScope.launch {
            _state.value = SharesState.Loading
            repo.getShares(hatchId).fold(
                onSuccess = { _state.value = SharesState.Success(it) },
                onFailure = { _state.value = SharesState.Error(it.message ?: "Error") },
            )
        }
    }

    fun addShare(hatchId: Int, username: String, canEdit: Boolean) {
        viewModelScope.launch {
            repo.addShare(hatchId, username, canEdit).fold(
                onSuccess = { load(hatchId) },
                onFailure = { _actionError.value = it.message },
            )
        }
    }

    fun revokeShare(hatchId: Int, shareId: Int) {
        viewModelScope.launch {
            repo.revokeShare(hatchId, shareId).fold(
                onSuccess = { load(hatchId) },
                onFailure = { _actionError.value = it.message },
            )
        }
    }

    fun clearError() { _actionError.value = null }
}
