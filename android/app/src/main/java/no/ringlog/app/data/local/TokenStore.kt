package no.ringlog.app.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    private fun open() = EncryptedSharedPreferences.create(
        PREFS_NAME,
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    // Prefs restored from another device can't be decrypted with this device's key; start fresh (user logs in again).
    private val prefs = try {
        open()
    } catch (e: Exception) {
        context.deleteSharedPreferences(PREFS_NAME)
        open()
    }

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = if (value != null) prefs.edit().putString(KEY_TOKEN, value).apply()
                     else prefs.edit().remove(KEY_TOKEN).apply()

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) = if (value != null) prefs.edit().putString(KEY_USERNAME, value).apply()
                     else prefs.edit().remove(KEY_USERNAME).apply()

    fun clear() {
        prefs.edit().clear().commit()
    }

    companion object {
        private const val PREFS_NAME   = "ringlog_secure_prefs"
        private const val KEY_TOKEN    = "token"
        private const val KEY_USERNAME = "username"
    }
}
