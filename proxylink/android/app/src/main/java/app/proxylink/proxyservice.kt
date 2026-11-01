package app.proxylink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
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
    private val player = audioplayer(this)
    private val rx = incoming(this) { name, mime, uri -> savednotif(name, mime, uri) }
    private var nid = 100
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wifilock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("link", "connection", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("incoming", "from pc", NotificationManager.IMPORTANCE_DEFAULT))
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
        link.onbinary = { rx.chunk(it) }
        link.onmessage = { m ->
            when (m.optString("type")) {
                "share.push" -> push(m.optString("kind"), m.optString("data"), m.optString("title"))
                "file.offer" -> rx.offer(m.optInt("id", -1), m.optString("name"))
                "file.done" -> rx.done(m.optInt("id", -1))
                else -> Unit
            }
        }
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
        link.onbinary = null
        link.onmessage = null
        rx.abort()
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

    private fun push(kind: String, data: String, title: String) {
        if (data.isBlank()) return
        val id = nid++
        val url = kind == "link" && Uri.parse(data).scheme?.lowercase() in listOf("http", "https")
        val b = NotificationCompat.Builder(this, "incoming")
            .setSmallIcon(R.drawable.ic_link)
            .setContentTitle(title.ifBlank { if (url) "link from pc" else "text from pc" })
            .setContentText(data)
            .setStyle(NotificationCompat.BigTextStyle().bigText(data))
            .setAutoCancel(true)
        if (url) {
            b.setContentIntent(
                PendingIntent.getActivity(
                    this, id,
                    Intent(Intent.ACTION_VIEW, Uri.parse(data)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
        }
        b.addAction(
            0, "copy",
            PendingIntent.getBroadcast(
                this, id, Intent(this, copyreceiver::class.java).putExtra("text", data), PendingIntent.FLAG_IMMUTABLE
            )
        )
        getSystemService(NotificationManager::class.java).notify(id, b.build())
    }

    private fun savednotif(name: String, mime: String, uri: Uri?) {
        val id = nid++
        val b = NotificationCompat.Builder(this, "incoming")
            .setSmallIcon(R.drawable.ic_link)
            .setContentTitle("received from pc")
            .setContentText(name)
            .setAutoCancel(true)
        if (uri != null) {
            b.setContentIntent(
                PendingIntent.getActivity(
                    this, id,
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
        }
        getSystemService(NotificationManager::class.java).notify(id, b.build())
    }
}
