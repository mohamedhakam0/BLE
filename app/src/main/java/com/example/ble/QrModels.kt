/**
 * QR payload model used for contact exchange during onboarding.
 *
 * The parser supports both legacy payloads (nickname + senderId + publicKey)
 * and the newer payload that includes `displayName`.
 */
package com.example.ble

import org.json.JSONObject

/**
 * Serializable QR identity payload shared between devices.
 *
 * Legacy schema (v1): `{ version, nickname, senderId, publicKey }`
 * Current schema (v2): `{ version, displayName, senderId, publicKey, nickname? }`
 */
data class QrIdentityPayload(
    val version: Int = 2,
    /** Legacy field name kept for backward compatibility. */
    val nickname: String = "",
    /** New preferred human display name. */
    val displayName: String = "",
    val senderId: String,
    val publicKey: String
) {
    /** Returns preferred display name while preserving support for legacy nickname-only payloads. */
    fun resolvedName(): String = displayName.ifBlank { nickname }

    /** Serializes payload to JSON string for QR generation. */
    fun toJson(): String = JSONObject().apply {
        put("version", version)
        // Always include displayName going forward.
        put("displayName", displayName)
        // Also include nickname for backward compatibility with older builds.
        put("nickname", if (nickname.isNotBlank()) nickname else displayName)
        put("senderId", senderId)
        put("publicKey", publicKey)
    }.toString()

    companion object {
        /**
         * Parses JSON into [QrIdentityPayload].
         *
         * @return parsed payload, or null when required keys are missing/invalid.
         */
        fun fromJson(json: String): QrIdentityPayload? = try {
            val obj = JSONObject(json)
            val version = obj.optInt("version", 1)
            val senderId = obj.getString("senderId")
            val publicKey = obj.getString("publicKey")

            // Old payloads only have nickname.
            val nickname = obj.optString("nickname", "")
            val displayName = obj.optString("displayName", "")

            QrIdentityPayload(
                version = version,
                nickname = nickname,
                displayName = displayName,
                senderId = senderId,
                publicKey = publicKey
            )
        } catch (_: Exception) {
            null
        }
    }
}
