package dev.huenotes

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Stores the sync file in the user's hidden Google Drive "appDataFolder" (scope drive.appdata): invisible in the Drive app
 * and readable only by this app. [base] is injectable so tests can point it at a local fake server.
 */
class DriveRemote(private val token: String, private val base: String = "https://www.googleapis.com") : SyncRemote {
    private val name = "huenotes-sync.json"

    private fun open(url: String, method: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("Authorization", "Bearer $token")
        // HttpURLConnection can't send PATCH; Google APIs honour the override header on POST.
        if (method == "PATCH") { c.requestMethod = "POST"; c.setRequestProperty("X-HTTP-Method-Override", "PATCH") } else c.requestMethod = method
        return c
    }

    private fun send(c: HttpURLConnection, contentType: String? = null, body: String? = null): String {
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", contentType!!)
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        if (code !in 200..299) throw IOException("Google Drive error $code${if (text.isNotBlank()) ": " + text.take(200) else ""}")
        return text
    }

    private fun findId(): String? {
        val q = URLEncoder.encode("name='$name' and trashed=false", "UTF-8")
        val c = open("$base/drive/v3/files?spaces=appDataFolder&q=$q&fields=files(id)&pageSize=1", "GET")
        val files = JSONObject(send(c)).optJSONArray("files")
        return if (files != null && files.length() > 0) files.getJSONObject(0).getString("id") else null
    }

    override fun download(): String? {
        val id = findId() ?: return null
        return send(open("$base/drive/v3/files/$id?alt=media", "GET"))
    }

    override fun upload(json: String) {
        val id = findId()
        if (id == null) {
            val boundary = "huenotes" + System.nanoTime()
            val meta = JSONObject().put("name", name).put("parents", org.json.JSONArray().put("appDataFolder")).toString()
            val body = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
                "--$boundary\r\nContent-Type: application/json\r\n\r\n$json\r\n--$boundary--"
            send(open("$base/upload/drive/v3/files?uploadType=multipart", "POST"), "multipart/related; boundary=$boundary", body)
        } else {
            send(open("$base/upload/drive/v3/files/$id?uploadType=media", "PATCH"), "application/json", json)
        }
    }
}
