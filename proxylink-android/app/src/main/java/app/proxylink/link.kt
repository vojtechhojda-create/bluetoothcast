package app.proxylink

import android.content.Context
import android.provider.OpenableColumns
import android.util.Patterns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    private val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<linkstate>(linkstate.idle)
    val state = _state.asStateFlow()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var generation = 0
    private val fileid = AtomicInteger(1)

    @Volatile var onaudioconfig: ((Int, Int) -> Unit)? = null
    @Volatile var onaudioframe: ((ByteArray, Int, Int) -> Unit)? = null

    fun deviceid(ctx: Context): String {
        val p = ctx.getSharedPreferences("proxylink", Context.MODE_PRIVATE)
        return p.getString("deviceid", null) ?: UUID.randomUUID().toString().also {
            p.edit().putString("deviceid", it).apply()
        }
    }

    fun connect(host: String, port: Int, token: String, deviceid: String, devicename: String) {
        socket?.cancel()
        val gen = ++generation
        _state.value = linkstate.working("pairing")
        socket = client.newWebSocket(
            Request.Builder().url("ws://$host:$port").build(),
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    if (gen != generation) return
                    ws.send(
                        JSONObject()
                            .put("type", "auth.pair")
                            .put("token", token)
                            .put("deviceid", deviceid)
                            .put("devicename", devicename)
                            .toString()
                    )
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    if (gen != generation) return
                    val m = runCatching { JSONObject(text) }.getOrNull() ?: return
                    when (m.optString("type")) {
                        "auth.pair.ok" -> _state.value = linkstate.paired(m.optString("name"))
                        "auth.pair.pending" -> _state.value = linkstate.working("approve on pc")
                        "auth.pair.error", "auth.pair.denied", "error" ->
                            _state.value = linkstate.failed(m.optString("code", "rejected"))
                        "audio.config" -> onaudioconfig?.invoke(m.optInt("rate", 48000), m.optInt("channels", 2))
                    }
                }

                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    if (gen != generation || bytes.size < 2) return
                    if (bytes[0] == frame_audio) {
                        val arr = bytes.toByteArray()
                        onaudioframe?.invoke(arr, 1, arr.size - 1)
                    }
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    ws.close(1000, null)
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    if (gen != generation) return
                    socket = null
                    _state.value = when (_state.value) {
                        is linkstate.paired -> linkstate.idle
                        is linkstate.working -> linkstate.failed("connection closed")
                        else -> _state.value
                    }
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    if (gen != generation) return
                    socket = null
                    _state.value = linkstate.failed(t.message ?: "connection failed")
                }
            }
        )
    }

    fun working(msg: String) { _state.value = linkstate.working(msg) }

    fun fail(msg: String) { _state.value = linkstate.failed(msg) }

    fun disconnect() {
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
