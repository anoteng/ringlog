package no.ringlog.app.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenStore @Inject constructor(@ApplicationContext context: Context) {

    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    private val prefs = EncryptedSharedPreferences.create(
        "ringlog_secure_prefs",
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = if (value != null) prefs.edit().putString(KEY_TOKEN, value).apply()
                     else prefs.edit().remove(KEY_TOKEN).apply()

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) = if (value != null) prefs.edit().putString(KEY_USERNAME, value).apply()
                     else prefs.edit().remove(KEY_USERNAME).apply()

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_TOKEN    = "token"
        private const val KEY_USERNAME = "username"
    }
}
