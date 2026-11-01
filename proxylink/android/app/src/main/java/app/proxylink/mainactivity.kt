package app.proxylink

import android.Manifest
import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as agcolor
import android.graphics.drawable.Icon
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon as m3icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class mainactivity : ComponentActivity() {
    private val model: mainmodel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(agcolor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(agcolor.TRANSPARENT),
        )
        handle(intent)
        setContent { proxytheme { dashboard(model) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i?.getBooleanExtra("scan", false) == true) model.requestscan()
    }
}

@Composable
private fun dashboard(model: mainmodel) {
    val ctx = LocalContext.current
    val scanning by model.scanning.collectAsState()
    val audioon by model.audioon.collectAsState()
    val req by model.scanrequest.collectAsState()
    val saved by model.saved.collectAsState()
    val state by link.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var pendingmode by remember { mutableStateOf<String?>(null) }

    val camperm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) model.startscan()
    }
    val notifperm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    val projlauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, captureservice::class.java).putExtra("mode", "phone").putExtra("code", r.resultCode).putExtra("data", data),
            )
        }
    }

    val begin: (String) -> Unit = { mode ->
        if (mode == "mic") {
            ContextCompat.startForegroundService(ctx, Intent(ctx, captureservice::class.java).putExtra("mode", "mic"))
        } else {
            projlauncher.launch(ctx.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
        }
    }

    val micperm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val m = pendingmode
        pendingmode = null
        if (ok && m != null) begin(m)
    }

    val onuplink: (String) -> Unit = { mode ->
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            begin(mode)
        } else {
            pendingmode = mode
            micperm.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifperm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val scan = {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            model.startscan()
        else camperm.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(req) {
        if (req) {
            model.clearscanrequest()
            val s = link.state.value
            if (s is linkstate.idle || s is linkstate.failed) {
                if (saved != null) {
                    link.disconnect()
                    model.connectsaved()
                } else scan()
            }
        }
    }

    val addtile = {
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(ctx, linktile::class.java),
                "proxylink",
                Icon.createWithResource(ctx, R.drawable.ic_link),
                ctx.mainExecutor,
            ) {}
        }
    }

    if (scanning) {
        Box(Modifier.fillMaxSize()) {
            qrscanner(Modifier.fillMaxSize()) { model.onqr(it) }
            scanoverlay(Modifier.fillMaxSize())
            abutton(
                "cancel", model::cancelscan,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp), kind = 2,
            )
        }
        return
    }

    val paired = state is linkstate.paired
    val orb = when (state) {
        is linkstate.idle -> colidle
        is linkstate.working -> colworking
        is linkstate.paired -> colok
        is linkstate.failed -> colerr
    }
    val pulsing = state is linkstate.working || (paired && (audioon))
    val tabs = listOf("audio" to R.drawable.ic_nav_audio, "control" to R.drawable.ic_nav_control, "tools" to R.drawable.ic_nav_tools)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (paired) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    tabs.forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { m3icon(painterResource(t.second), contentDescription = null) },
                            label = { Text(t.first) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (paired) {
                appheader(orb, pulsing, "connected: ${(state as linkstate.paired).pcname}")
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                    label = "tab",
                ) { t ->
                    when (t) {
                        0 -> audiotab(model, onuplink)
                        1 -> controltab(model)
                        else -> toolstab(model)
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    statusorb(orb, state is linkstate.working, Modifier.size(140.dp))
                    Text("proxylink", style = MaterialTheme.typography.headlineMedium)
                    AnimatedContent(
                        targetState = state,
                        contentKey = { it::class },
                        transitionSpec = {
                            (fadeIn(tween(260)) + slideInVertically(tween(260)) { h -> h / 10 }) togetherWith fadeOut(tween(140))
                        },
                        label = "state",
                    ) { s ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            when (s) {
                                is linkstate.idle -> {
                                    Text(
                                        saved?.let { "paired with ${it.name}" } ?: "not paired yet",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    saved?.let { abutton("connect to ${it.name}", model::connectsaved) }
                                    abutton(if (saved != null) "pair new pc (scan qr)" else "scan qr", { scan() }, kind = if (saved != null) 2 else 0)
                                    if (saved != null) abutton("forget pc", model::forget, kind = 2)
                                    if (Build.VERSION.SDK_INT >= 33) abutton("add quick settings tile", { addtile() }, kind = 2)
                                }
                                is linkstate.working -> {
                                    Text(s.msg)
                                    abutton("cancel", model::disconnect, kind = 2)
                                }
                                is linkstate.failed -> {
                                    Text(s.msg, color = colerr)
                                    saved?.let { abutton("retry ${it.name}", { link.disconnect(); model.connectsaved() }) }
                                    abutton(if (saved != null) "pair new pc (scan qr)" else "retry", { scan() }, kind = if (saved != null) 2 else 0)
                                }
                                is linkstate.paired -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}
