package com.example.client.ui.util

import java.util.Calendar
import java.util.Locale

/** The signed-in user's username shown as a name ("collector.one" becomes "Collector.one"), or null if there is none. */
fun displayName(username: String?): String? =
    username?.trim()?.takeIf { it.isNotEmpty() }?.replaceFirstChar { it.uppercase(Locale.getDefault()) }

/** Up to two letters for the avatar circle: the first letters of the username's words, else its first two letters. */
fun initialsOf(username: String?): String {
    val name = username?.trim().orEmpty()
    if (name.isEmpty()) return "?"
    val words = name.split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.isNotEmpty() }
    val letters = if (words.size >= 2) "${words[0].first()}${words[1].first()}" else name.filter { it.isLetterOrDigit() }.take(2)
    return letters.ifEmpty { "?" }.uppercase(Locale.getDefault())
}

/** Whether [epochMillis] falls on the same calendar day as [now] in the device's time zone. */
fun isSameDay(epochMillis: Long, now: Long = System.currentTimeMillis()): Boolean {
    val a = Calendar.getInstance().apply { timeInMillis = epochMillis }
    val b = Calendar.getInstance().apply { timeInMillis = now }
    return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}
