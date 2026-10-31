package app.proxylink

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer

class incoming(
    private val ctx: Context,
    private val onsaved: (name: String, mime: String, uri: Uri?) -> Unit,
) {
    private class tx(val name: String, val mime: String, val out: OutputStream, val uri: Uri?)

    private val active = HashMap<Int, tx>()

    private fun safename(raw: String): String =
        raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001f<>:\"|?*]"), "_")
            .trim().take(120).ifEmpty { "file" }

    @Synchronized
    fun offer(id: Int, rawname: String) {
        if (id < 0 || active.containsKey(id) || active.size >= 8) return
        val name = safename(rawname)
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/proxylink")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return
                val out = ctx.contentResolver.openOutputStream(uri) ?: return
                active[id] = tx(name, mime, out, uri)
            } else {
                val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
                dir.mkdirs()
                var f = File(dir, name)
                var n = 1
                while (f.exists()) f = File(dir, "${n++}_$name")
                active[id] = tx(name, mime, FileOutputStream(f), null)
            }
        }
    }

    @Synchronized
    fun chunk(arr: ByteArray) {
        if (arr.size < 6 || arr[0] != 3.toByte()) return
        val t = active[ByteBuffer.wrap(arr, 1, 4).int] ?: return
        runCatching { t.out.write(arr, 5, arr.size - 5) }
    }

    @Synchronized
    fun done(id: Int) {
        val t = active.remove(id) ?: return
        runCatching { t.out.close() }
        if (t.uri != null) {
            runCatching {
                ctx.contentResolver.update(
                    t.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null
                )
            }
        }
        onsaved(t.name, t.mime, t.uri)
    }

    @Synchronized
    fun abort() {
        active.values.forEach { runCatching { it.out.close() } }
        active.clear()
    }
}
