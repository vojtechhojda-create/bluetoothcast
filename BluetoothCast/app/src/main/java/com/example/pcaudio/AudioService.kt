package com.example.pcaudio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import org.json.JSONObject
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Služba na pozadí:
 *  - UDP: odpovídá na hledání z PC (aby PC našlo telefon bez zadávání IP)
 *  - TCP: PC pošle žádost, telefon ukáže notifikaci Povolit/Zamítnout,
 *         po povolení přehrává příchozí PCM zvuk.
 */
class AudioService : Service() {

    companion object {
        const val PORT = 8766
        const val ACTION_ALLOW = "com.example.pcaudio.ALLOW"
        const val ACTION_DENY = "com.example.pcaudio.DENY"
        const val ACTION_STOP = "com.example.pcaudio.STOP"
        const val CH_STATUS = "status"
        const val CH_REQUEST = "request"
    }

    private val answers = LinkedBlockingQueue<Boolean>()
    @Volatile private var running = false
    @Volatile private var busy = false
    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var mcastLock: WifiManager.MulticastLock? = null
    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Stav služby", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REQUEST, "Žádosti o přehrávání", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ALLOW -> answers.offer(true)
            ACTION_DENY -> answers.offer(false)
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> if (!running) start()
        }
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun start() {
        running = true
        val n = statusNotification("Čekám na PC (${localIp()}:$PORT)")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, n)
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "pcaudio").apply { acquire() }
        mcastLock = wm.createMulticastLock("pcaudio").apply {
            setReferenceCounted(false)
            acquire()
        }
        thread(name = "udp") { discoveryLoop() }
        thread(name = "tcp") { acceptLoop() }
    }

    private fun discoveryLoop() {
        try {
            val s = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
            }
            udpSocket = s
            val buf = ByteArray(64)
            while (running) {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                if (String(p.data, 0, p.length) == "CMF_AUDIO?") {
                    val r = "CMF_AUDIO!".toByteArray()
                    s.send(DatagramPacket(r, r.size, p.address, p.port))
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun acceptLoop() {
        try {
            val ss = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
            }
            serverSocket = ss
            while (running) {
                val sock = ss.accept()
                if (busy) {
                    sock.close()
                    continue
                }
                busy = true
                thread(name = "client") {
                    try {
                        handle(sock)
                    } catch (_: Exception) {
                    } finally {
                        busy = false
                        try { sock.close() } catch (_: Exception) {}
                        nm.cancel(2)
                        if (running) updateStatus("Čekám na PC (${localIp()}:$PORT)")
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun handle(sock: Socket) {
        sock.tcpNoDelay = true
        sock.keepAlive = true
        sock.soTimeout = 10_000
        val inp = sock.getInputStream()
        val out = sock.getOutputStream()

        val h = JSONObject(readLine(inp))
        val name = h.optString("name", "PC")
        val rate = h.optInt("rate", 48000)
        val ch = h.optInt("channels", 2).coerceIn(1, 2)

        answers.clear()
        showRequest(name)
        val ok = answers.poll(20, TimeUnit.SECONDS) == true
        nm.cancel(2)

        out.write((if (ok) "OK\n" else "NO\n").toByteArray())
        out.flush()
        if (!ok) return

        sock.soTimeout = 0 // při tichu PC nic neposílá
        updateStatus("Přehrávám z: $name")
        play(inp, rate, ch)
    }

    private fun play(inp: InputStream, rate: Int, ch: Int) {
        val mask = if (ch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val frame = ch * 2
        val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val bufBytes = maxOf(min, rate * frame / 15) // ~65 ms

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(mask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufBytes)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        val chunk = ByteArray(rate * frame / 50) // 20 ms, násobek frame
        val maxBacklog = rate * frame * 15 / 100 // nad 150 ms nahromaděných dat
        val keep = rate * frame / 20             // nechat ~50 ms

        track.play()
        try {
            while (true) {
                // dojetí zpoždění: když se po zadrhnutí sítě nahromadí data, zahodíme starší
                val avail = inp.available()
                if (avail > maxBacklog) {
                    var drop = (avail - keep) / frame * frame
                    while (drop > 0) {
                        val k = minOf(drop, chunk.size)
                        if (!readFully(inp, chunk, k)) return
                        drop -= k
                    }
                }
                if (!readFully(inp, chunk, chunk.size)) break
                track.write(chunk, 0, chunk.size)
            }
        } finally {
            try { track.stop() } catch (_: Exception) {}
            track.release()
        }
    }

    private fun readFully(inp: InputStream, buf: ByteArray, len: Int): Boolean {
        var off = 0
        while (off < len) {
            val n = inp.read(buf, off, len - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun readLine(i: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = i.read()
            if (b < 0 || b == '\n'.code) break
            sb.append(b.toChar())
            if (sb.length > 1024) throw IllegalStateException("hlavička je moc dlouhá")
        }
        return sb.toString()
    }

    private fun pi(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code,
            Intent(this, AudioService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    @Suppress("DEPRECATION")
    private fun showRequest(name: String) {
        val n = Notification.Builder(this, CH_REQUEST)
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentTitle("$name chce přehrávat zvuk")
            .setContentText("Povolit přehrávání na tomto telefonu?")
            .setTimeoutAfter(20_000)
            .addAction(android.R.drawable.ic_media_play, "Povolit", pi(ACTION_ALLOW, 1))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Zamítnout", pi(ACTION_DENY, 2))
            .build()
        nm.notify(2, n)
    }

    @Suppress("DEPRECATION")
    private fun statusNotification(text: String): Notification =
        Notification.Builder(this, CH_STATUS)
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentTitle("BluetoothCast")
            .setContentText(text)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Vypnout", pi(ACTION_STOP, 3))
            .build()

    private fun updateStatus(text: String) = nm.notify(1, statusNotification(text))

    private fun localIp(): String = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress ?: "?"
    } catch (_: Exception) {
        "?"
    }

    override fun onDestroy() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        try { udpSocket?.close() } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
        try { mcastLock?.release() } catch (_: Exception) {}
        nm.cancel(2)
        super.onDestroy()
    }
}
