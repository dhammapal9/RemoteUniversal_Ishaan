package com.idp.universalremote.data.cast

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimal embedded HTTP/1.1 server that exposes content://-scheme media items to
 * the local network so a DLNA/Chromecast TV can stream them.
 *
 * Why hand-rolled and not OkHttp's MockWebServer or NanoHTTPD?
 *   - MockWebServer is in test-only artifacts and meant for unit testing.
 *   - NanoHTTPD pulls in ~80 KB and a separate dependency.
 *   - We only need GET + Range — about ~100 lines of code.
 *
 * Supports HTTP Range requests, which is mandatory: every smart TV's video
 * player issues seek-forward Range requests, and replying without
 * `Content-Range` produces a "cannot play" error on Samsung/LG/Sony.
 */
class HttpFileServer(private val contentResolver: ContentResolver) {

    private data class Entry(val uri: Uri, val mime: String, val size: Long)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val nextId = AtomicLong(1L)
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "HttpFileServer-${nextThreadId.incrementAndGet()}").apply { isDaemon = true }
    }
    private var serverSocket: ServerSocket? = null

    @Volatile var port: Int = -1
        private set

    /** Bind to an ephemeral port on all interfaces. */
    fun start(): Int {
        if (serverSocket != null) return port
        val s = ServerSocket(0)
        serverSocket = s
        port = s.localPort
        pool.submit { acceptLoop(s) }
        Log.d(TAG, "HttpFileServer listening on port $port")
        return port
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        entries.clear()
        pool.shutdownNow()
    }

    /** Register [uri] for streaming; returns the public path component (no host). */
    fun register(uri: Uri, mime: String): String {
        val size = runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: -1L
        val id = nextId.getAndIncrement().toString(36)
        entries[id] = Entry(uri, mime, size)
        return "/cast/$id"
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val client = runCatching { s.accept() }.getOrNull() ?: break
            pool.submit { handle(client) }
        }
    }

    private fun handle(socket: Socket) {
        socket.use {
            val reader = BufferedReader(InputStreamReader(it.getInputStream()))
            val out = it.getOutputStream()
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2 || parts[0] != "GET") {
                writeStatus(out, 405, "Method Not Allowed"); return
            }
            val path = parts[1]
            var rangeHeader: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) rangeHeader = line.substringAfter(':').trim()
            }
            val id = path.removePrefix("/cast/")
            val entry = entries[id]
            if (entry == null) {
                writeStatus(out, 404, "Not Found"); return
            }
            serveEntry(out, entry, rangeHeader)
        }
    }

    private fun serveEntry(out: OutputStream, entry: Entry, rangeHeader: String?) {
        val total = entry.size
        val (start, end) = parseRange(rangeHeader, total)
        val isPartial = rangeHeader != null && total > 0
        val length = if (total > 0) end - start + 1 else -1L
        val headers = buildString {
            append("HTTP/1.1 ${if (isPartial) 206 else 200} ${if (isPartial) "Partial Content" else "OK"}\r\n")
            append("Content-Type: ${entry.mime}\r\n")
            if (length >= 0) append("Content-Length: $length\r\n")
            if (isPartial && total > 0) append("Content-Range: bytes $start-$end/$total\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(headers.toByteArray())

        runCatching {
            contentResolver.openInputStream(entry.uri)?.use { input ->
                if (start > 0) input.skip(start)
                val buf = ByteArray(64 * 1024)
                var remaining = if (length >= 0) length else Long.MAX_VALUE
                while (remaining > 0) {
                    val read = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (read <= 0) break
                    out.write(buf, 0, read)
                    remaining -= read
                }
                out.flush()
            }
        }.onFailure { Log.w(TAG, "stream failed: ${it.message}") }
    }

    private fun parseRange(header: String?, total: Long): Pair<Long, Long> {
        if (header == null || total <= 0) return 0L to (total - 1)
        // "bytes=START-END" or "bytes=START-"
        val spec = header.removePrefix("bytes=").trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return 0L to (total - 1)
        val start = spec.substring(0, dash).toLongOrNull() ?: 0L
        val end = spec.substring(dash + 1).ifEmpty { (total - 1).toString() }.toLongOrNull() ?: (total - 1)
        return start to end.coerceAtMost(total - 1)
    }

    private fun writeStatus(out: OutputStream, code: Int, msg: String) {
        out.write("HTTP/1.1 $code $msg\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
    }

    companion object {
        private const val TAG = "HttpFileServer"
        private val nextThreadId = AtomicLong(0)
    }
}
