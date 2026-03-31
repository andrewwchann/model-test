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
    @SerializedName("s3_evidence_uri") var s3EvidenceUri: String? = null, // Vehicle Photo S3
    @SerializedName("s3_plate_uri") var s3PlateUri: String? = null,       // Plate Photo S3
    @SerializedName("operator_id") val operatorId: String,
    @SerializedName("local_vehicle_path") var localVehiclePath: String? = null,
    @SerializedName("local_plate_path") var localPlatePath: String? = null,
)

data class BatchUploadResponse(
    @SerializedName("message") val message: String
)
