package app.proxylink

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun card(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
fun appheader(orb: Color, pulsing: Boolean, subtitle: String) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        statusorb(orb, pulsing, Modifier.size(56.dp))
        Column {
            Text("proxylink", style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun label(t: String) =
    Text(t, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun audiotab(model: mainmodel, onuplink: (String) -> Unit) {
    val audioon by model.audioon.collectAsState()
    val uplink by link.uplink.collectAsState()
    val apps by link.apps.collectAsState()
    val source by model.source.collectAsState()
    var state by remember { mutableStateOf<String>("výchozí") }
    val outkey by model.outkey.collectAsState()
    val buf by model.bufmult.collectAsState()
    val err by link.error.collectAsState()
    var v by remember(buf) { mutableFloatStateOf(buf.toFloat()) }

    LaunchedEffect(Unit) { model.refreshapps() }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        card("pc audio to this phone") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                abutton(if (audioon) "stop" else "start", model::toggleaudio, kind = if (audioon) 1 else 0)
                AnimatedVisibility(audioon) { equalizer(colok) }
            }
            label("source")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = source == 0, onClick = { model.setsource(0) }, label = { Text("all pc audio") })
                apps.forEach { a ->
                    FilterChip(
                        selected = source == a.pid,
                        onClick = { model.setsource(a.pid) },
                        label = { Text(a.name + if (a.active) " *" else "") },
                    )
                }
                AssistChip(onClick = { model.refreshapps() }, label = { Text("refresh") })
            }
            label("play on phone through")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = outkey.isEmpty(), onClick = { model.setoutput("") }, label = { Text("auto") })
                outputs.forEach { o ->
                    FilterChip(selected = outkey == o.key, onClick = { model.setoutput(o.key) }, label = { Text(o.label) })
                }
            }
            label("buffer x${v.roundToInt()} (higher = more stable, more delay)")
            Slider(
                value = v,
                onValueChange = { v = it },
                onValueChangeFinished = { model.setbuf(v.roundToInt()) },
                valueRange = 1f..8f,
                steps = 6,
            )
            err?.let { Text(it, color = colerr) }
        }

        card("this phone to pc") {
            label("plays on the pc speakers")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                abutton(
                    if (uplink == "mic") "stop mic" else "phone mic",
                    { if (uplink == "mic") link.uplinkstop() else onuplink("mic") },
                    kind = if (uplink == "mic") 1 else 2,
                )
                if (Build.VERSION.SDK_INT >= 29) {
                    abutton(
                        if (uplink == "phone") "stop audio" else "phone audio",
                        { if (uplink == "phone") link.uplinkstop() else onuplink("phone") },
                        kind = if (uplink == "phone") 1 else 2,
                    )
                }
            }
            AnimatedVisibility(uplink != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    equalizer(colok)
                    Text("sending to pc")
                }
            }
        }
    }
}

@Composable
fun controltab(model: mainmodel) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            link.scope.launch {
                uris.forEach {
                    val ok = link.sharefile(ctx.applicationContext, it)
                    link.note(if (ok) "file sent" else "file failed")
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        card("pc media") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                abutton("prev", { model.media("prev") }, kind = 1)
                abutton("play / pause", { model.media("play_pause") })
                abutton("next", { model.media("next") }, kind = 1)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                abutton("vol -", { model.media("vol_down") }, kind = 1)
                abutton("mute", { model.media("mute") }, kind = 1)
                abutton("vol +", { model.media("vol_up") }, kind = 1)
            }
        }
        card("send to pc") {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("text or link") },
                minLines = 2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                abutton("send text", {
                    if (text.isNotBlank()) {
                        link.sharetext(text)
                        text = ""
                    }
                })
                abutton("send files", { picker.launch("*/*") }, kind = 2)
            }
        }
    }
}

@Composable
fun toolstab(model: mainmodel) {
    val log by link.log.collectAsState()
    val state by link.state.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        card("connection") {
            Text(if (state is linkstate.paired) "connected to ${(state as linkstate.paired).pcname}" else "not connected")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                abutton("disconnect", model::disconnect, kind = 1)
                abutton("forget pc", model::forget, kind = 2)
            }
        }
        card("dev tools") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                abutton("ping", { link.ping() }, kind = 1)
                abutton("echo", { link.chan("echo", "hello") }, kind = 1)
            }
            Text(
                log.takeLast(10).joinToString("\n").ifEmpty { "no events yet" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
