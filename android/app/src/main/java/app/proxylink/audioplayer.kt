package app.proxylink

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build

data class outdev(val key: String, val label: String)

class audioplayer(private val ctx: Context) {
    @Volatile private var track: AudioTrack? = null

    companion object {
        private fun allowed(t: Int): Boolean =
            t == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                t == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                t == AudioDeviceInfo.TYPE_USB_HEADSET ||
                t == AudioDeviceInfo.TYPE_USB_DEVICE ||
                t == AudioDeviceInfo.TYPE_HEARING_AID ||
                (Build.VERSION.SDK_INT >= 31 && t == AudioDeviceInfo.TYPE_BLE_HEADSET)

        private fun devices(ctx: Context): List<AudioDeviceInfo> =
            ctx.getSystemService(AudioManager::class.java)
                .getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { allowed(it.type) }

        private fun keyof(d: AudioDeviceInfo) = "${d.type}:${d.productName}"

        private fun labelof(d: AudioDeviceInfo): String {
            val kind = when (d.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "headphones"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth"
                AudioDeviceInfo.TYPE_HEARING_AID -> "hearing aid"
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "usb"
                else -> "bluetooth"
            }
            val name = d.productName?.toString().orEmpty()
            return if (name.isBlank() || kind == "speaker") kind else "$kind: $name"
        }

        fun outputs(ctx: Context): List<outdev> =
            devices(ctx).map { outdev(keyof(it), labelof(it)) }.distinctBy { it.key }
    }

    @Synchronized
    fun start(rate: Int, channels: Int) {
        stop()
        val prefs = ctx.getSharedPreferences("proxylink", Context.MODE_PRIVATE)
        val mult = prefs.getInt("buf_mult", 2).coerceIn(1, 8)
        val want = prefs.getString("out_key", "").orEmpty()
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(mask)
                    .build()
            )
            .setBufferSizeInBytes(min * mult)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (want.isNotEmpty()) {
            devices(ctx).firstOrNull { keyof(it) == want }?.let { t.setPreferredDevice(it) }
        }
        t.play()
        track = t
    }

    fun write(buf: ByteArray, off: Int, len: Int) {
        track?.write(buf, off, len, AudioTrack.WRITE_NON_BLOCKING)
    }

    @Synchronized
    fun stop() {
        track?.let { runCatching { it.pause(); it.flush(); it.release() } }
        track = null
    }
}
