package app.proxylink

import android.content.Context
import android.os.Build
import android.util.Base64
import android.provider.OpenableColumns
import android.util.Patterns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class twws(val id: String, val name: String, val branch: String, val dirty: Int, val tasks: List<String>)

data class appinfo(val pid: Int, val name: String, val title: String, val active: Boolean)

sealed interface linkstate {
    data object idle : linkstate
    data class working(val msg: String) : linkstate
    data class paired(val pcname: String) : linkstate
    data class failed(val msg: String) : linkstate
}

object link {
    private const val frame_audio: Byte = 1
    private const val frame_file: Byte = 2
    private const val chunk = 32 * 1024
    private val delays = longArrayOf(1000, 2000, 3000, 5000, 8000, 12000, 20000, 30000)

    private val base = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<linkstate>(linkstate.idle)
    val state = _state.asStateFlow()

    private val _apps = MutableStateFlow<List<appinfo>>(emptyList())
    val apps = _apps.asStateFlow()

    private val _audioin = MutableStateFlow(false)
    val audioin = _audioin.asStateFlow()

    private val _uplink = MutableStateFlow<String?>(null)
    val uplink = _uplink.asStateFlow()

    private val _tw = MutableStateFlow<List<twws>>(emptyList())
    val twocode = _tw.asStateFlow()

    private val _twout = MutableStateFlow("")
    val twout = _twout.asStateFlow()

    private val _twrun = MutableStateFlow<String?>(null)
    val twrun = _twrun.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log = _log.asStateFlow()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var generation = 0
    @Volatile private var autoreconnect = false
    private var attempts = 0
    private var retry: Job? = null
    private val fileid = AtomicInteger(1)

    @Volatile var onaudioconfig: ((Int, Int) -> Unit)? = null
    @Volatile var onaudioframe: ((ByteArray, Int, Int) -> Unit)? = null
    @Volatile var onmessage: ((JSONObject) -> Unit)? = null
    @Volatile var onbinary: ((ByteArray) -> Unit)? = null

    fun deviceid(ctx: Context): String {
        val p = ctx.getSharedPreferences("proxylink", Context.MODE_PRIVATE)
        return p.getString("deviceid", null) ?: UUID.randomUUID().toString().also {
            p.edit().putString("deviceid", it).apply()
        }
    }

