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
class LoginViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {

    sealed class State { object Idle : State(); object Loading : State()
        data class Error(val msg: String) : State(); object Success : State() }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state = _state.asStateFlow()

    fun login(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            _state.value = State.Error("Username and password are required")
            return
        }
        viewModelScope.launch {
            _state.value = State.Loading
            auth.login(username, password).fold(
                onSuccess = { _state.value = State.Success },
                onFailure = { _state.value = State.Error(it.message ?: "Login failed") },
            )
        }
    }
}
