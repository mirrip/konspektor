package app.konspektor.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.konspektor.AppState
import app.konspektor.R
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The big full-width on/off switch.
 * Off: violet→blue gradient with the power symbol and "Включить".
 * On: red→orange gradient, breathing glow, timer and a live equalizer, "Выключить".
 */
@Composable
fun PowerButton(
    recording: Boolean,
    startedAtMs: Long,
    subjectLabel: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(dampingRatio = 0.55f, stiffness = 500f), label = "scale")
    val c1 by animateColorAsState(if (recording) Palette.recStart else Palette.idleStart, tween(600), label = "c1")
    val c2 by animateColorAsState(if (recording) Palette.recEnd else Palette.idleEnd, tween(600), label = "c2")
    val glow by animateFloatAsState(if (recording) 1f else 0f, tween(600), label = "glow")
    val breathe by rememberInfiniteTransition(label = "breathe").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b",
    )
    val shape = RoundedCornerShape(34.dp)

    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .drawBehind {
                if (glow > 0f) {
                    for (i in 1..3) {
                        val grow = (i * 3f + breathe * 3f) * density
                        drawRoundRect(
                            color = c1.copy(alpha = glow * (0.30f - i * 0.08f) * (0.55f + 0.45f * breathe)),
                            topLeft = Offset(-grow, -grow),
                            size = Size(size.width + grow * 2, size.height + grow * 2),
                            cornerRadius = CornerRadius(34.dp.toPx() + grow),
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                }
            }
            .clip(shape)
            .background(Brush.linearGradient(listOf(c1, c2)))
            .clickable(interactionSource = interaction, indication = ripple(color = Color.White), onClick = onClick)
            .semantics { contentDescription = if (recording) "Выключить запись" else "Включить запись" },
    ) {
        // soft top highlight
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.White.copy(alpha = 0.16f), 0.55f to Color.Transparent)
            )
        )
        AnimatedContent(
            targetState = recording,
            transitionSpec = { (fadeIn(tween(350)) + scaleIn(initialScale = 0.94f)) togetherWith fadeOut(tween(200)) },
            label = "content",
        ) { rec ->
            if (rec) RecordingContent(startedAtMs, subjectLabel, breathe) else IdleContent()
        }
    }
}

@Composable
private fun IdleContent() {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(104.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ms_power_settings_new), null, tint = Color.White, modifier = Modifier.size(54.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text("Включить", color = Color.White, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun RecordingContent(startedAtMs: Long, subjectLabel: String, breathe: Float) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(250) }
    }
    // equalizer history, advanced every few frames from the mic level
    val history = remember { FloatArray(25) }
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        var shown = 0f
        var n = 0
        while (true) {
            withFrameNanos { }
            shown += (AppState.level - shown) * 0.3f
            if (n++ % 3 == 0) {
                System.arraycopy(history, 1, history, 0, history.size - 1)
                history[history.size - 1] = shown
            }
            frame++
        }
    }

    Box(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.TopStart)) {
            Box(
                Modifier.size(8.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.35f + 0.65f * breathe))
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (subjectLabel.isEmpty()) "ЗАПИСЬ" else "ЗАПИСЬ · ${subjectLabel.uppercase()}",
                color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelMedium,
            )
        }

        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                Fmt.timer((now - startedAtMs).coerceAtLeast(0) / 1000), color = Color.White,
                fontSize = 46.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp,
                style = MaterialTheme.typography.displaySmall,
            )
            Spacer(Modifier.height(10.dp))
            Canvas(Modifier.fillMaxWidth(0.72f).height(40.dp)) {
                frame // redraw every frame
                val n = history.size
                val gap = 4.dp.toPx()
                val bw = min(5.dp.toPx(), (size.width - gap * (n - 1)) / n)
                val total = n * bw + (n - 1) * gap
                val startX = (size.width - total) / 2
                for (i in 0 until n) {
                    val v = max(0.08f, min(1f, history[i] * (0.75f + 0.25f * sin(frame * 0.12f + i))))
                    val h = size.height * v
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.45f + 0.55f * i / n),
                        topLeft = Offset(startX + i * (bw + gap), (size.height - h) / 2),
                        size = Size(bw, h),
                        cornerRadius = CornerRadius(bw / 2),
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.BottomCenter)) {
            Icon(painterResource(R.drawable.ms_power_settings_new), null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Выключить", color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
    }
}
