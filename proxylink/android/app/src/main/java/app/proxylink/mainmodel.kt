package app.proxylink

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

class mainmodel(private val app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("proxylink", Context.MODE_PRIVATE)

    private val _scanning = MutableStateFlow<Boolean>(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _scanrequest = MutableStateFlow(false)
    val scanrequest: StateFlow<Boolean> = _scanrequest.asStateFlow()

    val audioon: StateFlow<Boolean> = link.audioin

    private val _saved = MutableStateFlow<pc?>(store.load(app))
    val saved: StateFlow<pc?> = _saved.asStateFlow()

    private val _source = MutableStateFlow<Int>(0)
    val source: StateFlow<Int> = _source.asStateFlow()

    private val _outputs = MutableStateFlow<List<outdev>>(audioplayer.listoutputs(app))
    val outputs: StateFlow<List<outdev>> = _outputs.asStateFlow()

    private val _outkey = MutableStateFlow<String>(prefs.getString("out_key", "").orEmpty())
    val outkey: StateFlow<String> = _outkey.asStateFlow()

    private val _bufmult = MutableStateFlow<Int>(prefs.getInt("buf_mult", 2).coerceIn(1, 8))
    val bufmult: StateFlow<Int> = _bufmult.asStateFlow()

    private var job: Job? = null

    private val devicecb = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<AudioDeviceInfo>) { _outputs.value = audioplayer.listoutputs(app) }
        override fun onAudioDevicesRemoved(removed: Array<AudioDeviceInfo>) { _outputs.value = audioplayer.listoutputs(app) }
    }

    init {
        app.getSystemService(AudioManager::class.java).registerAudioDeviceCallback(devicecb, null)
        viewModelScope.launch {
            link.state.collect {
                _saved.value = store.load(app)
                if (it !is linkstate.paired) {
                    link.resetaudio()
                    link.clearapps()
                }
            }
        }
        connectsaved()
    }

    override fun onCleared() {
        app.getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(devicecb)
        super.onCleared()
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
        if (link.audioin.value) link.audiostop() else link.audiostart(_source.value)
    }

    fun refreshapps() { link.listapps() }

    fun setsource(pid: Int) {
        _source.value = pid
        if (link.audioin.value) link.audiostart(pid)
    }

    fun setoutput(key: String) {
        prefs.edit().putString("out_key", key).apply()
        _outkey.value = key
        if (link.audioin.value) link.audiostart(_source.value)
    }

    fun setbuf(n: Int) {
        prefs.edit().putInt("buf_mult", n).apply()
        _bufmult.value = n
        if (link.audioin.value) link.audiostart(_source.value)
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
