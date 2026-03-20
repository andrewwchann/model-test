package com.andre.alprprototype

import com.google.gson.annotations.SerializedName

data class RegistryPlate(
    @SerializedName("plate_string") val plateString: String,
    @SerializedName("permit_type") val permitType: String?,
    @SerializedName("expiry_date") val expiryDate: String?,
    @SerializedName("lot_zone") val lotZone: String?,
    @SerializedName("list_version") val listVersion: Int?
)
