package com.example.ble

object TestExperiments {

    data class Preset(
        val experimentId: String,
        val description: String,
        val messageCount: Int,
        val intervalMs: Long
    )

    val all: List<Preset> = listOf(
        Preset("E1_BASELINE",    "BLE single-hop baseline",              30,  5000L),
        Preset("E2_ASYMMETRY",   "S10+ vs Tab A7 direction comparison",  30,  5000L),
        Preset("E3_MULTIHOP",    "Multi-hop BLE chain",                  30,  5000L),
        Preset("E4_ESP32_RELAY", "ESP32 relay characterization",         30,  5000L),
        Preset("E5_LORA",        "LoRa inter-cluster gateway",           20,  5000L),
        Preset("E6_SECURITY",    "Replay and tamper verification",       20,  5000L),
        Preset("E7_STRESS",      "Burst load stress test",              100,     0L),
    )
}
