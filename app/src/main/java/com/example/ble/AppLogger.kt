package com.example.ble

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Simple in-memory logger for in-app diagnostics.
 *
 * - Thread-safe (synchronized)
 * - Bounded (keeps last [MAX_ENTRIES])
 * - Also forwards to android.util.Log so Logcat behavior stays the same
 */
object AppLogger {

    private const val MAX_ENTRIES = 500

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Entry(
        val timestamp: String,
        val level: Level,
        val tag: String,
        val message: String
    )

    private val lock = Any()
    private val buffer: ArrayDeque<Entry> = ArrayDeque(MAX_ENTRIES)

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun d(tag: String, message: String) {
        add(Level.DEBUG, tag, message)
        Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        add(Level.INFO, tag, message)
        Log.i(tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        add(Level.WARN, tag, message + (throwable?.let { "\n${Log.getStackTraceString(it)}" } ?: ""))
        if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        add(Level.ERROR, tag, message + (throwable?.let { "\n${Log.getStackTraceString(it)}" } ?: ""))
        if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
    }

    /** Compatibility with the user-request naming. */
    fun log(tag: String, message: String) = d(tag, message)

    /** Compatibility with the user-request naming. */
    fun logError(tag: String, message: String, throwable: Throwable? = null) = e(tag, message, throwable)

    fun clear() {
        synchronized(lock) {
            buffer.clear()
        }
    }

    fun snapshot(): List<Entry> {
        synchronized(lock) {
            return buffer.toList()
        }
    }

    fun dumpText(query: String? = null): String {
        val q = query?.trim().orEmpty()
        val entries = snapshot()
            .asSequence()
            .filter {
                if (q.isBlank()) true
                else it.tag.contains(q, ignoreCase = true) || it.message.contains(q, ignoreCase = true)
            }
            .toList()

        return buildString {
            for (e in entries) {
                append(e.timestamp)
                append(' ')
                append(e.level.name)
                append('/')
                append(e.tag)
                append(": ")
                append(e.message)
                append('\n')
            }
        }
    }

    private fun add(level: Level, tag: String, message: String) {
        val entry = Entry(
            timestamp = timeFormat.format(Date()),
            level = level,
            tag = tag,
            message = message
        )

        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) {
                buffer.removeFirst()
            }
            buffer.addLast(entry)
        }
    }
}