    private fun pinned(fp: String): OkHttpClient {
        val want = fp.lowercase().toByteArray()
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val got = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                    .joinToString("") { "%02x".format(it) }.toByteArray()
                if (!MessageDigest.isEqual(got, want)) throw CertificateException("pc certificate mismatch")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), null) }
        return base.newBuilder().sslSocketFactory(ssl.socketFactory, tm).hostnameVerifier { _, _ -> true }.build()
    }

    fun note(s: String) {
        val t = java.time.LocalTime.now().withNano(0)
        _log.value = (_log.value + "$t $s").takeLast(40)
    }

    fun clearapps() {
        _apps.value = emptyList()
        _tw.value = emptyList()
        _twrun.value = null
    }

    private fun twhandle(d: JSONObject) {
        when (d.optString("op")) {
            "workspaces" -> {
                val a = d.optJSONArray("items")
                _tw.value = (0 until (a?.length() ?: 0)).mapNotNull { i ->
                    a?.optJSONObject(i)?.let { o ->
                        val t = o.optJSONArray("tasks")
                        twws(
                            o.optString("id"), o.optString("name"), o.optString("branch"), o.optInt("dirty"),
                            (0 until (t?.length() ?: 0)).map { t!!.optString(it) },
                        )
                    }
                }
            }
            "run.start" -> {
                _twrun.value = d.optString("id")
                _twout.value = ""
            }
            "run.out" -> _twout.value = (_twout.value + d.optString("text")).takeLast(4000)
            "run.done" -> {
                _twrun.value = null
                val err = d.optString("error")
                _twout.value = (_twout.value + "\n[exit ${d.optInt("code", -1)}${if (err.isEmpty()) "" else ": $err"}]").takeLast(4000)
            }
        }
    }

    private fun twsend(o: JSONObject): Boolean =
        send(JSONObject().put("type", "chan.msg").put("ch", "twocode").put("data", o))

    fun twrefresh(): Boolean = twsend(JSONObject().put("op", "workspaces"))

    fun twstart(workspace: String, task: String): Boolean =
        twsend(JSONObject().put("op", "run").put("id", System.currentTimeMillis().toString()).put("workspace", workspace).put("task", task))

    fun twstop(): Boolean {
        val id = _twrun.value ?: return false
        return twsend(JSONObject().put("op", "stop").put("id", id))
    }

    fun resetaudio() {
        _audioin.value = false
        _uplink.value = null
    }

    fun audiostop(): Boolean {
        _audioin.value = false
        return request("audio.stop")
    }

    fun uplinkstart(source: String, rate: Int, channels: Int): Boolean {
        _error.value = null
        _uplink.value = source
        return send(
            JSONObject().put("type", "audio.up.start").put("source", source).put("rate", rate).put("channels", channels)
        )
    }

    fun uplinkstop() {
        if (_uplink.value == null) return
        _uplink.value = null
        request("audio.up.stop")
    }

    fun sendaudio(buf: ByteArray, len: Int): Boolean {
        val ws = socket ?: return false
        if (ws.queueSize() > 512_000L) return true
        val out = ByteArray(len + 1)
        out[0] = 4
        System.arraycopy(buf, 0, out, 1, len)
        return ws.send(out.toByteString())
    }

    fun listapps(): Boolean = request("apps.list")

    fun audiostart(pid: Int): Boolean {
        _error.value = null
        _audioin.value = true
        return send(JSONObject().put("type", "audio.start").apply { if (pid > 0) put("pid", pid) })
    }

    fun ping(): Boolean = send(JSONObject().put("type", "ping").put("t", System.nanoTime()))

    fun chan(ch: String, data: String): Boolean =
        send(JSONObject().put("type", "chan.msg").put("ch", ch).put("data", data))

    fun working(msg: String) { _state.value = linkstate.working(msg) }

    fun fail(msg: String) { _state.value = linkstate.failed(msg) }

    fun pair(ctx: Context, host: String, port: Int, fp: String, token: String) {
        autoreconnect = false
        retry?.cancel()
        attempts = 0
        _state.value = linkstate.working("pairing")
        dial(ctx.applicationContext, host, port, fp, token)
    }

    fun connectsaved(ctx: Context): Boolean {
        val app = ctx.applicationContext
        val pc = store.load(app) ?: return false
        if (_state.value is linkstate.paired || _state.value is linkstate.working) return true
        autoreconnect = true
        attempts = 0
        retry?.cancel()
        _state.value = linkstate.working("connecting to ${pc.name}")
        retry = scope.launch { attemptsaved(app) }
        return true
    }

    private suspend fun attemptsaved(app: Context) {
        val pc = store.load(app)
        if (pc == null) {
            autoreconnect = false
            fail("not paired")
            return
        }
        val host = if (reachable(pc.host, pc.port)) pc.host else discoverhost(app, pc.name)
        if (!autoreconnect) return
        if (host == null) {
            scheduleretry(app)
            return
        }
        if (host != pc.host) store.save(app, pc.copy(host = host))
        dial(app, host, pc.port, pc.fp, null)
    }

    private fun scheduleretry(app: Context) {
        if (!autoreconnect) return
        if (attempts >= delays.size) {
            autoreconnect = false
            fail("pc not reachable")
            return
        }
        val d = delays[attempts++]
        note("reconnect in ${d / 1000}s")
        _state.value = linkstate.working("reconnecting")
        retry?.cancel()
        retry = scope.launch {
            delay(d)
            if (autoreconnect) attemptsaved(app)
        }
    }

    private fun stopauth(code: String) {
        generation++
        autoreconnect = false
        socket?.cancel()
        socket = null
        _state.value = linkstate.failed(code)
    }

    private fun dial(app: Context, host: String, port: Int, fp: String, token: String?) {
        socket?.cancel()
        val gen = ++generation
        val devid = deviceid(app)
        val devname = "${Build.MANUFACTURER} ${Build.MODEL}"
        socket = pinned(fp).newWebSocket(
            Request.Builder().url("wss://$host:$port").build(),
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    if (gen != generation) return
                    val m = JSONObject().put("deviceid", devid)
                    if (token != null) {
                        m.put("type", "auth.pair").put("token", token).put("devicename", devname)
                            .put("pubkey", keys.publickey())
                    } else {
                        m.put("type", "auth.hello")
                    }
                    ws.send(m.toString())
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    if (gen != generation) return
                    val m = runCatching { JSONObject(text) }.getOrNull() ?: return
                    when (m.optString("type")) {
                        "auth.challenge" -> {
                            val nonce = Base64.decode(m.optString("nonce"), Base64.NO_WRAP)
                            ws.send(
                                JSONObject().put("type", "auth.resume").put("sig", keys.sign(nonce)).toString()
                            )
                        }
                        "auth.pair.ok" -> {
                            attempts = 0
                            autoreconnect = true
                            val name = m.optString("name")
                            store.save(app, pc(name, host, port, fp))
                            _state.value = linkstate.paired(name)
                            note("paired with $name")
                        }
                        "auth.pair.pending" -> _state.value = linkstate.working("approve on pc")
                        "auth.pair.error", "auth.pair.denied" -> {
                            val code = m.optString("code", "rejected")
                            if (code == "unknown_device" || code == "invalid_signature") store.clear(app)
                            stopauth(code)
                        }
                        "apps.list" -> {
                            val a = m.optJSONArray("apps")
                            _apps.value = (0 until (a?.length() ?: 0)).mapNotNull { i ->
                                a?.optJSONObject(i)?.let {
                                    appinfo(it.optInt("pid"), it.optString("name"), it.optString("title"), it.optBoolean("active"))
                                }
                            }.filter { it.pid > 0 }
                        }
                        "pong" -> note("pong %.1f ms".format((System.nanoTime() - m.optLong("t")) / 1_000_000.0))
                        "chan.msg" -> {
                            val d = m.optJSONObject("data")
                            if (m.optString("ch") == "twocode" && d != null) twhandle(d)
                            else note("chan ${m.optString("ch")}: ${m.opt("data")}")
                        }
                        "error" -> if (_state.value is linkstate.paired) {
                            _error.value = m.optString("code")
                            if (m.optString("code").startsWith("audio_")) _audioin.value = false
                            if (m.optString("code").startsWith("playback_")) _uplink.value = null
                            note("error ${m.optString("code")}")
                        }
                        "audio.stopped" -> _audioin.value = false
                        "audio.up.stopped" -> _uplink.value = null
                        "audio.config" -> {
                            _audioin.value = true
                            onaudioconfig?.invoke(m.optInt("rate", 48000), m.optInt("channels", 2))
                        }
                        else -> if (_state.value is linkstate.paired) onmessage?.invoke(m)
                    }
                }

                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    if (gen != generation || bytes.size < 2) return
                    val arr = bytes.toByteArray()
                    if (arr[0] == frame_audio) onaudioframe?.invoke(arr, 1, arr.size - 1)
                    else onbinary?.invoke(arr)
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    ws.close(1000, null)
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    if (gen != generation) return
                    socket = null
                    if (code == 4401 || code == 4403 || code == 4408) {
                        autoreconnect = false
                        _state.value =
                            if (_state.value is linkstate.paired) linkstate.idle else linkstate.failed(reason.ifEmpty { "closed" })
                    } else {
                        dropped(app, null)
                    }
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    if (gen != generation) return
                    socket = null
                    dropped(app, t.message)
                }
            }
        )
    }

    private fun dropped(app: Context, msg: String?) {
        if (autoreconnect && store.load(app) != null) {
            scheduleretry(app)
        } else {
            _state.value =
                if (_state.value is linkstate.paired) linkstate.idle else linkstate.failed(msg ?: "connection closed")
        }
    }

    fun disconnect() {
        autoreconnect = false
        retry?.cancel()
        generation++
        socket?.close(1000, null)
        socket = null
        _state.value = linkstate.idle
    }

    private fun send(o: JSONObject): Boolean = socket?.send(o.toString()) ?: false

    fun request(type: String): Boolean = send(JSONObject().put("type", type))

    fun media(action: String): Boolean =
        send(JSONObject().put("type", "media.control").put("action", action))

    fun sharetext(text: String): Boolean {
        val t = text.trim()
        val kind = if (Patterns.WEB_URL.matcher(t).matches()) "link" else "text"
        return send(JSONObject().put("type", "share.send").put("kind", kind).put("data", t))
    }

    suspend fun sharefile(
        ctx: Context,
        uri: android.net.Uri,
        onprogress: (Float) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        val ws = socket ?: return@withContext false
        var name = "file"
        var size = -1L
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val s = c.getColumnIndex(OpenableColumns.SIZE)
                if (n >= 0) name = c.getString(n)
                if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
            }
        }
        val input = ctx.contentResolver.openInputStream(uri) ?: return@withContext false
        val id = fileid.getAndIncrement()
        ws.send(JSONObject().put("type", "file.offer").put("id", id).put("name", name).put("size", size).toString())
        onprogress(if (size > 0) 0f else -1f)
        var sent = 0L
        input.use { s ->
            val buf = ByteArray(5 + chunk)
            buf[0] = frame_file
            ByteBuffer.wrap(buf, 1, 4).putInt(id)
            while (true) {
                val n = s.read(buf, 5, chunk)
                if (n < 0) break
                while (ws.queueSize() > 4_000_000L) {
                    if (socket !== ws) return@withContext false
                    delay(5)
                }
                if (socket !== ws) return@withContext false
                ws.send(buf.toByteString(0, n + 5))
                sent += n
                onprogress(if (size > 0) (sent.toFloat() / size).coerceAtMost(1f) else -1f)
            }
        }
        ws.send(JSONObject().put("type", "file.done").put("id", id).toString())
        true
    }
}
