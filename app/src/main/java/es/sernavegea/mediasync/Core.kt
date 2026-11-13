package es.sernavegea.mediasync

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File
import java.security.MessageDigest

/** Ajustes guardados en el móvil. */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("mediasync", Context.MODE_PRIVATE)

    var server: String
        get() = p.getString("server", "http://192.168.1.242:8080")!!
        set(v) = p.edit().putString("server", v).apply()
    var token: String?
        get() = p.getString("token", null)
        set(v) = p.edit().putString("token", v).apply()
    var name: String
        get() = p.getString("name", "")!!
        set(v) = p.edit().putString("name", v).apply()
    var onlyWifi: Boolean
        get() = p.getBoolean("onlyWifi", true)
        set(v) = p.edit().putBoolean("onlyWifi", v).apply()
    var autoSync: Boolean
        get() = p.getBoolean("autoSync", false)
        set(v) = p.edit().putBoolean("autoSync", v).apply()
    var lastSync: Long
        get() = p.getLong("lastSync", 0L)
        set(v) = p.edit().putLong("lastSync", v).apply()
    var lastResult: String
        get() = p.getString("lastResult", "")!!
        set(v) = p.edit().putString("lastResult", v).apply()
    var excluded: String
        get() = p.getString("excluded", "")!!
        set(v) = p.edit().putString("excluded", v).apply()

    fun isEnabled(cat: String) = p.getBoolean("cat_$cat", true)
    fun setEnabled(cat: String, v: Boolean) = p.edit().putBoolean("cat_$cat", v).apply()
}

/** Qué se sincroniza según la extensión (el servidor hace la clasificación fina). */
object Categorias {
    const val FOTOS = "Fotos"
    const val VIDEOS = "Vídeos"
    const val AUDIO = "Audio"
    const val DOCS = "Documentos"

    private val fotos = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "dng", "bmp", "avif")
    private val videos = setOf("mp4", "mov", "3gp", "mkv", "webm", "avi", "m4v")
    private val audio = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "amr", "wma", "3ga")
    private val docs = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "txt",
        "csv", "rtf", "epub", "zip", "rar", "7z", "apk", "vcf", "md"
    )

    fun of(fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in fotos -> FOTOS
            in videos -> VIDEOS
            in audio -> AUDIO
            in docs -> DOCS
            else -> null
        }
    }
}

/** Registro local de lo ya enviado, para no volver a calcular hashes de todo cada vez. */
class SentDb(ctx: Context) : SQLiteOpenHelper(ctx, "sent.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sent(path TEXT PRIMARY KEY, size INTEGER, mtime INTEGER, sha TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {}

    fun isSent(path: String, size: Long, mtime: Long): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM sent WHERE path=? AND size=? AND mtime=?",
            arrayOf(path, size.toString(), mtime.toString())
        ).use { it.moveToFirst() }

    fun mark(path: String, size: Long, mtime: Long, sha: String) {
        val v = ContentValues().apply {
            put("path", path); put("size", size); put("mtime", mtime); put("sha", sha)
        }
        writableDatabase.insertWithOnConflict("sent", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun count(): Long = DatabaseUtils.queryNumEntries(readableDatabase, "sent")
    fun clear() = writableDatabase.execSQL("DELETE FROM sent")
}

object Perms {
    fun hasAllFiles(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
}

data class Candidate(val file: File, val rel: String)

object Scanner {
    /** Recorre el almacenamiento y devuelve los archivos que tocan según los ajustes. */
    fun scan(root: File, prefs: Prefs): List<Candidate> {
        val excluded = prefs.excluded.lines()
            .map { it.trim().trim('/').lowercase() }
            .filter { it.isNotEmpty() }
        val enabled = listOf(Categorias.FOTOS, Categorias.VIDEOS, Categorias.AUDIO, Categorias.DOCS)
            .filter { prefs.isEnabled(it) }.toSet()
        val rootPath = root.absolutePath
        val out = ArrayList<Candidate>()
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (c in children) {
                val name = c.name
                if (name.startsWith(".")) continue // ocultos, .thumbnails, .trashed, .Statuses...
                val rel = c.absolutePath.removePrefix(rootPath).trimStart('/')
                val relLow = rel.lowercase()
                if (c.isDirectory) {
                    if (relLow == "android/data" || relLow == "android/obb") continue
                    if (excluded.any { relLow == it || relLow.startsWith("$it/") }) continue
                    stack.add(c)
                } else if (c.isFile && c.length() > 0) {
                    val cat = Categorias.of(name) ?: continue
                    if (cat !in enabled) continue
                    out.add(Candidate(c, "/$rel"))
                }
            }
        }
        return out
    }
}

fun sha256(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { s ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    val hex = "0123456789abcdef"
    val sb = StringBuilder(64)
    for (b in md.digest()) {
        val i = b.toInt() and 0xff
        sb.append(hex[i shr 4]).append(hex[i and 0x0f])
    }
    return sb.toString()
}

fun normalizeServer(input: String): String {
    var s = input.trim().trimEnd('/')
    if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
    if (!Regex(":\\d+$").containsMatchIn(s.substringAfter("://"))) s = "$s:8080"
    return s
}
