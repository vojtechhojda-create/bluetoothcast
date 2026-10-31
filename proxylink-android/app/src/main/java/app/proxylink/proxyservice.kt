package app.proxylink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class proxyservice : Service() {
    private val player = audioplayer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wifilock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("link", "connection", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(
            this, 1, notification(link.state.value),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        )

        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifilock = wm.createWifiLock(mode, "proxylink").apply {
            setReferenceCounted(false)
            acquire()
        }

        link.onaudioconfig = { rate, channels -> player.start(rate, channels) }
        link.onaudioframe = { b, o, l -> player.write(b, o, l) }
        scope.launch {
            link.state.collect { s ->
                when (s) {
                    is linkstate.paired, is linkstate.working -> nm.notify(1, notification(s))
                    else -> stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            link.disconnect()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        link.onaudioconfig = null
        link.onaudioframe = null
        player.stop()
        wifilock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun notification(s: linkstate): Notification {
        val text = when (s) {
            is linkstate.paired -> "connected: ${s.pcname}"
            is linkstate.working -> s.msg
            else -> "disconnected"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, mainactivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, proxyservice::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, "link")
            .setSmallIcon(R.drawable.ic_link)
            .setContentTitle("proxylink")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "disconnect", stop)
            .build()
    }
}
