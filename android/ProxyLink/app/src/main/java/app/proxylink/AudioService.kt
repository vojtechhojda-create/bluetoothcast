package app.proxylink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Sluzba na pozadi: odpovida na hledani z PC (UDP), prijima spojeni (TCP),
 * resi parovani jednorazovym kodem a prehrava prijaty zvuk. Protokol je popsany v proxylink_pc.py.
 */
class AudioService : Service() {

    companion object {
        const val PORT = 8766
        const val ACTION_START = "app.proxylink.START"
        const val ACTION_STOP = "app.proxylink.STOP"
        const val ACTION_CANCEL_PAIR = "app.proxylink.CANCEL_PAIR"
        const val ACTION_RESTART_STREAM = "app.proxylink.RESTART_STREAM"
        private const val CH_SERVICE = "proxylink_service"
        private const val CH_PAIR = "proxylink_pair"
        private const val ID_SERVICE = 1
        private const val ID_PAIR = 2
    }

    private val alive = AtomicBoolean(false)
    private val sessionId = AtomicInteger(0)
    private val rng = SecureRandom()
    private var udp: DatagramSocket? = null
    private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null
    @Volatile private var cancelPair = false
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_CANCEL_PAIR -> {
                cancelPair = true
                Thread {   // PC dostane "denied" a nebude se dokola znovu parovat
                    try {
                        client?.getOutputStream()?.let { send(it, JSONObject().put("status", "denied")) }
                    } catch (_: Exception) {
                    }
                    closeClient()
                }.start()
            }
            ACTION_RESTART_STREAM -> if (ProxyState.phase == Phase.STREAMING) closeClient()
            else -> startEverything()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    // ---------- zivotni cyklus ----------

