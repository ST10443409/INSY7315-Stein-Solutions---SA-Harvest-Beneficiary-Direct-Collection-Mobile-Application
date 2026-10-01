package com.example.client.ui.admin

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.client.R
import com.example.client.data.local.entity.Tone
import com.example.client.data.repository.SyncForm
import com.example.client.data.repository.SyncState
import com.example.client.ui.components.ErrorTagPalette
import com.example.client.ui.components.TagPalette
import com.example.client.ui.components.palette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** "1 Oct 2026, 10:42" in the device's own time zone, or null when the server gave no time. */
fun formatWhen(millis: Long?, timeZone: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.getDefault()): String? =
    millis?.let { SimpleDateFormat("d MMM yyyy, HH:mm", locale).apply { this.timeZone = timeZone }.format(Date(it)) }

@StringRes
fun SyncState.labelRes(): Int = when (this) {
    SyncState.NEEDS_ATTENTION -> R.string.admin_failed_state_label_needs_attention
    SyncState.DUPLICATE_HELD -> R.string.admin_failed_state_label_duplicate_held
    SyncState.FORWARDED -> R.string.admin_failed_state_label_forwarded
    SyncState.RETRYING -> R.string.admin_failed_state_label_retrying
    SyncState.WAITING -> R.string.admin_failed_state_label_waiting
    SyncState.SUPERSEDED -> R.string.admin_failed_state_label_superseded
    SyncState.DISMISSED -> R.string.admin_failed_state_label_dismissed
    SyncState.UNKNOWN -> R.string.admin_failed_state_label_unknown
}

/** Red for the one state that asks something of the Admin, amber for a held duplicate, green once Foodspace has it, grey otherwise. */
fun SyncState.palette(): TagPalette = when (this) {
    SyncState.NEEDS_ATTENTION -> ErrorTagPalette
    SyncState.DUPLICATE_HELD, SyncState.RETRYING -> Tone.WARN.palette()
    SyncState.FORWARDED -> Tone.OK.palette()
    else -> Tone.NEW.palette()
}

@StringRes
fun SyncForm.labelRes(): Int = when (this) {
    SyncForm.CBO_COLLECTION -> R.string.admin_failed_form1
    SyncForm.VETTING_DECISION -> R.string.admin_failed_form2
}

@Composable
fun attemptsText(attempts: Int): String =
    if (attempts == 1) stringResource(R.string.admin_failed_attempts_one)
    else stringResource(R.string.admin_failed_attempts, attempts)
