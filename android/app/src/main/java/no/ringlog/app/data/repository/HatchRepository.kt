package no.ringlog.app.data.repository

import no.ringlog.app.data.api.Hatch
import no.ringlog.app.data.api.HatchFinishRequest
import no.ringlog.app.data.api.HatchFinishResponse
import no.ringlog.app.data.api.HatchRequest
import no.ringlog.app.data.api.HatchShare
import no.ringlog.app.data.api.HatchShareRequest
import no.ringlog.app.data.api.HatchesResponse
import no.ringlog.app.data.api.RingLogApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HatchRepository @Inject constructor(private val api: RingLogApi) {

    suspend fun getHatches(): Result<HatchesResponse> = runCatching {
        val resp = api.hatches()
        if (resp.isSuccessful) resp.body()!! else throw Exception("Failed to load hatches")
    }

    suspend fun getHatchDetail(id: Int): Result<Hatch> = runCatching {
        val resp = api.hatchDetail(id)
        if (resp.isSuccessful) resp.body()!! else throw Exception("Failed to load hatch")
    }

    suspend fun createHatch(req: HatchRequest): Result<Int> = runCatching {
        val resp = api.createHatch(req)
        if (resp.isSuccessful) resp.body()!!.id
        else throw Exception("Failed to create hatch (${resp.code()})")
    }

    suspend fun updateHatch(id: Int, req: HatchRequest): Result<Unit> = runCatching {
        val resp = api.updateHatch(id, req)
        if (!resp.isSuccessful) throw Exception("Failed to update hatch (${resp.code()})")
    }

    suspend fun finishHatch(id: Int, req: HatchFinishRequest): Result<HatchFinishResponse> = runCatching {
        val resp = api.finishHatch(id, req)
        if (resp.isSuccessful) resp.body()!!
        else throw Exception(
            when (resp.code()) {
                409  -> resp.errorBody()?.string()?.let {
                    Regex("\"error\":\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1)
                } ?: "Ring number already in use"
                403  -> "No access to target flock"
                else -> "Failed to finish hatch (${resp.code()})"
            }
        )
    }

    suspend fun deleteHatch(id: Int): Result<Unit> = runCatching {
        val resp = api.deleteHatch(id)
        if (!resp.isSuccessful) throw Exception("Failed to delete hatch")
    }

    suspend fun getShares(id: Int): Result<List<HatchShare>> = runCatching {
        val resp = api.hatchShares(id)
        if (resp.isSuccessful) resp.body()!! else throw Exception("Failed to load shares")
    }

    suspend fun addShare(id: Int, username: String, canEdit: Boolean): Result<HatchShare> = runCatching {
        val resp = api.addHatchShare(id, HatchShareRequest(username, canEdit))
        if (resp.isSuccessful) resp.body()!!
        else throw Exception(
            resp.errorBody()?.string()?.let {
                Regex("\"error\":\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1)
            } ?: "Failed to share (${resp.code()})"
        )
    }

    suspend fun revokeShare(hatchId: Int, shareId: Int): Result<Unit> = runCatching {
        val resp = api.revokeHatchShare(hatchId, shareId)
        if (!resp.isSuccessful) throw Exception("Failed to revoke share")
    }
}
