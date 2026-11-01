package app.proxylink

import android.content.Context

data class pc(val name: String, val host: String, val port: Int, val fp: String)

object store {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("proxylink", Context.MODE_PRIVATE)

    fun load(ctx: Context): pc? {
        val p = prefs(ctx)
        val name = p.getString("pc_name", null) ?: return null
        val host = p.getString("pc_host", null) ?: return null
        val fp = p.getString("pc_fp", null) ?: return null
        val port = p.getInt("pc_port", 0)
        return if (port == 0) null else pc(name, host, port, fp)
    }

    fun save(ctx: Context, v: pc) {
        prefs(ctx).edit()
            .putString("pc_name", v.name)
            .putString("pc_host", v.host)
            .putInt("pc_port", v.port)
            .putString("pc_fp", v.fp)
            .apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove("pc_name").remove("pc_host").remove("pc_port").remove("pc_fp").apply()
    }
}
