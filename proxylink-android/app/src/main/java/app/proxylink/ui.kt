package app.proxylink

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

val colidle = Color(0xFF6B7280)
val colworking = Color(0xFFF59E0B)
val colok = Color(0xFF22C55E)
val colerr = Color(0xFFEF4444)

fun Modifier.pressscale(source: InteractionSource): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(
        if (pressed) 0.93f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    graphicsLayer { scaleX = s; scaleY = s }
}

@Composable
fun abutton(text: String, onclick: () -> Unit, modifier: Modifier = Modifier, kind: Int = 0) {
    val src = remember { MutableInteractionSource() }
    val m = modifier.pressscale(src)
    when (kind) {
        1 -> FilledTonalButton(onClick = onclick, modifier = m, interactionSource = src) { Text(text) }
        2 -> OutlinedButton(onClick = onclick, modifier = m, interactionSource = src) { Text(text) }
        else -> Button(onClick = onclick, modifier = m, interactionSource = src) { Text(text) }
    }
}

@Composable
fun statusorb(color: Color, pulsing: Boolean, modifier: Modifier = Modifier) {
    val c by animateColorAsState(color, tween(400), label = "orbcolor")
    val t = rememberInfiniteTransition(label = "orb")
    val pulse by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse",
    )
    val breathe by t.animateFloat(
        0.94f, 1.06f,
        infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    Canvas(modifier.size(120.dp)) {
        val r = size.minDimension / 4f
        val rim = size.minDimension / 2f
        if (pulsing) {
            drawCircle(c.copy(alpha = (1f - pulse) * 0.45f), radius = r + (rim - r) * pulse, center = center)
        }
        drawCircle(c.copy(alpha = 0.18f), radius = r * 1.5f * (if (pulsing) breathe else 1f), center = center)
        drawCircle(c, radius = r, center = center)
    }
}

@Composable
fun equalizer(color: Color, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "eq")
    val bars = (0 until 5).map { i ->
        t.animateFloat(
            0.15f, 1f,
            infiniteRepeatable(tween(380 + i * 120, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "bar$i",
        )
    }
    Row(
        modifier.height(40.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        bars.forEach { v ->
            Box(
                Modifier
                    .width(6.dp)
                    .fillMaxHeight(v.value)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
            )
        }
    }
}

@Composable
fun scanoverlay(modifier: Modifier = Modifier) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(500, easing = FastOutSlowInEasing)) }
    val t = rememberInfiniteTransition(label = "scan")
    val y by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "y",
    )
    Canvas(modifier) {
        val a = appear.value
        val side = size.minDimension * 0.65f * (0.8f + 0.2f * a)
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        val right = left + side
        val bottom = top + side
        val arm = side * 0.16f
        val sw = 5.dp.toPx()
        val col = Color.White.copy(alpha = a)
        fun seg(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(col, Offset(x1, y1), Offset(x2, y2), sw, StrokeCap.Round)
        seg(left, top, left + arm, top); seg(left, top, left, top + arm)
        seg(right, top, right - arm, top); seg(right, top, right, top + arm)
        seg(left, bottom, left + arm, bottom); seg(left, bottom, left, bottom - arm)
        seg(right, bottom, right - arm, bottom); seg(right, bottom, right, bottom - arm)
        val ly = top + side * y
        drawLine(colok.copy(alpha = 0.9f * a), Offset(left + arm, ly), Offset(right - arm, ly), 3.dp.toPx(), StrokeCap.Round)
    }
}

@Composable
fun checkmark(color: Color, modifier: Modifier = Modifier) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(450, easing = FastOutSlowInEasing)) }
    Canvas(modifier.size(56.dp)) {
        val a = Offset(size.width * 0.2f, size.height * 0.52f)
        val b = Offset(size.width * 0.42f, size.height * 0.74f)
        val c = Offset(size.width * 0.8f, size.height * 0.3f)
        val w = 6.dp.toPx()
        val v = p.value
        drawLine(color, a, lerp(a, b, (v / 0.4f).coerceIn(0f, 1f)), w, StrokeCap.Round)
        if (v > 0.4f) drawLine(color, b, lerp(b, c, ((v - 0.4f) / 0.6f).coerceIn(0f, 1f)), w, StrokeCap.Round)
    }
}
