package com.example.ble

data class TestConfig(
    val experimentId: String,
    val trialNum: Int,
    val role: TestRole,
    val messageCount: Int,
    val intervalMs: Long,
    val environment: String,
    val distanceLabel: String,
    val targetPeerId: String?,
    val loraEligible: Boolean = false,
    val notes: String = ""
)

enum class TestRole { SENDER, RECEIVER }
