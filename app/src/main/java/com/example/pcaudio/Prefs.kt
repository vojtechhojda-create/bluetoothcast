package com.example.pcaudio

import android.content.Context
import java.security.SecureRandom

/** Malé úložiště nastavení: PIN a volba automatického povolení. */
object Prefs {
    private fun sp(c: Context) =
        c.getSharedPreferences("bluetoothcast", Context.MODE_PRIVATE)

    fun pin(c: Context): String = sp(c).getString("pin", null) ?: newPin(c)

    fun newPin(c: Context): String {
        val p = String.format("%06d", SecureRandom().nextInt(1_000_000))
        sp(c).edit().putString("pin", p).apply()
        return p
    }

    fun autoApprove(c: Context): Boolean = sp(c).getBoolean("auto_approve", false)

    fun setAutoApprove(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("auto_approve", v).apply()
    }
}
