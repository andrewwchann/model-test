package com.andre.alprprototype

import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

interface ViolationApi {
    @GET("get-upload-url")
    suspend fun getUploadUrl(@Query("filename") filename: String): GetUploadUrlResponse

    @PUT
    suspend fun uploadToS3(
        @Url uploadUrl: String,
        @Body image: RequestBody
    ): Response<Unit>

    @POST("violations")
    suspend fun uploadViolations(@Body violations: List<ViolationEvent>): BatchUploadResponse
}
