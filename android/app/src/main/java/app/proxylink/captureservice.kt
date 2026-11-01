package app.proxylink

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class captureservice : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var rec: AudioRecord? = null
    private var proj: MediaProjection? = null
    private var reader: Job? = null
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == "stop") {
            link.uplinkstop()
            stopSelf()
            return START_NOT_STICKY
        }
        val mode = intent.getStringExtra("mode") ?: "mic"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("capture", "sending audio", NotificationManager.IMPORTANCE_LOW))
        val type = when {
            mode == "phone" -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            Build.VERSION.SDK_INT >= 30 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else -> 0
        }
        ServiceCompat.startForeground(this, 2, notification(mode), if (Build.VERSION.SDK_INT >= 29) type else 0)
        begin(mode, intent)
        return START_NOT_STICKY
    }

    private fun fail(msg: String) {
        link.note(msg)
        link.uplinkstop()
        stopSelf()
    }

    private fun cleanup() {
        reader?.cancel()
        watcher?.cancel()
        rec?.let { runCatching { it.stop() }; it.release() }
        rec = null
        proj?.stop()
        proj = null
    }

    private fun begin(mode: String, intent: Intent) {
        cleanup()
        if (link.state.value !is linkstate.paired) return fail("not connected")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return fail("microphone permission missing")
        }
        val channels = if (mode == "phone") 2 else 1
        val mask = if (mode == "phone") AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val fmt = AudioFormat.Builder()
            .setSampleRate(48000)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(mask)
            .build()
        val minbuf = AudioRecord.getMinBufferSize(48000, mask, AudioFormat.ENCODING_PCM_16BIT)
        val size = maxOf(minbuf * 2, 16384)

        val r = try {
            if (mode == "phone") {
                if (Build.VERSION.SDK_INT < 29) return fail("phone audio needs android 10")
                val data = IntentCompat.getParcelableExtra(intent, "data", Intent::class.java)
                    ?: return fail("no capture permission")
                val mp = getSystemService(MediaProjectionManager::class.java)
                    .getMediaProjection(intent.getIntExtra("code", 0), data)
                mp.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        link.uplinkstop()
                        stopSelf()
                    }
                }, null)
                proj = mp
                val cfg = AudioPlaybackCaptureConfiguration.Builder(mp)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .excludeUid(Process.myUid())
                    .build()
                AudioRecord.Builder().setAudioFormat(fmt).setAudioPlaybackCaptureConfig(cfg).setBufferSizeInBytes(size).build()
            } else {
                AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC).setAudioFormat(fmt).setBufferSizeInBytes(size).build()
            }
        } catch (e: Exception) {
            return fail("capture failed: ${e.message}")
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return fail("capture not available")
        }
        rec = r

        link.uplinkstart(mode, 48000, channels)
        r.startRecording()
        reader = scope.launch {
            val chunk = ByteArray(if (channels == 2) 3840 else 1920)
            while (isActive) {
                val n = r.read(chunk, 0, chunk.size)
                if (n < 0) break
                if (n > 0) link.sendaudio(chunk, n)
            }
        }
        watcher = scope.launch(Dispatchers.Main) {
            launch { link.uplink.collect { if (it == null) stopSelf() } }
            launch { link.state.collect { if (it !is linkstate.paired) stopSelf() } }
        }
    }

    override fun onDestroy() {
        link.uplinkstop()
        cleanup()
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(mode: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, mainactivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 3, Intent(this, captureservice::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, "capture")
            .setSmallIcon(R.drawable.ic_link)
            .setContentTitle("proxylink")
            .setContentText(if (mode == "phone") "sending phone audio to pc" else "sending phone mic to pc")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "stop", stop)
            .build()
    }
}