    private fun startEverything() {
        createChannels()
        try {
            val n = buildNotification("Čekám na PC…")
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(ID_SERVICE, n)
            }
        } catch (e: Exception) {
            stopSelf()
            return
        }
        if (!alive.compareAndSet(false, true)) return
        Prefs.get(this).edit().putBoolean("enabled", true).apply()
        acquireLocks()
        setPhase(Phase.LISTENING)
        Thread({ udpLoop() }, "pl-udp").start()
        Thread({ tcpLoop() }, "pl-tcp").start()
    }

    private fun shutdown() {
        Prefs.get(this).edit().putBoolean("enabled", false).apply()
        cleanup()
        setPhase(Phase.OFF)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        alive.set(false)
        closeClient()
        try { server?.close() } catch (_: Exception) {}
        try { udp?.close() } catch (_: Exception) {}
        releaseLocks()
        getSystemService(NotificationManager::class.java).cancel(ID_PAIR)
    }

    private fun closeClient() {
        try { client?.close() } catch (_: Exception) {}
    }

    private fun acquireLocks() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "proxylink").apply { setReferenceCounted(false); acquire() }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "proxylink:svc")
            .apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseLocks() {
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
    }

    // ---------- sit ----------

    private fun udpLoop() {
        try {
            val s = DatagramSocket(null)
            s.reuseAddress = true
            s.broadcast = true
            s.bind(InetSocketAddress(PORT))
            udp = s
            val buf = ByteArray(64)
            while (alive.get()) {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                if (String(p.data, 0, p.length) == "PROXYLINK?") {
                    val r = "PROXYLINK!".toByteArray()
                    s.send(DatagramPacket(r, r.size, p.address, p.port))
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun tcpLoop() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(PORT))
            server = ss
            while (alive.get()) {
                val c = ss.accept()
                closeClient()                       // novy PC vytlaci stary (napr. pulotevreny) spoj
                client = c
                val id = sessionId.incrementAndGet()
                Thread({ handle(c, id) }, "pl-client").start()
            }
        } catch (_: Exception) {
        }
    }

    private fun readLine(input: InputStream): String {
        val sb = ByteArrayOutputStream()
        while (sb.size() < 4096) {
            val b = input.read()
            if (b < 0) throw EOFException()
            if (b == '\n'.code) return sb.toString("UTF-8")
            sb.write(b)
        }
        throw IOException("line too long")
    }

    private fun send(out: OutputStream, o: JSONObject) {
        out.write((o.toString() + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun phoneName(): String =
        Settings.Global.getString(contentResolver, "device_name") ?: Build.MODEL

    private fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes)
        rng.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun same(a: String, b: String) =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    // ---------- jedno spojeni ----------

    private fun handle(c: Socket, id: Int) {
        try {
            c.tcpNoDelay = true
            c.soTimeout = 10_000
            val input = c.getInputStream()
            val out = c.getOutputStream()

            val hello = JSONObject(readLine(input))
            val pc = hello.optString("name", "PC").take(60)
            val rate = hello.optInt("rate", 48000).coerceIn(8000, 192000)
            val token = hello.optString("token", "")
            val prefs = Prefs.get(this)
            val lat = Prefs.latency(this)
            val saved = prefs.getString("token_$pc", "") ?: ""

            val reply = JSONObject()
                .put("name", phoneName())
                .put("chunk_ms", lat.chunkMs)
                .put("buffer_ms", lat.pcBufferMs)

            if (saved.isNotEmpty() && token.isNotEmpty() && same(saved, token)) {
                send(out, reply.put("status", "ok"))
            } else {
                // neznamy PC: ukazat jednorazovy kod, PC ho musi opsat
                val code = "%06d".format(rng.nextInt(1_000_000))
                cancelPair = false
                ProxyState.pcName = pc
                ProxyState.pairCode = code
                setPhase(Phase.PAIRING)
                notifyPairing(pc)
                send(out, JSONObject().put("status", "pair").put("name", phoneName()))

                c.soTimeout = 180_000
                val given = JSONObject(readLine(input)).optString("code", "")
                val ok = !cancelPair && same(code, given)
                ProxyState.pairCode = ""
                getSystemService(NotificationManager::class.java).cancel(ID_PAIR)
                if (!ok) {
                    send(out, JSONObject().put("status", "denied"))
                    return
                }
                val newToken = randomHex(16)
                prefs.edit().putString("token_$pc", newToken).apply()
                send(out, reply.put("status", "paired").put("token", newToken))
            }
            c.soTimeout = 0

            prefs.edit().putString("last_pc", pc).apply()
            ProxyState.pcName = pc
            ProxyState.kbps = 0
            ProxyState.playing = false
            setPhase(Phase.STREAMING)
            stream(input, rate, lat, id)
        } catch (_: Exception) {
            // spojeni spadlo nebo bylo zavreno - uklidime ve finally
        } finally {
            try { c.close() } catch (_: Exception) {}
            if (sessionId.get() == id && alive.get()) {
                ProxyState.pairCode = ""
                ProxyState.kbps = 0
                getSystemService(NotificationManager::class.java).cancel(ID_PAIR)
                setPhase(Phase.LISTENING)
            }
        }
    }

    // ---------- prehravani ----------

    private fun buildTrack(rate: Int, lat: Latency): AudioTrack {
        val bytesPerMs = rate * 4 / 1000
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val size = maxOf(minBuf, bytesPerMs * lat.trackMs) / 4 * 4
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (lat == Latency.LOW) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        return builder.build()
    }

    private fun stream(input: InputStream, rate: Int, lat: Latency, id: Int) {
        val bytesPerMs = rate * 4 / 1000
        val prefillBytes = bytesPerMs * lat.prefillMs
        val maxBytes = bytesPerMs * lat.maxQueueMs

        val lock = ReentrantLock()
        val cond = lock.newCondition()
        val queue = ArrayDeque<ByteArray>()
        var queued = 0
        var finished = false
        var lastData = 0L
        var total = 0L

        val track = buildTrack(rate, lat)

        val player = Thread({
            try {
                track.play()
                var started = false
                while (true) {
                    val chunk: ByteArray? = lock.withLock {
                        while (!finished && (queue.isEmpty() || (!started && queued < prefillBytes))) {
                            cond.await(100, TimeUnit.MILLISECONDS)
                        }
                        if (finished) null else queue.pollFirst().also { queued -= it.size }
                    }
                    if (chunk == null) break
                    started = true
                    track.write(chunk, 0, chunk.size)   // blokuje, dokud se misto v bufferu neuvolni
                }
            } catch (_: Exception) {
            }
        }, "pl-player")

        val stats = Thread({
            var lastBytes = 0L
            var lastT = System.nanoTime()
            try {
                while (true) {
                    Thread.sleep(500)
                    val now = System.nanoTime()
                    val snap = lock.withLock { longArrayOf(queued.toLong(), total, lastData, if (finished) 1L else 0L) }
                    if (snap[3] == 1L) break
                    val sec = (now - lastT) / 1e9
                    ProxyState.kbps = ((snap[1] - lastBytes) * 8 / 1000 / maxOf(sec, 0.001)).toInt()
                    ProxyState.latencyMs = snap[0].toInt() / maxOf(bytesPerMs, 1) + lat.trackMs
                    ProxyState.playing = System.currentTimeMillis() - snap[2] < 1000
                    lastBytes = snap[1]
                    lastT = now
                    ProxyState.changed()
                }
            } catch (_: InterruptedException) {
            }
        }, "pl-stats")

        player.start()
        stats.start()
        try {
            val rb = ByteArray((bytesPerMs * lat.chunkMs.coerceAtLeast(5)) / 4 * 4)
            var carry = 0
            while (alive.get() && sessionId.get() == id) {
                val n = input.read(rb, carry, rb.size - carry)
                if (n < 0) break
                val have = carry + n
                val usable = have - have % 4
                if (usable > 0) {
                    val data = rb.copyOf(usable)
                    lock.withLock {
                        queue.addLast(data)
                        queued += data.size
                        total += data.size
                        lastData = System.currentTimeMillis()
                        // zpozdeni nesmi rust: nad limitem se nejstarsi zvuk zahodi
                        while (queued > maxBytes && queue.size > 1) queued -= queue.pollFirst().size
                        cond.signalAll()
                    }
                }
                carry = have - usable
                if (carry > 0) System.arraycopy(rb, usable, rb, 0, carry)
            }
        } finally {
            lock.withLock { finished = true; cond.signalAll() }
            try { player.join(1000) } catch (_: InterruptedException) {}
            stats.interrupt()
            try { track.pause(); track.flush(); track.stop() } catch (_: Exception) {}
            track.release()
        }
    }

    // ---------- stav a notifikace ----------

    private fun setPhase(p: Phase) {
        ProxyState.phase = p
        ProxyState.changed()
        if (alive.get() && p != Phase.OFF) {
            val text = when (p) {
                Phase.LISTENING -> "Čekám na PC…"
                Phase.PAIRING -> "Párování s ${ProxyState.pcName}"
                Phase.STREAMING -> "Streamuji z ${ProxyState.pcName}"
                else -> ""
            }
            getSystemService(NotificationManager::class.java).notify(ID_SERVICE, buildNotification(text))
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "ProxyLink běží", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_PAIR, "Párování", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AudioService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CH_SERVICE)
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentTitle("ProxyLink")
            .setContentText(text)
            .setContentIntent(openApp())
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Vypnout", stop
                ).build()
            )
            .build()
    }

    private fun notifyPairing(pc: String) {
        val n = Notification.Builder(this, CH_PAIR)
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentTitle("Párování s $pc")
            .setContentText("Klepni a opiš jednorázový kód na PC")
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(ID_PAIR, n)
    }
}
