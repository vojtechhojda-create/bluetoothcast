package com.example.pcaudio

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Dlaždice v rychlých nastaveních (vedle Wi-Fi, dat, jasu...).
 * Klepnutí zapne/vypne službu. Podnázev ukazuje stav:
 * "Vypnuto", "Čeká na PC" nebo "Připojeno k <název PC>".
 */
class CastTileService : TileService() {

    private val main = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { main.post { refresh() } }

    override fun onStartListening() {
        CastState.addListener(listener)
        refresh()
    }

    override fun onStopListening() {
        CastState.removeListener(listener)
    }

    override fun onClick() {
        val svc = Intent(this, AudioService::class.java)
        if (CastState.running) {
            stopService(svc)
        } else {
            try {
                startForegroundService(svc)
            } catch (_: Exception) {
                openApp() // kdyby systém start ze dlaždice nepovolil
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            startActivityAndCollapse(i)
        }
    }

    private fun refresh() {
        val t = qsTile ?: return
        val running = CastState.running
        val to = CastState.connectedTo
        val subtitle = when {
            !running -> "Vypnuto"
            to != null -> "Připojeno k $to"
            else -> "Čeká na PC"
        }
        t.icon = Icon.createWithResource(
            this,
            if (running && to != null) R.drawable.ic_tile else R.drawable.ic_tile_off
        )
        t.label = "BluetoothCast"
        t.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = subtitle
        if (Build.VERSION.SDK_INT >= 30) t.stateDescription = subtitle
        t.updateTile()
    }
}
