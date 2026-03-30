package no.ringlog.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import no.ringlog.app.data.repository.AuthRepository
import javax.inject.Inject

@HiltViewModel
class RegisterViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {

    sealed class State {
        object Idle : State()
        object Loading : State()
        data class Error(val msg: String) : State()
        object Success : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state = _state.asStateFlow()

    fun register(username: String, password: String, confirm: String, email: String?) {
        if (username.isBlank() || password.isBlank()) {
            _state.value = State.Error("Username and password are required")
            return
        }
        if (password != confirm) {
            _state.value = State.Error("Passwords do not match")
            return
        }
        viewModelScope.launch {
            _state.value = State.Loading
            auth.register(username, password, email?.ifBlank { null }).fold(
                onSuccess = { _state.value = State.Success },
                onFailure = { _state.value = State.Error(it.message ?: "Registration failed") },
            )
        }
    }
}
