package com.nononsense.vla.discovery

import android.net.Network
import com.nononsense.vla.admission.SignedRoomJson
import com.nononsense.vla.model.RoomKey
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

data class RoomAdmissionMetadata(val port: Int, val fingerprint: String)

data class RoomBeacon(
    val roomKey: String,
    val name: String,
    val version: String,
    val protocol: Int,
    val presenceAddress: String,
    val presencePort: Int,
    val hostOverlayAddress: String?,
    val hostPublicKey: String,
    val admission: RoomAdmissionMetadata?,
)

typealias DiscoveredHost = RoomBeacon

object RoomMetadata {
    private const val PORT = 47802
    private const val MAX_HEADER_BYTES = 8 * 1024
    private const val MAX_BODY_BYTES = 65_536
    private const val MAX_AGE_SECONDS = 30L
    private val RELEASE = Regex("[Vv]?\\d+\\.\\d+\\.\\d+")
    private val ROOM_ID = Regex("[0-9a-fA-F]{16}")
    private val FINGERPRINT = Regex("[0-9a-f]{64}")
    private val STATUS_OK = Regex("HTTP/1\\.[01] 200(?: .*)?")

    /**
     * Fetches and verifies the Host's signed metadata over its bootstrap endpoint.
     * When a signing key is already known, require that exact key.
     */
    fun fetch(
        address: String,
        expectedRoomKey: String? = null,
        network: Network? = null,
        timeoutMs: Int = 2_000,
        expectedHostKey: String? = null,
        onSocket: ((Socket) -> Unit)? = null,
    ): RoomBeacon {
        require(isIpv4(address)) { "Room Host address must be an IPv4 address." }
        require(timeoutMs > 0)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
        return Socket().use { socket ->
            onSocket?.invoke(socket)
            network?.bindSocket(socket)
            socket.connect(InetSocketAddress(address, PORT), timeLeft(deadline))
            socket.getOutputStream().apply {
                write("GET /room HTTP/1.0\r\n\r\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
            val input = BufferedInputStream(socket.getInputStream())
            val header = readHttpHeader(input, socket, deadline)
            val lines = header.split("\r\n")
            if (!STATUS_OK.matches(lines.first())) throw IOException("Host did not serve room metadata.")
            val rawLengths = lines.drop(1).filter { it.substringBefore(':').equals("Content-Length", ignoreCase = true) }
            val length = rawLengths.singleOrNull()?.substringAfter(':')?.trim()?.toIntOrNull()
            if (length == null || length !in 1..MAX_BODY_BYTES) throw IOException("Invalid room metadata size.")
            val body = ByteArray(length)
            var offset = 0
            while (offset < body.size) {
                socket.soTimeout = timeLeft(deadline)
                val read = input.read(body, offset, body.size - offset)
                if (read < 0) throw IOException("Incomplete room metadata.")
                offset += read
            }
            parse(body, address, expectedRoomKey, expectedHostKey)
        }
    }

    internal fun parse(
        bytes: ByteArray,
        address: String,
        expectedRoomKey: String? = null,
        expectedHostKey: String? = null,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): RoomBeacon {
        require(isIpv4(address))
        if (bytes.size !in 1..MAX_BODY_BYTES) throw IOException("Invalid room metadata size.")
        val envelope = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (error: JSONException) {
            throw IOException("Invalid room metadata.", error)
        }
        val hostKey = envelope.opt("host_pub") as? String ?: throw SecurityException("Missing Host signing key.")
        if (expectedHostKey != null && hostKey != expectedHostKey) {
            throw SecurityException("Room Host signing key changed.")
        }
        if (!SignedRoomJson.verify(envelope, hostKey)) throw SecurityException("Invalid room metadata signature.")
        val timestamp = envelope.opt("ts").asLong() ?: throw IOException("Invalid room metadata timestamp.")
        if (timestamp !in (nowSeconds - MAX_AGE_SECONDS)..(nowSeconds + MAX_AGE_SECONDS)) {
            throw SecurityException("Room metadata expired. Check the phone and Host clocks.")
        }
        val wireRoom = envelope.opt("net_id") as? String ?: throw IOException("Missing room ID.")
        if (!ROOM_ID.matches(wireRoom)) throw IOException("Invalid room ID.")
        val room = wireRoom.lowercase()
        if (expectedRoomKey != null && RoomKey.parse(expectedRoomKey).getOrNull()?.value != room) {
            throw SecurityException("Host returned metadata for a different room.")
        }
        if (envelope.opt("v").asLong() != 1L) throw IOException("Unsupported Host announcement format.")
        val release = (envelope.opt("version") as? String)?.takeIf(RELEASE::matches)
            ?: throw IOException("Invalid Host version.")
        val protocol = if (envelope.has("proto")) {
            envelope.opt("proto").asLong()?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
                ?: throw IOException("Invalid Host protocol.")
        } else 1
        val presencePort = envelope.opt("presence_port").asPort()
            ?: throw IOException("Invalid Host roster port.")
        val overlayAddress = if (envelope.has("host_zt")) {
            (envelope.opt("host_zt") as? String)?.takeIf(::isIpv4)
                ?: throw IOException("Invalid Host VLA address.")
        } else null
        val admission = if (envelope.has("admission")) {
            val metadata = envelope.optJSONObject("admission") ?: throw IOException("Invalid room admission metadata.")
            val port = metadata.opt("port").asPort() ?: throw IOException("Invalid room admission port.")
            val fingerprint = (metadata.opt("fingerprint") as? String)?.takeIf(FINGERPRINT::matches)
                ?: throw IOException("Invalid room certificate fingerprint.")
            if (metadata.opt("required") != true) throw IOException("Invalid room admission requirement.")
            RoomAdmissionMetadata(port, fingerprint)
        } else null
        // The socket peer is used as the LAN endpoint. The separately signed host_zt
        // value is checked by the caller against the active VLA route.
        return RoomBeacon(
            room, (envelope.opt("pc_name") as? String)?.trim()?.take(64)?.takeIf(String::isNotBlank) ?: address,
            release, protocol, address, presencePort, overlayAddress, hostKey, admission,
        )
    }

    internal fun isIpv4(address: String): Boolean {
        val octets = address.split('.')
        return octets.size == 4 && octets.all { octet ->
            octet.isNotEmpty() && octet.length <= 3 && octet.all { it in '0'..'9' } &&
                (octet.length == 1 || octet[0] != '0') && octet.toInt() in 0..255
        }
    }

    private fun readHttpHeader(input: BufferedInputStream, socket: Socket, deadline: Long): String {
        val output = ByteArrayOutputStream()
        var ending = 0
        while (output.size() < MAX_HEADER_BYTES) {
            socket.soTimeout = timeLeft(deadline)
            val byte = input.read()
            if (byte < 0) throw IOException("Incomplete Host HTTP response.")
            output.write(byte)
            ending = (ending shl 8) or byte
            if (ending == 0x0d0a0d0a) return output.toString(Charsets.US_ASCII.name())
        }
        throw IOException("Host HTTP headers were too large.")
    }

    private fun timeLeft(deadline: Long): Int {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) throw SocketTimeoutException("Room metadata connection timed out.")
        return TimeUnit.NANOSECONDS.toMillis(remaining).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    }

    private fun Any?.asLong(): Long? = when (this) {
        is Int -> toLong()
        is Long -> this
        else -> null
    }

    private fun Any?.asPort(): Int? = asLong()?.takeIf { it in 1..65535 }?.toInt()
}
