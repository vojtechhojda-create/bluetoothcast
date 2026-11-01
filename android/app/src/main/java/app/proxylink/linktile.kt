package app.proxylink

import android.app.PendingIntent
import android.content.Intent
import androidx.core.content.ContextCompat
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class linktile : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onStartListening() {
        job?.cancel()
        job = scope.launch { link.state.collect { render(it) } }
    }

    override fun onStopListening() {
        job?.cancel()
        job = null
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        if (link.state.value is linkstate.paired) {
            link.disconnect()
            return
        }
        if (store.load(this) != null) {
            val ok = runCatching {
                ContextCompat.startForegroundService(this, Intent(this, proxyservice::class.java))
            }.isSuccess
            if (ok && link.connectsaved(this)) return
        }
        if (isLocked) unlockAndRun { openapp() } else openapp()
    }

    private fun openapp() {
        val i = Intent(this, mainactivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("scan", true)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }

    private fun render(s: linkstate) {
        val t = qsTile ?: return
        t.icon = Icon.createWithResource(this, R.drawable.ic_link)
        val sub: String
        when (s) {
            is linkstate.paired -> { t.state = Tile.STATE_ACTIVE; t.label = s.pcname; sub = "connected" }
            is linkstate.working -> { t.state = Tile.STATE_UNAVAILABLE; t.label = "proxylink"; sub = s.msg }
            else -> { t.state = Tile.STATE_INACTIVE; t.label = "proxylink"; sub = "tap to pair" }
        }
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = sub
        t.updateTile()
    }
}
