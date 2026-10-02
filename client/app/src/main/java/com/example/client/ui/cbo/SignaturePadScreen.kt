package com.example.client.ui.cbo

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.client.R
import com.example.client.data.local.entity.AttachmentKind
import com.example.client.ui.components.FilledPillButton
import com.example.client.ui.components.OutlinePillButton
import com.example.client.ui.components.ScreenHeader
import com.example.client.ui.theme.CBOCollectorTheme
import com.example.client.ui.theme.Figtree
import com.example.client.ui.theme.SaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

object SignatureTags {
    const val SCREEN = "screen_signature"
    const val PAD = "signature_pad"
    const val CLEAR = "signature_clear"
    const val ACCEPT = "signature_accept"
}

/** What has been drawn on the pad so far: one list of points per finger stroke, in pad pixels. */
class SignatureStrokes(initial: List<List<Offset>> = emptyList()) {
    val strokes = mutableStateListOf<List<Offset>>().apply { addAll(initial) }
    val current = mutableStateListOf<Offset>()

    val hasInk: Boolean get() = strokes.isNotEmpty() || current.isNotEmpty()

    fun begin(at: Offset) {
        current.clear()
        current.add(at)
    }

    fun extend(to: Offset) {
        current.add(to)
    }

    fun end() {
        if (current.isNotEmpty()) strokes.add(current.toList())
        current.clear()
    }

    fun clear() {
        strokes.clear()
        current.clear()
    }

    /** Every stroke including the one still being drawn. */
    fun all(): List<List<Offset>> = if (current.isEmpty()) strokes.toList() else strokes + listOf(current.toList())

    companion object {
        // Survives rotation: strokes are flattened to x,y pairs, one list per stroke.
        val Saver: Saver<SignatureStrokes, Any> = Saver(
            save = { s -> s.all().map { stroke -> stroke.flatMap { listOf(it.x, it.y) } } },
            restore = { saved ->
                @Suppress("UNCHECKED_CAST")
                SignatureStrokes((saved as List<List<Float>>).map { flat -> flat.chunked(2).map { Offset(it[0], it[1]) } })
            }
        )
    }
}

/** Draws the strokes on a white bitmap, [width] pixels wide at most, and encodes it as PNG. */
fun renderSignaturePng(strokes: List<List<Offset>>, padSize: IntSize, strokeWidthPx: Float, maxWidthPx: Int = 800): ByteArray {
    val scale = min(1f, maxWidthPx.toFloat() / padSize.width)
    val bitmap = Bitmap.createBitmap(
        (padSize.width * scale).toInt().coerceAtLeast(1),
        (padSize.height * scale).toInt().coerceAtLeast(1),
        Bitmap.Config.ARGB_8888
    )
    val canvas = AndroidCanvas(bitmap)
    canvas.drawColor(android.graphics.Color.WHITE)
    canvas.scale(scale, scale)
    val ink = SaColors.Ink
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(255, (ink.red * 255).toInt(), (ink.green * 255).toInt(), (ink.blue * 255).toInt())
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val dot = Paint(line).apply { style = Paint.Style.FILL }
    strokes.forEach { points ->
        if (points.size < 2) {
            points.firstOrNull()?.let { canvas.drawCircle(it.x, it.y, strokeWidthPx / 2, dot) }
            return@forEach
        }
        val path = AndroidPath().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        canvas.drawPath(path, line)
    }
    return ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }
}

/**
 * Full screen signature pad (the demo's Sign screen): sign with a finger, clear and start again, or accept, which hands
 * back the signature as a PNG for the caller to store.
 */
@Composable
fun SignaturePadScreen(
    kind: AttachmentKind,
    donorName: String,
    onAccept: (ByteArray) -> Unit,
    onBack: () -> Unit
) = CBOCollectorTheme {
    val ink = rememberSaveable(saver = SignatureStrokes.Saver) { SignatureStrokes() }
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    var encoding by remember { mutableStateOf(false) }
    val strokeWidthPx = with(LocalDensity.current) { 2.4.dp.toPx() }
    val scope = rememberCoroutineScope()
    val stamp = remember { SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SaColors.Surface)
            .padding(20.dp, 20.dp, 20.dp, 26.dp)
            .testTag(SignatureTags.SCREEN)
    ) {
        ScreenHeader(
            title = stringResource(if (kind == AttachmentKind.DONOR_SIGNATURE) R.string.sign_donor_title else R.string.sign_cbo_title),
            onBack = onBack,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Text(
            stringResource(R.string.sign_instructions),
            fontFamily = Figtree, fontSize = 13.sp, lineHeight = 20.sp, color = SaColors.Muted,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(SaColors.White)
                .border(1.dp, SaColors.inkAlpha(0.16f), RoundedCornerShape(14.dp))
        ) {
            val description = stringResource(R.string.sign_pad_description)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .onSizeChanged { padSize = it }
                    .semantics { contentDescription = description }
                    .testTag(SignatureTags.PAD)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { ink.begin(it) },
                            onDragEnd = { ink.end() },
                            onDragCancel = { ink.end() },
                            onDrag = { change, _ ->
                                change.consume()
                                ink.extend(change.position)
                            }
                        )
                    }
            ) {
                val stroke = Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
                ink.all().forEach { points ->
                    if (points.size < 2) return@forEach
                    drawPath(
                        path = Path().apply {
                            moveTo(points.first().x, points.first().y)
                            points.drop(1).forEach { lineTo(it.x, it.y) }
                        },
                        color = SaColors.Ink,
                        style = stroke
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(SaColors.Divider)
            )
            Text(
                stringResource(if (ink.hasInk) R.string.sign_pad_captured else R.string.sign_pad_hint),
                fontFamily = Figtree, fontSize = 11.5.sp, color = SaColors.Faint,
                modifier = Modifier.padding(16.dp, 10.dp)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinePillButton(
                onClick = { ink.clear() },
                modifier = Modifier.testTag(SignatureTags.CLEAR),
                contentPadding = PaddingValues(vertical = 14.dp, horizontal = 22.dp)
            ) {
                Text(stringResource(R.string.sign_clear), fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = SaColors.Ink)
            }
            FilledPillButton(
                onClick = {
                    if (encoding || !ink.hasInk) return@FilledPillButton
                    encoding = true
                    val drawn = ink.all()
                    scope.launch {
                        val png = withContext(Dispatchers.Default) { renderSignaturePng(drawn, padSize, strokeWidthPx) }
                        onAccept(png)
                    }
                },
                enabled = ink.hasInk && !encoding,
                modifier = Modifier
                    .weight(1f)
                    .testTag(SignatureTags.ACCEPT),
                contentPadding = PaddingValues(14.dp)
            ) {
                Text(
                    stringResource(R.string.sign_accept),
                    fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp,
                    color = if (ink.hasInk && !encoding) SaColors.Ink else SaColors.Muted
                )
            }
        }
        Box(Modifier.weight(1f))
        Text(
            text = if (kind == AttachmentKind.DONOR_SIGNATURE) {
                if (donorName.isBlank()) stringResource(R.string.sign_footer_donor_unnamed, stamp)
                else stringResource(R.string.sign_footer_donor, donorName.trim(), stamp)
            } else {
                stringResource(R.string.sign_footer_cbo, stamp)
            },
            fontFamily = Figtree, fontSize = 12.sp, color = SaColors.MutedLight, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
