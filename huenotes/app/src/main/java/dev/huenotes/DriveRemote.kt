package dev.huenotes

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Stores the sync file and attachment files in the user's hidden Google Drive "appDataFolder" (scope drive.appdata): invisible
 * in the Drive app and readable only by this app. [base] is injectable so tests can point it at a local fake server.
 */
class DriveRemote(private val token: String, private val base: String = "https://www.googleapis.com") : SyncRemote {
    private val docName = "huenotes-sync.json"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun open(url: String, method: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 60_000
        c.setRequestProperty("Authorization", "Bearer $token")
        // HttpURLConnection can't send PATCH; Google APIs honour the override header on POST.
        if (method == "PATCH") { c.requestMethod = "POST"; c.setRequestProperty("X-HTTP-Method-Override", "PATCH") } else c.requestMethod = method
        return c
    }

    private fun send(c: HttpURLConnection, contentType: String? = null, body: ByteArray? = null): ByteArray {
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", contentType!!)
            c.outputStream.use { it.write(body) }
        }
        val code = c.responseCode
        val bytes = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        if (code !in 200..299) throw IOException("Google Drive error $code${if (bytes.isNotEmpty()) ": " + bytes.toString(Charsets.UTF_8).take(200) else ""}")
        return bytes
    }

    private fun text(c: HttpURLConnection, contentType: String? = null, body: String? = null) =
        send(c, contentType, body?.toByteArray(Charsets.UTF_8)).toString(Charsets.UTF_8)

    private fun findId(name: String): String? {
        val q = enc("name='$name' and trashed=false")
        val files = JSONObject(text(open("$base/drive/v3/files?spaces=appDataFolder&q=$q&fields=files(id)&pageSize=1", "GET"))).optJSONArray("files")
        return if (files != null && files.length() > 0) files.getJSONObject(0).getString("id") else null
    }

    private fun create(name: String, mime: String, data: ByteArray) {
        val boundary = "huenotes" + System.nanoTime()
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put("appDataFolder")).toString()
        val out = ByteArrayOutputStream()
        out.write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$boundary\r\nContent-Type: $mime\r\n\r\n".toByteArray())
        out.write(data)
        out.write("\r\n--$boundary--".toByteArray())
        send(open("$base/upload/drive/v3/files?uploadType=multipart", "POST"), "multipart/related; boundary=$boundary", out.toByteArray())
    }

    override fun download(): String? {
        val id = findId(docName) ?: return null
        return text(open("$base/drive/v3/files/$id?alt=media", "GET"))
    }

    override fun upload(json: String) {
        val id = findId(docName)
        if (id == null) create(docName, "application/json", json.toByteArray(Charsets.UTF_8))
        else text(open("$base/upload/drive/v3/files/$id?uploadType=media", "PATCH"), "application/json", json)
    }

    override fun listBlobs(): List<BlobInfo> {
        val out = ArrayList<BlobInfo>()
        var page: String? = null
        val q = enc("name contains 'att-' and trashed=false")
        do {
            val url = "$base/drive/v3/files?spaces=appDataFolder&q=$q&fields=nextPageToken,files(id,name,createdTime)&pageSize=1000" +
                (page?.let { "&pageToken=${enc(it)}" } ?: "")
            val o = JSONObject(text(open(url, "GET")))
            o.optJSONArray("files")?.let { a ->
                for (i in 0 until a.length()) {
                    val f = a.getJSONObject(i); val n = f.getString("name")
                    if (n.startsWith("att-")) out += BlobInfo(n, parseTime(f.optString("createdTime")))
                }
            }
            page = o.optString("nextPageToken").ifEmpty { null }
        } while (page != null)
        return out
    }

    override fun downloadBlob(name: String): ByteArray? {
        val id = findId(name) ?: return null
        return send(open("$base/drive/v3/files/$id?alt=media", "GET"))
    }

    override fun uploadBlob(name: String, bytes: ByteArray, mime: String) { if (findId(name) == null) create(name, mime, bytes) }

    override fun deleteBlob(name: String) { findId(name)?.let { send(open("$base/drive/v3/files/$it", "DELETE")) } }

    /** Drive's RFC 3339 timestamp; 0 (treated as "old") if it can't be read. */
    private fun parseTime(s: String): Long {
        for (p in listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")) {
            try { return SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)!!.time } catch (_: Exception) { }
        }
        return 0
    }
}
