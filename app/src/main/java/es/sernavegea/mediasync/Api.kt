package es.sernavegea.mediasync

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, msg: String) : Exception(msg)

/** Cliente del servidor MediaSync (Node). */
class Api(private val base: String, private val token: String?) {

    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.MINUTES)
            .build()
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val BIN = "application/octet-stream".toMediaType()
    }

    private fun req(path: String): Request.Builder {
        val b = Request.Builder().url(base + path)
        token?.let { b.header("Authorization", "Bearer $it") }
        return b
    }

    private fun exec(r: Request): JSONObject {
        client.newCall(r).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            val j = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
            if (!resp.isSuccessful) throw ApiException(resp.code, j.optString("error", "Error ${resp.code}"))
            return j
        }
    }

    fun ping(): JSONObject {
        val j = exec(req("/api/ping").get().build())
        if (j.optString("app") != "MediaSync Familia") throw ApiException(0, "Esa dirección no es un servidor MediaSync")
        return j
    }

    fun login(user: String, pass: String, device: String): JSONObject {
        val body = JSONObject().put("username", user).put("password", pass).put("device", device)
        return exec(req("/api/login").post(body.toString().toRequestBody(JSON)).build())
    }

    fun check(hashes: List<String>): Set<String> {
        if (hashes.isEmpty()) return emptySet()
        val body = JSONObject().put("sha256", JSONArray(hashes))
        val j = exec(req("/api/sync/check").post(body.toString().toRequestBody(JSON)).build())
        val arr = j.optJSONArray("existing") ?: JSONArray()
        return (0 until arr.length()).map { arr.getString(it) }.toSet()
    }

    /** Sube un archivo en streaming (no se carga en memoria). Devuelve "ok" o "duplicado". */
    fun upload(f: File, originalPath: String, sha: String, device: String): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("originalPath", originalPath)
            .addFormDataPart("mtime", f.lastModified().toString())
            .addFormDataPart("sha256", sha)
            .addFormDataPart("device", device)
            .addFormDataPart("file", f.name, f.asRequestBody(BIN))
            .build()
        return exec(req("/api/upload").post(body).build()).optString("status", "ok")
    }
}
