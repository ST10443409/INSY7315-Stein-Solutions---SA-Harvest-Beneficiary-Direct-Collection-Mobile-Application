package com.example.client.data

import java.util.Calendar
import java.util.TimeZone

private val ISO = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:\d{2})$""")

/**
 * Epoch milliseconds for an ISO-8601 timestamp with an offset, as the backend writes them
 * ("2026-10-01T19:19:23.8718392+00:00"), or null if it is not one. Hand-rolled because java.time needs API 26 and
 * this app supports 24.
 */
fun parseIsoInstantMillis(text: String?): Long? {
    val m = ISO.matchEntire(text?.trim().orEmpty()) ?: return null
    val g = m.groupValues
    val millis = if (g[7].isEmpty()) 0 else g[7].padEnd(3, '0').take(3).toInt()
    val offset = g[8]
    val offsetMillis = if (offset == "Z") 0L else {
        val sign = if (offset[0] == '-') -1 else 1
        sign * (offset.substring(1, 3).toLong() * 60 + offset.substring(4, 6).toLong()) * 60_000L
    }
    val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
        set(Calendar.MILLISECOND, millis)
    }
    return calendar.timeInMillis - offsetMillis
}
