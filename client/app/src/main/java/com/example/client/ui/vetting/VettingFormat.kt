package com.example.client.ui.vetting

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** How long ago something happened, in the coarsest unit that still reads naturally. */
sealed interface Age {
    object JustNow : Age
    data class Minutes(val n: Int) : Age
    data class Hours(val n: Int) : Age
    data class Days(val n: Int) : Age
}

fun ageOf(thenMillis: Long, nowMillis: Long): Age {
    val minutes = ((nowMillis - thenMillis).coerceAtLeast(0) / 60_000).toInt()
    return when {
        minutes < 1 -> Age.JustNow
        minutes < 60 -> Age.Minutes(minutes)
        minutes < 60 * 24 -> Age.Hours(minutes / 60)
        else -> Age.Days(minutes / (60 * 24))
    }
}

/** The Foodspace form's date format (yyyy/mm/dd), in South African time, which is where these organisations are. */
fun formatFieldDate(epochMillis: Long): String =
    SimpleDateFormat("yyyy/MM/dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("Africa/Johannesburg") }.format(Date(epochMillis))
