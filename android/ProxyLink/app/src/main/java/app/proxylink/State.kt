package app.proxylink

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.CRC32

enum class Phase { OFF, LISTENING, PAIRING, STREAMING }

/**
 * Rezimy latence. PC dostane chunkMs a pcBufferMs v odpovedi "ok".
 * prefillMs = kolik zvuku se nahromadi, nez se zacne prehravat,
 * trackMs = velikost bufferu AudioTracku, maxQueueMs = nad touto hodnotou se stary zvuk zahodi.
 */
enum class Latency(
    val label: String,
    val hint: String,
    val chunkMs: Int,
    val pcBufferMs: Int,
    val prefillMs: Int,
    val trackMs: Int,
    val maxQueueMs: Int,
) {
    LOW("Nízká", "Nejmenší zpoždění. Citlivější na slabou Wi-Fi.", 10, 60, 20, 40, 80),
    BALANCED("Vyvážená", "Dobrý kompromis mezi zpožděním a plynulostí.", 20, 120, 60, 80, 200),
    STABLE("Stabilní", "Největší rezerva, bez lupanců, ale zvuk je opožděný.", 40, 300, 150, 200, 500),
}

/** Sdileny stav mezi sluzbou a oknem. */
object ProxyState {
    @Volatile var phase = Phase.OFF
    @Volatile var pcName = ""
    @Volatile var pairCode = ""
    @Volatile var kbps = 0
    @Volatile var latencyMs = 0
    @Volatile var playing = false

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }
    fun changed() { main.post { listeners.forEach { it() } } }
}

object Prefs {
    fun get(c: Context): SharedPreferences = c.getSharedPreferences("proxylink", Context.MODE_PRIVATE)

    fun latency(c: Context): Latency =
        Latency.values().getOrElse(get(c).getInt("latency", 1)) { Latency.BALANCED }
}

/** Barva z jmena zarizeni - stejny vzorec jako na PC: HLS(CRC32(jmeno) % 360, 0.58, 0.65). */
object Palette {
    fun accent(name: String): Int {
        val crc = CRC32()
        crc.update((if (name.isEmpty()) "ProxyLink" else name).toByteArray(Charsets.UTF_8))
        val hue = (crc.value % 360).toFloat() / 360f
        return hls(hue, 0.58f, 0.65f)
    }

    private fun channel(m1: Float, m2: Float, h0: Float): Float {
        var h = h0 % 1f
        if (h < 0f) h += 1f
        return when {
            h < 1f / 6f -> m1 + (m2 - m1) * h * 6f
            h < 0.5f -> m2
            h < 2f / 3f -> m1 + (m2 - m1) * (2f / 3f - h) * 6f
            else -> m1
        }
    }

    private fun hls(h: Float, l: Float, s: Float): Int {
        val m2 = if (l <= 0.5f) l * (1f + s) else l + s - l * s
        val m1 = 2f * l - m2
        fun px(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
        return Color.rgb(
            px(channel(m1, m2, h + 1f / 3f)),
            px(channel(m1, m2, h)),
            px(channel(m1, m2, h - 1f / 3f)),
        )
    }
}
