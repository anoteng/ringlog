package no.ringlog.app.data.repository

import kotlinx.coroutines.flow.StateFlow
import no.ringlog.app.R
import no.ringlog.app.data.api.LoginRequest
import no.ringlog.app.data.api.RegisterRequest
import no.ringlog.app.data.api.RingLogApi
import no.ringlog.app.data.local.TokenStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val api: RingLogApi,
    private val tokenStore: TokenStore,
    private val errors: ApiErrors,
) {
    val loggedIn: StateFlow<Boolean> = tokenStore.loggedIn

    val username get() = tokenStore.username

    suspend fun register(username: String, password: String, email: String?): Result<String> = errors.call {
        val resp = api.register(RegisterRequest(username.trim(), password, email?.trim()?.ifBlank { null }))
        if (!resp.isSuccessful) throw errors.of(resp, R.string.err_register_failed,
            409 to R.string.err_username_taken, 400 to R.string.err_register_invalid)
        val body = resp.body()!!
        tokenStore.username = body.username
        tokenStore.token    = body.token
        body.username
    }

    suspend fun login(username: String, password: String): Result<String> = errors.call {
        val resp = api.login(LoginRequest(username.trim(), password))
        if (!resp.isSuccessful) throw errors.of(resp, R.string.err_login_failed,
            401 to R.string.err_login_invalid)
        val body = resp.body()!!
        tokenStore.username = body.username
        tokenStore.token    = body.token
        body.username
    }

    fun logoutLocal() {
        tokenStore.clear()
    }

    /** Revokes the token on the server (best effort), then forgets it locally. */
    suspend fun logout() {
        runCatching { api.logout() }
        logoutLocal()
    }
}
