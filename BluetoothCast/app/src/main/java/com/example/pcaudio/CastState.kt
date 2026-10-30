package com.example.pcaudio

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import java.util.concurrent.CopyOnWriteArrayList

/** Sdílený stav služby, ze kterého čte dlaždice v rychlých nastaveních. */
object CastState {
    @Volatile var running = false
    @Volatile var connectedTo: String? = null

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    /** Zavolej po každé změně stavu. */
    fun changed(context: Context) {
        listeners.forEach { it() }
        try {
            TileService.requestListeningState(
                context,
                ComponentName(context, CastTileService::class.java)
            )
        } catch (_: Exception) {
        }
    }
}

/** IP adresa telefonu v lokální síti (nebo "?" když není na Wi-Fi). */
fun lanIp(): String = try {
    java.net.NetworkInterface.getNetworkInterfaces().toList()
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address && it.isSiteLocalAddress }
        ?.hostAddress ?: "?"
} catch (_: Exception) {
    "?"
}
