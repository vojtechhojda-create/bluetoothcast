package app.proxylink

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent

class copyreceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val t = intent.getStringExtra("text") ?: return
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("proxylink", t))
    }
}
