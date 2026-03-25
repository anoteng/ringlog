package no.ringlog.app.data.repository

import no.ringlog.app.data.api.Hatch
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

    suspend fun deleteHatch(id: Int): Result<Unit> = runCatching {
        val resp = api.deleteHatch(id)
        if (!resp.isSuccessful) throw Exception("Failed to delete hatch")
    }
}
