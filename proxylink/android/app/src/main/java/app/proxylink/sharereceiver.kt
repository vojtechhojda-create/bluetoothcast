package app.proxylink

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch

class sharereceiver : ComponentActivity() {
    private val phase = mutableIntStateOf(0)
    private val progress = mutableFloatStateOf(-1f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val needconnect = link.state.value !is linkstate.paired
        if (needconnect && store.load(this) == null) {
            Toast.makeText(this, "not paired with a pc", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (needconnect) phase.intValue = 3
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        }
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()

        setContent {
            proxytheme { sendcard(phase.intValue, progress.floatValue) }
        }

        lifecycleScope.launch {
            if (needconnect) {
                runCatching { ContextCompat.startForegroundService(this@sharereceiver, Intent(this@sharereceiver, proxyservice::class.java)) }
                link.connectsaved(applicationContext)
                withTimeoutOrNull(10_000) { link.state.first { it is linkstate.paired || it is linkstate.failed } }
                if (link.state.value !is linkstate.paired) {
                    phase.intValue = 2
                    delay(1500)
                    finish()
                    return@launch
                }
                phase.intValue = 0
            }
            var ok = true
            if (!text.isNullOrBlank()) ok = link.sharetext(text)
            val total = uris.size
            uris.forEachIndexed { i, u ->
                val sent = link.sharefile(applicationContext, u) { f ->
                    progress.floatValue = if (f < 0f) -1f else (i + f) / total
                }
                ok = sent && ok
            }
            phase.intValue = if (ok) 1 else 2
            delay(if (ok) 900 else 1500)
            finish()
        }
    }
}

@Composable
private fun sendcard(phase: Int, progress: Float) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val scrim by animateFloatAsState(if (shown) 0.45f else 0f, tween(250), label = "scrim")
    val prog by animateFloatAsState(progress.coerceAtLeast(0f), tween(180), label = "prog")

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim)),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(220)) + scaleIn(
                initialScale = 0.85f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
            ),
        ) {
            Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp) {
                AnimatedContent(
                    targetState = phase,
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                    label = "phase",
                ) { p ->
                    Column(
                        Modifier.padding(28.dp).width(220.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        when (p) {
                            0 -> {
                                Text("sending to pc")
                                if (progress < 0f) LinearProgressIndicator(Modifier.width(220.dp))
                                else LinearProgressIndicator(progress = { prog }, modifier = Modifier.width(220.dp))
                            }
                            1 -> {
                                checkmark(colok)
                                Text("sent")
                            }
                            3 -> {
                                Text("connecting to pc")
                                LinearProgressIndicator(Modifier.width(220.dp))
                            }
                            else -> Text("send failed", color = colerr)
                        }
                    }
                }
            }
        }
    }
}
