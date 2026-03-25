package no.ringlog.app.data.repository

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
    val isLoggedIn get() = tokenStore.token != null
    val username   get() = tokenStore.username

    suspend fun login(username: String, password: String): Result<String> = runCatching {
        val resp = api.login(LoginRequest(username.trim(), password))
        if (resp.isSuccessful) {
            val body = resp.body()!!
            tokenStore.token    = body.token
            tokenStore.username = body.username
            body.username
        } else {
            throw Exception(if (resp.code() == 401) "Invalid username or password" else "Login failed")
        }
    }

    suspend fun logout() {
        runCatching { api.logout() }
        tokenStore.clear()
    }
}
