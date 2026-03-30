package no.ringlog.app.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import no.ringlog.app.data.api.LoginRequest
import no.ringlog.app.data.api.RingLogApi
import no.ringlog.app.data.local.TokenStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val api: RingLogApi,
    private val tokenStore: TokenStore,
) {
    private val _loggedIn = MutableStateFlow(tokenStore.token != null)
    val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()

    val username get() = tokenStore.username

    suspend fun register(username: String, password: String, email: String?): Result<String> = runCatching {
        val resp = api.register(RegisterRequest(username.trim(), password, email?.trim()?.ifBlank { null }))
        if (resp.isSuccessful) {
            val body = resp.body()!!
            tokenStore.token    = body.token
            tokenStore.username = body.username
            _loggedIn.value = true
            body.username
        } else {
            val msg = resp.code()
            throw Exception(when (msg) {
                409 -> "Username already taken"
                400 -> "Invalid username or password requirements"
                else -> "Registration failed"
            })
        }
    }

    suspend fun login(username: String, password: String): Result<String> = runCatching {
        val resp = api.login(LoginRequest(username.trim(), password))
        if (resp.isSuccessful) {
            val body = resp.body()!!
            tokenStore.token    = body.token
            tokenStore.username = body.username
            _loggedIn.value = true
            body.username
        } else {
            throw Exception(if (resp.code() == 401) "Invalid username or password" else "Login failed")
        }
    }

    fun logoutLocal() {
        tokenStore.clear()
        _loggedIn.value = false
    }

    suspend fun logout() {
        logoutLocal()
        runCatching { api.logout() }
    }
}
