package app.compendium.android

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The server answered with an error status (404 and so on). Read by the native side via getCode(). */
class HttpStatusException(val code: Int) : IOException("HTTP $code")

/**
 * What the native engine calls back into: progress, and HTTP on Android's own network stack
 * (certificates, proxies and so on come from the system).
 */
object Bridge {
    data class Progress(val text: String, val fraction: Float)

    /** Latest progress per task id. */
    val progress = MutableStateFlow<Map<String, Progress>>(emptyMap())

    @Volatile var userAgent: String = "Mozilla/5.0 (Linux; Android) Compendium"

    fun clearProgress(task: String) = progress.update { it - task }

    @JvmStatic
    fun onProgress(task: ByteArray, text: ByteArray, fraction: Float) {
        val id = String(task, Charsets.UTF_8)
        val p = Progress(String(text, Charsets.UTF_8), fraction)
        progress.update { it + (id to p) }
    }

    private fun str(b: ByteArray) = String(b, Charsets.UTF_8)

    /** Opens [url], following redirects by hand (HttpURLConnection won't cross http/https). */
    private fun open(url: String, method: String = "GET", range: String? = null): HttpURLConnection {
        var target = url
        repeat(8) {
            val c = URL(target).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", userAgent)
            c.setRequestProperty("Accept", "*/*")
            if (range != null) c.setRequestProperty("Range", range)
            val code = c.responseCode
            if (code in intArrayOf(301, 302, 303, 307, 308)) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc.isNullOrEmpty()) throw IOException("The server sent a redirect without a place to go")
                target = URL(URL(target), loc).toString()
            } else {
                return c
            }
        }
        throw IOException("Too many redirects")
    }

    @JvmStatic
    fun httpGet(url: ByteArray): ByteArray {
        val c = open(str(url))
        try {
            if (c.responseCode !in 200..299) throw HttpStatusException(c.responseCode)
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    @JvmStatic
    fun httpHead(url: ByteArray): Long {
        val c = open(str(url), "HEAD")
        try {
            if (c.responseCode !in 200..299) return -1
            return c.contentLengthLong
        } finally {
            c.disconnect()
        }
    }

    @JvmStatic
    fun httpRange(url: ByteArray, from: Long, to: Long): ByteArray {
        val c = open(str(url), "GET", "bytes=$from-$to")
        try {
            if (c.responseCode != 206) {
                if (c.responseCode in 200..299) throw IOException("The server doesn't support ranges")
                throw HttpStatusException(c.responseCode)
            }
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    @JvmStatic
    fun httpDownload(url: ByteArray, dest: ByteArray, handle: Long) {
        val path = str(dest)
        val part = File("$path.part")
        part.parentFile?.mkdirs()
        val c = open(str(url))
        try {
            if (c.responseCode !in 200..299) throw HttpStatusException(c.responseCode)
            val total = maxOf(c.contentLengthLong, 0L)
            var done = 0L
            var reported = 0L
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - reported >= 256 * 1024) {
                            reported = done
                            if (!Native.progress(handle, done, total)) throw IOException("Cancelled")
                        }
                    }
                }
            }
            if (!Native.progress(handle, done, if (total > 0) total else done)) throw IOException("Cancelled")
            val file = File(path)
            file.delete()
            if (!part.renameTo(file)) throw IOException("Couldn't save the download")
        } catch (e: Throwable) {
            part.delete()
            throw e
        } finally {
            c.disconnect()
        }
    }
}
