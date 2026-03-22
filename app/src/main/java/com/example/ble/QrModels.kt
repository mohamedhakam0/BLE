package com.example.ble

import org.json.JSONObject

/** QR payload schema (v1 legacy): { "version":1, "nickname":String, "senderId":hex, "publicKey":base64 }
 *  QR payload schema (v2+):      { "version":2, "displayName":String, "senderId":hex, "publicKey":base64, "nickname"?:String }
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
    fun resolvedName(): String = displayName.ifBlank { nickname }

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
