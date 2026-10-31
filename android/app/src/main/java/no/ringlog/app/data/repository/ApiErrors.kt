package no.ringlog.app.data.repository

import android.content.Context
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import no.ringlog.app.R
import retrofit2.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Turns failed API calls into localized, user-facing messages. */
@Singleton
class ApiErrors @Inject constructor(@ApplicationContext private val context: Context) {

    /**
     * Exception for a failed [resp]. [overrides] maps specific status codes to messages;
     * otherwise common codes get a shared message and the rest use [fallback] (formatted with the code).
     */
    fun of(resp: Response<*>, @StringRes fallback: Int, vararg overrides: Pair<Int, Int>): Exception {
        val code = resp.code()
        val res = overrides.firstOrNull { it.first == code }?.second
        val msg = when {
            res != null       -> context.getString(res)
            code == 403       -> context.getString(R.string.err_permission)
            code == 404       -> context.getString(R.string.err_not_found)
            code == 429       -> context.getString(R.string.err_too_many_attempts)
            code in 500..599  -> context.getString(R.string.err_server, code)
            else              -> context.getString(fallback, code)
        }
        return Exception(msg)
    }

    /** Like runCatching, but network failures get a localized message. */
    suspend fun <T> call(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Result.failure(Exception(context.getString(R.string.err_network), e))
    } catch (e: Exception) {
        Result.failure(e)
    }
}
