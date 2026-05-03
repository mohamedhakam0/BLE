package com.example.ble

/**
 * Provides a safe debug-build check without direct BuildConfig references.
 * This avoids IDE/source-set resolution issues while preserving debug gating.
 */
object DebugBuildFlags {
    val isDebug: Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching {
            val cls = Class.forName("com.example.ble.BuildConfig")
            cls.getField("DEBUG").getBoolean(null)
        }.getOrDefault(false)
    }
}

