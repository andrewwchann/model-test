package com.andre.alprprototype

import com.google.gson.annotations.SerializedName

data class GetUploadUrlResponse(
    @SerializedName("uploadUrl") val uploadUrl: String,
    @SerializedName("filename") val filename: String,
    @SerializedName("final_s3_uri") val finalS3Uri: String
)

data class ViolationEvent(
    @SerializedName("raw_ocr_text") val rawOcrText: String,
    @SerializedName("confidence_score") val confidenceScore: Float,
    @SerializedName("timestamp") val timestamp: String, // ISO 8601 format
    @SerializedName("s3_evidence_uri") var s3EvidenceUri: String? = null,
    @SerializedName("operator_id") val operatorId: String,
    @Transient val localImagePath: String? = null // Used locally before upload
)

data class BatchUploadResponse(
    @SerializedName("message") val message: String
)
