package app.proxylink

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

class audioplayer {
    @Volatile private var track: AudioTrack? = null

    @Synchronized
    fun start(rate: Int, channels: Int) {
        stop()
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
            .setBufferSizeInBytes(min * 2)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        track = t
    }

    fun write(buf: ByteArray, off: Int, len: Int) {
        track?.write(buf, off, len, AudioTrack.WRITE_NON_BLOCKING)
    }

    @Synchronized
    fun stop() {
        track?.let {
            runCatching { it.pause(); it.flush(); it.release() }
        }
        track = null
    }
}
