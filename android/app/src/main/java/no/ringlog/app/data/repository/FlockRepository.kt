package no.ringlog.app.data.repository

import no.ringlog.app.data.api.Bird
import no.ringlog.app.data.api.Flock
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
class FlockRepository @Inject constructor(private val api: RingLogApi) {

    suspend fun getFlocks(): Result<FlocksResponse> = runCatching {
        val resp = api.flocks()
        if (resp.isSuccessful) resp.body()!! else throw Exception("Failed to load flocks")
    }

    suspend fun getFlockDetail(id: Int): Result<Flock> = runCatching {
        val resp = api.flockDetail(id)
        if (resp.isSuccessful) resp.body()!! else throw Exception("Failed to load flock")
    }

    suspend fun getBirdDetail(id: Int): Result<Bird> = runCatching {
        val resp = api.birdDetail(id)
        if (resp.isSuccessful) resp.body()!! else throw Exception("Failed to load bird")
    }

    suspend fun addBird(flockId: Int, fields: Map<String, String>, imageBytes: ByteArray? = null, imageMime: String? = null): Result<Bird> = runCatching {
        val parts = fields.mapValues { it.value.toRequestBody("text/plain".toMediaTypeOrNull()) }
        val imagePart = imageBytes?.let {
            MultipartBody.Part.createFormData("image", "photo.jpg",
                it.toRequestBody(imageMime?.toMediaTypeOrNull() ?: "image/jpeg".toMediaTypeOrNull()))
        }
        val resp = api.addBird(flockId, parts, imagePart)
        if (resp.isSuccessful) resp.body()!! else throw Exception("Failed to add bird")
    }

    suspend fun updateBird(birdId: Int, fields: Map<String, String>, imageBytes: ByteArray? = null, imageMime: String? = null): Result<Bird> = runCatching {
        val parts = fields.mapValues { it.value.toRequestBody("text/plain".toMediaTypeOrNull()) }
        val imagePart = imageBytes?.let {
            MultipartBody.Part.createFormData("image", "photo.jpg",
                it.toRequestBody(imageMime?.toMediaTypeOrNull() ?: "image/jpeg".toMediaTypeOrNull()))
        }
        val resp = api.updateBird(birdId, parts, imagePart)
        if (resp.isSuccessful) resp.body()!! else throw Exception("Failed to update bird (${resp.code()}): ${resp.errorBody()?.string()}")
    }

    suspend fun uploadBirdImage(birdId: Int, bytes: ByteArray, mime: String): Result<Unit> = runCatching {
        val part = MultipartBody.Part.createFormData("image", "photo.jpg",
            bytes.toRequestBody(mime.toMediaTypeOrNull() ?: "image/jpeg".toMediaTypeOrNull()))
        val resp = api.uploadBirdImage(birdId, part)
        if (!resp.isSuccessful) throw Exception("Upload failed (${resp.code()})")
    }

    suspend fun deleteBird(birdId: Int): Result<Unit> = runCatching {
        val resp = api.deleteBird(birdId)
        if (!resp.isSuccessful) throw Exception("Failed to delete bird")
    }

    suspend fun addNote(birdId: Int, noteDate: String, content: String): Result<Unit> = runCatching {
        val resp = api.addNote(birdId, mapOf("note_date" to noteDate, "content" to content))
        if (!resp.isSuccessful) throw Exception("Failed to add note")
    }

    suspend fun getLog(flockId: Int, from: String? = null, to: String? = null): Result<List<LogEntry>> = runCatching {
        val resp = api.flockLog(flockId, from, to)
        if (resp.isSuccessful) resp.body()!! else throw Exception("Failed to load log")
    }

    suspend fun getReport(flockId: Int, days: Int = 30): Result<no.ringlog.app.data.api.FlockReport> = runCatching {
        val resp = api.flockReport(flockId, days)
        if (resp.isSuccessful) resp.body()!! else throw Exception("Failed to load report")
    }

    suspend fun upsertLog(flockId: Int, date: String, eggs: Int?, light: Float?, bedding: Boolean, notes: String?): Result<Unit> = runCatching {
        val resp = api.upsertLog(flockId, date, LogUpsertRequest(eggs, light, bedding, notes))
        if (!resp.isSuccessful) throw Exception("Failed to save log")
    }
}
