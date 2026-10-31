package no.ringlog.app.data.repository

import no.ringlog.app.R
import no.ringlog.app.data.api.BatchBirdRequest
import no.ringlog.app.data.api.BatchBirdResponse
import no.ringlog.app.data.api.Bird
import no.ringlog.app.data.api.Flock
import no.ringlog.app.data.api.FlockPatchRequest
import no.ringlog.app.data.api.FlockReport
import no.ringlog.app.data.api.FlocksResponse
import no.ringlog.app.data.api.LogEntry
import no.ringlog.app.data.api.LogUpsertRequest
import no.ringlog.app.data.api.RingLogApi
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FlockRepository @Inject constructor(
    private val api: RingLogApi,
    private val errors: ApiErrors,
) {

    suspend fun createFlock(name: String, description: String? = null): Result<Flock> = errors.call {
        val body = buildMap<String, String> {
            put("name", name)
            if (!description.isNullOrBlank()) put("description", description)
        }
        val resp = api.createFlock(body)
        if (resp.isSuccessful) resp.body()!!
        else throw errors.of(resp, R.string.err_save, 409 to R.string.err_flock_name_taken)
    }

    suspend fun deleteFlock(id: Int): Result<Unit> = errors.call {
        val resp = api.deleteFlock(id)
        if (!resp.isSuccessful) throw errors.of(resp, R.string.err_delete, 409 to R.string.err_flock_not_empty)
    }

    suspend fun batchAddBirds(flockId: Int, req: BatchBirdRequest): Result<BatchBirdResponse> = errors.call {
        val resp = api.batchAddBirds(flockId, req)
        if (resp.isSuccessful) resp.body()!!
        else throw errors.of(resp, R.string.err_save, 400 to R.string.err_batch_count)
    }

    suspend fun patchFlock(id: Int, allowRingReuse: Boolean): Result<Flock> = errors.call {
        val resp = api.patchFlock(id, FlockPatchRequest(allow_ring_reuse = allowRingReuse))
        if (resp.isSuccessful) resp.body()!! else throw errors.of(resp, R.string.err_save)
    }

    suspend fun getFlocks(): Result<FlocksResponse> = errors.call {
        val resp = api.flocks()
        if (resp.isSuccessful) resp.body()!! else throw errors.of(resp, R.string.err_load)
    }

    suspend fun getFlockDetail(id: Int): Result<Flock> = errors.call {
        val resp = api.flockDetail(id)
        if (resp.isSuccessful) resp.body()!! else throw errors.of(resp, R.string.err_load)
    }

    suspend fun getBirdDetail(id: Int): Result<Bird> = errors.call {
        val resp = api.birdDetail(id)
        if (resp.isSuccessful) resp.body()!! else throw errors.of(resp, R.string.err_load)
    }

    suspend fun addBird(flockId: Int, fields: Map<String, String>, imageBytes: ByteArray? = null, imageMime: String? = null): Result<Bird> = errors.call {
        val parts = fields.mapValues { it.value.toRequestBody("text/plain".toMediaTypeOrNull()) }
        val imagePart = imageBytes?.let {
            MultipartBody.Part.createFormData("image", "photo.jpg",
                it.toRequestBody(imageMime?.toMediaTypeOrNull() ?: "image/jpeg".toMediaTypeOrNull()))
        }
        val resp = api.addBird(flockId, parts, imagePart)
        if (resp.isSuccessful) resp.body()!!
        else throw errors.of(resp, R.string.err_save, 409 to R.string.err_ring_in_use, 400 to R.string.err_ring_required)
    }

    suspend fun updateBird(birdId: Int, fields: Map<String, String>, imageBytes: ByteArray? = null, imageMime: String? = null): Result<Bird> = errors.call {
        val parts = fields.mapValues { it.value.toRequestBody("text/plain".toMediaTypeOrNull()) }
        val imagePart = imageBytes?.let {
            MultipartBody.Part.createFormData("image", "photo.jpg",
                it.toRequestBody(imageMime?.toMediaTypeOrNull() ?: "image/jpeg".toMediaTypeOrNull()))
        }
        val resp = api.updateBird(birdId, parts, imagePart)
        if (resp.isSuccessful) resp.body()!!
        else throw errors.of(resp, R.string.err_save, 409 to R.string.err_ring_in_use)
    }

    suspend fun uploadBirdImage(birdId: Int, bytes: ByteArray, mime: String): Result<Unit> = errors.call {
        val part = MultipartBody.Part.createFormData("image", "photo.jpg",
            bytes.toRequestBody(mime.toMediaTypeOrNull() ?: "image/jpeg".toMediaTypeOrNull()))
        val resp = api.uploadBirdImage(birdId, part)
        if (!resp.isSuccessful) throw errors.of(resp, R.string.err_upload,
            400 to R.string.err_image_type, 413 to R.string.err_image_too_large)
    }

    suspend fun deleteBird(birdId: Int): Result<Unit> = errors.call {
        val resp = api.deleteBird(birdId)
        if (!resp.isSuccessful) throw errors.of(resp, R.string.err_delete)
    }

    suspend fun addNote(birdId: Int, noteDate: String, content: String): Result<Unit> = errors.call {
        val resp = api.addNote(birdId, mapOf("note_date" to noteDate, "content" to content))
        if (!resp.isSuccessful) throw errors.of(resp, R.string.err_save)
    }

    suspend fun getLog(flockId: Int, from: String? = null, to: String? = null): Result<List<LogEntry>> = errors.call {
        val resp = api.flockLog(flockId, from, to)
        if (resp.isSuccessful) resp.body()!! else throw errors.of(resp, R.string.err_load)
    }

    suspend fun getReport(flockId: Int, days: Int = 30): Result<FlockReport> = errors.call {
        val resp = api.flockReport(flockId, days)
        if (resp.isSuccessful) resp.body()!! else throw errors.of(resp, R.string.err_load)
    }

    suspend fun upsertLog(flockId: Int, date: String, eggs: Int?, light: Float?, bedding: Boolean, notes: String?): Result<Unit> = errors.call {
        val resp = api.upsertLog(flockId, date, LogUpsertRequest(eggs, light, bedding, notes))
        if (!resp.isSuccessful) throw errors.of(resp, R.string.err_save)
    }
}
