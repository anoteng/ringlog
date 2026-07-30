package no.ringlog.app.data.api

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

interface RingLogApi {

    // Auth
    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): Response<LoginResponse>

    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): Response<LoginResponse>

    @POST("auth/logout")
    suspend fun logout(): Response<OkResponse>

    @GET("auth/me")
    suspend fun me(): Response<MeResponse>

    // Flocks
    @GET("flocks")
    suspend fun flocks(): Response<FlocksResponse>

    @GET("flocks/{id}")
    suspend fun flockDetail(@Path("id") id: Int): Response<Flock>

    @POST("flocks")
    suspend fun createFlock(@Body body: Map<String, String>): Response<Flock>

    @DELETE("flocks/{id}")
    suspend fun deleteFlock(@Path("id") id: Int): Response<OkResponse>

    @PATCH("flocks/{id}")
    suspend fun patchFlock(@Path("id") id: Int, @Body body: FlockPatchRequest): Response<Flock>

    @POST("flocks/{id}/birds/batch")
    suspend fun batchAddBirds(@Path("id") flockId: Int, @Body body: BatchBirdRequest): Response<BatchBirdResponse>

    // Birds
    @Multipart
    @POST("flocks/{flockId}/birds")
    suspend fun addBird(
        @Path("flockId") flockId: Int,
        @PartMap parts: Map<String, @JvmSuppressWildcards RequestBody>,
        @Part image: MultipartBody.Part? = null,
    ): Response<Bird>

    @GET("birds/{id}")
    suspend fun birdDetail(@Path("id") id: Int): Response<Bird>

    @Multipart
    @PUT("birds/{id}")
    suspend fun updateBird(
        @Path("id") id: Int,
        @PartMap parts: Map<String, @JvmSuppressWildcards RequestBody>,
        @Part image: MultipartBody.Part? = null,
    ): Response<Bird>

    @DELETE("birds/{id}")
    suspend fun deleteBird(@Path("id") id: Int): Response<OkResponse>

    @GET("birds/{id}/image")
    suspend fun birdImage(@Path("id") id: Int): Response<okhttp3.ResponseBody>

    @Multipart
    @PUT("birds/{id}/image")
    suspend fun uploadBirdImage(
        @Path("id") id: Int,
        @Part image: MultipartBody.Part,
    ): Response<OkResponse>

    // Notes
    @POST("birds/{id}/notes")
    suspend fun addNote(@Path("id") birdId: Int, @Body body: Map<String, String>): Response<BirdNote>

    @DELETE("birds/{birdId}/notes/{noteId}")
    suspend fun deleteNote(@Path("birdId") birdId: Int, @Path("noteId") noteId: Int): Response<OkResponse>

    // Log
    @GET("flocks/{id}/log")
    suspend fun flockLog(
        @Path("id") flockId: Int,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
    ): Response<List<LogEntry>>

    @GET("flocks/{id}/report")
    suspend fun flockReport(
        @Path("id") flockId: Int,
        @Query("days") days: Int = 30,
    ): Response<FlockReport>

    @PUT("flocks/{id}/log/{date}")
    suspend fun upsertLog(
        @Path("id") flockId: Int,
        @Path("date") date: String,
        @Body body: LogUpsertRequest,
    ): Response<OkResponse>

    // Hatches
    @GET("hatches")
    suspend fun hatches(): Response<HatchesResponse>

    @GET("hatches/{id}")
    suspend fun hatchDetail(@Path("id") id: Int): Response<Hatch>

    @POST("hatches")
    suspend fun createHatch(@Body body: HatchRequest): Response<HatchCreateResponse>

    @PUT("hatches/{id}")
    suspend fun updateHatch(@Path("id") id: Int, @Body body: HatchRequest): Response<OkResponse>

    @POST("hatches/{id}/finish")
    suspend fun finishHatch(@Path("id") id: Int, @Body body: HatchFinishRequest): Response<HatchFinishResponse>

    @DELETE("hatches/{id}")
    suspend fun deleteHatch(@Path("id") id: Int): Response<OkResponse>

    @GET("hatches/{id}/shares")
    suspend fun hatchShares(@Path("id") id: Int): Response<List<HatchShare>>

    @POST("hatches/{id}/shares")
    suspend fun addHatchShare(@Path("id") id: Int, @Body body: HatchShareRequest): Response<HatchShare>

    @DELETE("hatches/{id}/shares/{shareId}")
    suspend fun revokeHatchShare(@Path("id") id: Int, @Path("shareId") shareId: Int): Response<OkResponse>
}
