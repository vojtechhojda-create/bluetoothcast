package app.proxylink

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

class mainmodel(private val app: Application) : AndroidViewModel(app) {
    private val _scanning = MutableStateFlow(false)
    val scanning = _scanning.asStateFlow()

    private val _scanrequest = MutableStateFlow(false)
    val scanrequest = _scanrequest.asStateFlow()

    private val _audioon = MutableStateFlow(false)
    val audioon = _audioon.asStateFlow()

    private val _saved = MutableStateFlow(store.load(app))
    val saved = _saved.asStateFlow()

    private var job: Job? = null

    init {
        viewModelScope.launch {
            link.state.collect {
                _saved.value = store.load(app)
                if (it !is linkstate.paired) _audioon.value = false
            }
        }
        connectsaved()
    }

    private fun startservice() {
        runCatching { ContextCompat.startForegroundService(app, Intent(app, proxyservice::class.java)) }
    }

    fun connectsaved() {
        if (store.load(app) == null || link.state.value !is linkstate.idle) return
        startservice()
        link.connectsaved(app)
    }

    fun forget() {
        disconnect()
        store.clear(app)
        _saved.value = null
    }

    fun requestscan() { _scanrequest.value = true }
    fun clearscanrequest() { _scanrequest.value = false }
    fun startscan() { _scanning.value = true }
    fun cancelscan() { _scanning.value = false }

    fun disconnect() {
        job?.cancel()
        link.disconnect()
    }

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
        val fp = j?.optString("fp").orEmpty().lowercase()
        if (name.isEmpty() || port == 0 || token.isEmpty() || fp.length != 64) {
            link.fail("invalid qr")
            return
        }
        val hint = j?.optString("host").orEmpty()
        link.working("discovering $name")
        startservice()
        job?.cancel()
        job = viewModelScope.launch {
            val host = if (hint.isNotEmpty() && reachable(hint, port)) hint else discoverhost(app, name)
            if (host == null) {
                link.fail("pc not found on network")
                return@launch
            }
            link.pair(app, host, port, fp, token)
        }
    }
}
