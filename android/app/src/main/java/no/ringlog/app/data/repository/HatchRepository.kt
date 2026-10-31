package no.ringlog.app.data.repository

import no.ringlog.app.R
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
class HatchRepository @Inject constructor(
    private val api: RingLogApi,
    private val errors: ApiErrors,
) {

    suspend fun getHatches(): Result<HatchesResponse> = errors.call {
        val resp = api.hatches()
        if (resp.isSuccessful) resp.body()!! else throw errors.of(resp, R.string.err_load)
    }

    suspend fun getHatchDetail(id: Int): Result<Hatch> = errors.call {
        val resp = api.hatchDetail(id)
        if (resp.isSuccessful) resp.body()!! else throw errors.of(resp, R.string.err_load)
    }

    suspend fun createHatch(req: HatchRequest): Result<Int> = errors.call {
        val resp = api.createHatch(req)
        if (resp.isSuccessful) resp.body()!!.id
        else throw errors.of(resp, R.string.err_save, 400 to R.string.err_hatch_invalid)
    }

    suspend fun updateHatch(id: Int, req: HatchRequest): Result<Unit> = errors.call {
        val resp = api.updateHatch(id, req)
        if (!resp.isSuccessful) throw errors.of(resp, R.string.err_save, 400 to R.string.err_hatch_invalid)
    }

    suspend fun finishHatch(id: Int, req: HatchFinishRequest): Result<HatchFinishResponse> = errors.call {
        val resp = api.finishHatch(id, req)
        if (resp.isSuccessful) resp.body()!!
        else throw errors.of(resp, R.string.err_save, 409 to R.string.err_flock_name_taken)
    }

    suspend fun deleteHatch(id: Int): Result<Unit> = errors.call {
        val resp = api.deleteHatch(id)
        if (!resp.isSuccessful) throw errors.of(resp, R.string.err_delete)
    }

    suspend fun getShares(id: Int): Result<List<HatchShare>> = errors.call {
        val resp = api.hatchShares(id)
        if (resp.isSuccessful) resp.body()!! else throw errors.of(resp, R.string.err_load)
    }

    suspend fun addShare(id: Int, username: String, canEdit: Boolean): Result<HatchShare> = errors.call {
        val resp = api.addHatchShare(id, HatchShareRequest(username, canEdit))
        if (resp.isSuccessful) resp.body()!!
        else throw errors.of(resp, R.string.err_save,
            404 to R.string.err_user_not_found, 400 to R.string.err_share_self)
    }

    suspend fun revokeShare(hatchId: Int, shareId: Int): Result<Unit> = errors.call {
        val resp = api.revokeHatchShare(hatchId, shareId)
        if (!resp.isSuccessful) throw errors.of(resp, R.string.err_delete)
    }
}
