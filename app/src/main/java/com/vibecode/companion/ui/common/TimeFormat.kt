package com.vibecode.companion.ui.common

import java.time.Duration
import java.time.Instant

/** "just now", "5m ago", "3h ago", "2d ago" from an ISO-8601 timestamp. */
fun relativeTime(isoTimestamp: String?, now: Instant = Instant.now()): String {
    if (isoTimestamp.isNullOrBlank()) return ""
    val instant = try {
        Instant.parse(isoTimestamp)
    } catch (_: Exception) {
        return isoTimestamp
    }
    val elapsed = Duration.between(instant, now)
    return when {
        elapsed.toMinutes() < 1 -> "just now"
        elapsed.toHours() < 1 -> "${elapsed.toMinutes()}m ago"
        elapsed.toDays() < 1 -> "${elapsed.toHours()}h ago"
        elapsed.toDays() < 30 -> "${elapsed.toDays()}d ago"
        else -> "${elapsed.toDays() / 30}mo ago"
    }
}

/** "12.4s", "3m 05s" from a duration in milliseconds. */
fun formatDuration(durationMs: Long?): String {
    if (durationMs == null) return ""
    val totalSeconds = durationMs / 1000
    return if (totalSeconds < 60) {
        "%.1fs".format(durationMs / 1000.0)
    } else {
        "%dm %02ds".format(totalSeconds / 60, totalSeconds % 60)
    }
}
