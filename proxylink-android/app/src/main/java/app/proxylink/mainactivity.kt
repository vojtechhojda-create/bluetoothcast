package app.proxylink

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as agcolor
import android.graphics.drawable.Icon
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { dashboard(model) }
            }
        }
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
    val state by link.state.collectAsState()

    val camperm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) model.startscan()
    }
    val notifperm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

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
            if (s is linkstate.idle || s is linkstate.failed) scan()
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
    } else {
        val orb = when (state) {
            is linkstate.idle -> colidle
            is linkstate.working -> colworking
            is linkstate.paired -> colok
            is linkstate.failed -> colerr
        }
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("proxylink", style = MaterialTheme.typography.headlineMedium)
            statusorb(orb, pulsing = state is linkstate.working || (state is linkstate.paired && audioon))
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
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    when (s) {
                        is linkstate.idle -> {
                            abutton("scan qr", { scan() })
                            if (Build.VERSION.SDK_INT >= 33) abutton("add quick settings tile", { addtile() }, kind = 2)
                        }
                        is linkstate.working -> Text(s.msg)
                        is linkstate.failed -> {
                            Text(s.msg, color = colerr)
                            abutton("retry", { scan() })
                        }
                        is linkstate.paired -> {
                            Text("connected: ${s.pcname}")
                            abutton(if (audioon) "stop audio" else "start audio", model::toggleaudio)
                            AnimatedVisibility(audioon) { equalizer(colok) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                abutton("vol -", { model.media("vol_down") }, kind = 1)
                                abutton("mute", { model.media("mute") }, kind = 1)
                                abutton("vol +", { model.media("vol_up") }, kind = 1)
                            }
                            abutton("play / pause", { model.media("play_pause") }, kind = 1)
                            abutton("disconnect", model::disconnect, kind = 2)
                        }
                    }
                }
            }
        }
    }
}
