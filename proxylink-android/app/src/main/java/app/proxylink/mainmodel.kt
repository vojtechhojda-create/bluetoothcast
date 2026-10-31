package app.proxylink

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

class mainmodel(app: Application) : AndroidViewModel(app) {
    private val _scanning = MutableStateFlow(false)
    val scanning = _scanning.asStateFlow()

    private val _scanrequest = MutableStateFlow(false)
    val scanrequest = _scanrequest.asStateFlow()

    private val _audioon = MutableStateFlow(false)
    val audioon = _audioon.asStateFlow()

    init {
        viewModelScope.launch {
            link.state.collect { if (it !is linkstate.paired) _audioon.value = false }
        }
    }

    fun requestscan() { _scanrequest.value = true }
    fun clearscanrequest() { _scanrequest.value = false }
    fun startscan() { _scanning.value = true }
    fun cancelscan() { _scanning.value = false }
    fun disconnect() = link.disconnect()

    fun toggleaudio() {
        val on = !_audioon.value
        if (link.request(if (on) "audio.start" else "audio.stop")) _audioon.value = on
    }

    fun media(action: String) { link.media(action) }

    fun onqr(raw: String) {
        _scanning.value = false
        val j = runCatching { JSONObject(raw) }.getOrNull()
        val name = j?.optString("name").orEmpty()
        val port = j?.optInt("port", 0) ?: 0
        val token = j?.optString("token").orEmpty()
        if (name.isEmpty() || port == 0 || token.isEmpty()) {
            link.fail("invalid qr")
            return
        }
        val hint = j?.optString("host").orEmpty()
        val ctx = getApplication<Application>()
        link.working("discovering $name")
        runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, proxyservice::class.java)) }
        viewModelScope.launch {
            val host = hint.ifEmpty { discoverhost(ctx, name) }
            if (host == null) {
                link.fail("pc not found on network")
                return@launch
            }
            link.connect(host, port, token, link.deviceid(ctx), "${Build.MANUFACTURER} ${Build.MODEL}")
        }
    }
}
