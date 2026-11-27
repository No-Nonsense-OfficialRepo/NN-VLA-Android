package com.nononsense.vla.share

import android.os.Environment
import java.io.BufferedInputStream
import java.io.IOException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class PhoneFileServer {
    private class ShareSession(val address: String, callback: (Int?) -> Unit) {
        val callbacks = mutableListOf(callback)
        val sockets = mutableSetOf<Socket>()
        var listener: ServerSocket? = null
    }

    private val lock = Any()
    private var session: ShareSession? = null
    private val clients = ThreadPoolExecutor(0, 12, 30, TimeUnit.SECONDS, SynchronousQueue()) { work ->
        Thread(work, "VLA file client").apply { isDaemon = true }
    }
    private val root: File get() = Environment.getExternalStorageDirectory().canonicalFile

    fun start(address: String, onReady: (Int?) -> Unit) {
        val started = synchronized(lock) {
            val current = session
            if (current?.address == address) {
                current.listener?.let { onReady(it.localPort); return }
                current.callbacks.add(onReady)
                return
            } else {
                closeSession()
                ShareSession(address, onReady).also { session = it }
            }
        }
        Thread({ listen(started) }, "VLA file share").apply { isDaemon = true }.start()
    }

    fun stop() = synchronized(lock) { closeSession() }

    private fun closeSession() {
        val previous = session ?: return
        session = null
        runCatching { previous.listener?.close() }
        previous.sockets.forEach { socket -> runCatching { socket.close() } }
        previous.sockets.clear()
    }

    private fun listen(current: ShareSession) {
        val listener = bind(current)
        val callbacks = synchronized(lock) {
            if (session !== current) {
                listener?.close()
                return
            }
            current.listener = listener
            if (listener == null) session = null
            current.callbacks.toList().also { current.callbacks.clear() }
        }
        callbacks.forEach { callback -> runCatching { callback(listener?.localPort) } }
        if (listener == null) return

        try {
            while (true) {
                val socket = listener.accept()
                synchronized(lock) {
                    if (session !== current) {
                        socket.close()
                        return
                    }
                    current.sockets.add(socket)
                }
                try {
                    clients.execute {
                        try { handle(current, socket) }
                        finally { synchronized(lock) { current.sockets.remove(socket) } }
                    }
                } catch (_: RejectedExecutionException) {
                    synchronized(lock) { current.sockets.remove(socket) }
                    socket.close()
                }
            }
        } catch (_: IOException) {
            // The listener is normally closed by stop().
        } finally {
            synchronized(lock) { if (session === current) closeSession() }
        }
    }

    private fun bind(current: ShareSession): ServerSocket? {
        repeat(10) {
            if (!isActive(current)) return null
            for (port in listOf(80, 47810)) {
                var listener: ServerSocket? = null
                try {
                    listener = ServerSocket()
                    listener.reuseAddress = true
                    listener.bind(InetSocketAddress(InetAddress.getByName(current.address), port), 16)
                    return listener
                } catch (_: IOException) {
                    runCatching { listener?.close() }
                } catch (_: SecurityException) {
                    runCatching { listener?.close() }
                }
            }
            Thread.sleep(500)
        }
        return null
    }

    private fun isActive(current: ShareSession) = synchronized(lock) { session === current }

    private fun handle(current: ShareSession, socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 30_000
                val input = BufferedInputStream(client.getInputStream())
                val output = client.getOutputStream()
                val request = readLine(input).split(' ')
                if (request.size < 2) return
                val method = request[0].uppercase(Locale.US)
                val path = request[1].substringBefore('?')
                val preview = request[1].substringAfter('?', "") == "preview=1"
                val headers = mutableMapOf<String, String>()
                var headerBytes = 0
                while (true) {
                    val line = readLine(input)
                    if (line.isEmpty()) break
                    headerBytes += line.length + 2
                    if (headerBytes > 64 * 1024) return respond(output, 431, "Headers too large")
                    val split = line.indexOf(':')
                    if (split > 0) {
                        val name = line.substring(0, split).lowercase(Locale.US)
                        if (name == "content-length" && name in headers) {
                            return respond(output, 400, "Duplicate Content-Length")
                        }
                        headers[name] = line.substring(split + 1).trim()
                    }
                }
                if (!isActive(current)) return
                val target = resolve(path) ?: return respond(output, 403, "Forbidden")
                if (method in WRITE_METHODS && !canWrite(target)) return respond(output, 403, "This folder is protected")
                when (method) {
                    "OPTIONS" -> respond(output, 200, "", mapOf("Allow" to "OPTIONS, GET, HEAD, PUT, DELETE, MKCOL, PROPFIND"))
                    "GET" -> get(output, target, preview, false)
                    "HEAD" -> get(output, target, preview, true)
                    "PUT" -> put(current, input, output, target, headers)
                    "DELETE" -> {
                        val deleted = try {
                            synchronized(lock) { session === current && canWrite(target) && Files.deleteIfExists(target.toPath()) }
                        } catch (_: IOException) { return respond(output, 409, "Could not delete the item. Folders must be empty.") }
                        if (deleted) respond(output, 204, "") else respond(output, 404, "Item not found")
                    }
                    "MKCOL" -> {
                        val made = synchronized(lock) { session === current && canWrite(target) && target.mkdir() }
                        if (made) respond(output, 201, "") else respond(output, 409, "Could not create folder")
                    }
                    "PROPFIND" -> propfind(output, target, path, headers["depth"] != "0")
                    else -> respond(output, 405, "Method not allowed")
                }
            } catch (_: Exception) {}
        }
    }

    private fun get(output: OutputStream, target: File, preview: Boolean, headOnly: Boolean) {
        if (!target.exists()) return respond(output, 404, "Not found")
        if (target.isDirectory) {
            if (preview) return respond(output, 404, "Image not found")
            val path = root.toPath().relativize(target.toPath()).filter { it.toString().isNotEmpty() }.map { it.toString() }
            val body = PhoneFileExplorer.page(path, visibleFiles(target)).toByteArray()
            writeHeaders(output, 200, body.size.toLong(), "text/html; charset=utf-8", mapOf(
                "Content-Security-Policy" to "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'",
            ))
            if (!headOnly) output.write(body)
            return
        }
        if (!target.isFile) return respond(output, 404, "File not found")
        val type = if (preview) PhoneFileExplorer.imageType(target.name) ?: return respond(output, 404, "Preview unavailable")
            else "application/octet-stream"
        val disposition = if (preview) "inline" else "attachment"
        writeHeaders(output, 200, target.length(), type, mapOf(
            "Content-Disposition" to "$disposition; filename*=UTF-8''${PhoneFileExplorer.encode(target.name)}",
            "Cross-Origin-Resource-Policy" to "same-origin",
        ))
        if (!headOnly) target.inputStream().use { it.copyTo(output) }
    }

    private fun put(current: ShareSession, input: InputStream, output: OutputStream, target: File, headers: Map<String, String>) {
        if ("transfer-encoding" in headers) return respond(output, 501, "Transfer-Encoding is unsupported")
        val rawLength = headers["content-length"] ?: return respond(output, 411, "Content-Length required")
        val length = rawLength.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }?.toLongOrNull()
            ?: return respond(output, 400, "Invalid Content-Length")
        val expectation = headers["expect"]
        if (expectation != null && !expectation.equals("100-continue", ignoreCase = true)) {
            return respond(output, 417, "Unsupported Expect header")
        }
        val parent = target.parentFile
        if (target == root || target.isDirectory || parent == null) return respond(output, 409, "Cannot replace a folder")
        val stage = try {
            synchronized(lock) {
                if (session !== current || !canWrite(target)) return
                if (parent.isDirectory) File.createTempFile(UPLOAD_PREFIX, ".tmp", parent)
                else null
            }
        } catch (_: IOException) {
            return respond(output, 500, "Could not stage upload")
        } ?: return respond(output, 409, "Could not create folder")
        try {
            if (expectation != null) {
                output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                output.flush()
            }
            stage.outputStream().use { destination ->
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) return respond(output, 400, "Incomplete upload")
                    destination.write(buffer, 0, count)
                    remaining -= count
                }
            }
            val existed = synchronized(lock) {
                if (session !== current || !canWrite(target)) return
                target.exists().also {
                    Files.move(stage.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            respond(output, if (existed) 204 else 201, "")
        } catch (_: IOException) {
            if (isActive(current)) runCatching { respond(output, 500, "Upload could not be saved") }
        } finally {
            stage.delete()
        }
    }

    private fun propfind(output: OutputStream, target: File, rawPath: String, includeChildren: Boolean) {
        if (!target.exists()) return respond(output, 404, "Not found")
        val entries = mutableListOf(target)
        if (includeChildren && target.isDirectory) entries += visibleFiles(target).sortedBy { it.name.lowercase() }
        val basePath = rawPath.trimEnd('/')
        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?><D:multistatus xmlns:D=\"DAV:\">")
            entries.forEach { file ->
                val href = if (file == target) rawPath else "$basePath/${PhoneFileExplorer.encode(file.name)}${if (file.isDirectory) "/" else ""}"
                append("<D:response><D:href>${escape(href)}</D:href><D:propstat><D:prop>")
                append("<D:displayname>${escape(file.name.ifBlank { "Phone" })}</D:displayname>")
                append("<D:resourcetype>${if (file.isDirectory) "<D:collection/>" else ""}</D:resourcetype>")
                append("<D:getcontentlength>${if (file.isFile) file.length() else 0}</D:getcontentlength>")
                append("<D:getlastmodified>${httpDate(file.lastModified())}</D:getlastmodified>")
                append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>")
            }
            append("</D:multistatus>")
        }
        respond(output, 207, xml, mapOf("Content-Type" to "application/xml; charset=utf-8"))
    }

    private fun resolve(rawPath: String): File? {
        if (!rawPath.startsWith('/')) return null
        val decoded = runCatching {
            URLDecoder.decode(rawPath.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        }.getOrNull() ?: return null
        val segments = decoded.split('/').filter(String::isNotEmpty)
        if (segments.any { it.startsWith('.') || '\\' in it || '\u0000' in it }) return null
        val target = segments.fold(root) { parent, name -> File(parent, name) }
        return target.takeIf(::isSharedPath)
    }

    private fun isSharedPath(file: File): Boolean = runCatching {
        val base = root.toPath()
        val path = file.toPath()
        if (path == base) return true
        if (!path.startsWith(base) || file.canonicalFile.toPath() != path) return false
        val relative = base.relativize(path)
        relative.getName(0).toString() in SHARED_FOLDERS &&
            relative.none { it.toString().startsWith('.') } &&
            (relative.nameCount > 1 || file.isDirectory)
    }.getOrDefault(false)

    private fun canWrite(target: File) =
        isSharedPath(target) && target.toPath().nameCount >= root.toPath().nameCount + 2

    private fun visibleFiles(directory: File): List<File> =
        directory.listFiles().orEmpty().filter(::isSharedPath)

    private fun readLine(input: InputStream): String {
        val bytes = ArrayList<Byte>()
        while (bytes.size < 16 * 1024) {
            val value = input.read()
            if (value < 0 || value == '\n'.code) break
            if (value != '\r'.code) bytes += value.toByte()
        }
        if (bytes.size == 16 * 1024) throw IOException("HTTP line too long")
        return bytes.toByteArray().toString(StandardCharsets.UTF_8)
    }

    private fun respond(output: OutputStream, status: Int, text: String, extra: Map<String, String> = emptyMap()) {
        val body = text.toByteArray()
        writeHeaders(output, status, body.size.toLong(), extra["Content-Type"] ?: "text/plain; charset=utf-8", extra - "Content-Type")
        output.write(body)
    }

    private fun writeHeaders(output: OutputStream, status: Int, length: Long, type: String, extra: Map<String, String> = emptyMap()) {
        val reason = when (status) {
            200 -> "OK"
            201 -> "Created"
            204 -> "No Content"
            207 -> "Multi-Status"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            411 -> "Length Required"
            417 -> "Expectation Failed"
            431 -> "Request Header Fields Too Large"
            501 -> "Not Implemented"
            else -> "Internal Server Error"
        }
        val headers = buildString {
            append("HTTP/1.1 $status $reason\r\nContent-Length: $length\r\nContent-Type: $type\r\nConnection: close\r\n")
            append("X-Content-Type-Options: nosniff\r\nCache-Control: no-store\r\n")
            extra.forEach { (key, value) -> append("$key: $value\r\n") }
            append("\r\n")
        }
        output.write(headers.toByteArray())
    }

    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun httpDate(value: Long): String = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date(value))

    companion object {
        private val WRITE_METHODS = setOf("PUT", "DELETE", "MKCOL")
        private val SHARED_FOLDERS = setOf("DCIM", "Pictures", "Download", "Documents", "Movies", "Music", "Recordings", "Screenshots")
        private const val UPLOAD_PREFIX = ".nn-vla-upload-"
    }
}
