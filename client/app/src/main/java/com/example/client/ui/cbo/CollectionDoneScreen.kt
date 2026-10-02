package com.example.client.ui.cbo

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.example.client.R
import com.example.client.ui.components.ReceiptRow
import com.example.client.ui.components.ReceiptScreen
import com.example.client.ui.theme.CBOCollectorTheme
import com.example.client.ui.util.rememberIsOnline

object DoneTags {
    const val SCREEN = "screen_collection_done"
    const val TITLE = "form1_success"
    const val PRIMARY = "done_primary"
    const val SECONDARY = "done_secondary"
}

/** Which two ways onward the receipt offers: a collector goes to the sync queue or home; an Admin has neither tab. */
enum class DoneVariant { COLLECTOR, ADMIN }

/**
 * The receipt shown after a collection is saved (the demo's Done screen), built from the record that was just stored:
 * nothing here is invented, and the reference is the saved record's own id.
 */
@Composable
fun CollectionDoneScreen(
    receipt: CollectionReceipt,
    variant: DoneVariant,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit
) = CBOCollectorTheme {
    val online = rememberIsOnline()
    val rows = buildList {
        add(ReceiptRow(stringResource(R.string.done_receipt_donor), receipt.donorName))
        add(ReceiptRow(stringResource(R.string.done_receipt_weight), Form1Validator.formatKg(receipt.totalKg) + " kg"))
        add(
            ReceiptRow(
                stringResource(R.string.done_receipt_times),
                receipt.arrivalTime + " · " + (receipt.departureTime ?: stringResource(R.string.done_not_stamped))
            )
        )
        add(ReceiptRow(stringResource(R.string.done_receipt_signatures), stringResource(R.string.done_of_two, receipt.signatureCount)))
        add(ReceiptRow(stringResource(R.string.done_receipt_photos), stringResource(R.string.done_of_four, receipt.photoCount)))
        if (receipt.deliveryNote.isNotBlank()) add(ReceiptRow(stringResource(R.string.done_receipt_note), receipt.deliveryNote))
        add(ReceiptRow(stringResource(R.string.done_receipt_reference), receipt.reference))
    }
    ReceiptScreen(
        title = stringResource(R.string.done_title),
        body = stringResource(if (online) R.string.done_body_online else R.string.done_body_offline),
        rows = rows,
        primaryLabel = stringResource(if (variant == DoneVariant.COLLECTOR) R.string.done_view_queue else R.string.done_new_collection),
        onPrimary = onPrimary,
        secondaryLabel = stringResource(if (variant == DoneVariant.COLLECTOR) R.string.done_back_today else R.string.done_back_overview),
        onSecondary = onSecondary,
        modifier = Modifier.testTag(DoneTags.SCREEN),
        titleTag = DoneTags.TITLE,
        primaryTag = DoneTags.PRIMARY,
        secondaryTag = DoneTags.SECONDARY
    )
}
