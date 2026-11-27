package com.nononsense.vla.admission

import android.content.Context
import android.os.Build
import com.nononsense.vla.discovery.RoomBeacon
import com.nononsense.vla.discovery.RoomMetadata
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class RoomAdmissionAccess internal constructor(val fingerprint: String, val token: String)

sealed interface RoomAdmissionResult {
    data class Allowed(val access: RoomAdmissionAccess) : RoomAdmissionResult
    data class Pending(val requestId: String) : RoomAdmissionResult
    /** The Host's status code, e.g. password_required, wrong_password, denied, or busy. */
    data class Rejected(val status: String) : RoomAdmissionResult
}

/** One per room session. Closing the session interrupts any current admission exchange. */
class RoomAdmissionClient internal constructor(
    private val deviceName: String,
    private val identityProvider: () -> Ed25519PrivateKeyParameters,
) : Closeable {
    constructor(context: Context) : this(
        Build.MODEL.take(32).ifBlank { "Android phone" },
        { loadIdentity(context.applicationContext) },
    )

    private val lock = Any()
    private var closed = false
    private var activeSocket: Socket? = null
    private val identity by lazy(identityProvider)
    private val publicKey by lazy { SignedRoomJson.base64(identity.generatePublicKey().encoded) }
    val playerId: String get() = publicKey

    fun join(host: String, beacon: RoomBeacon, password: String? = null): RoomAdmissionResult {
        if (password != null && password.toByteArray(Charsets.UTF_8).size > MAX_PASSWORD_BYTES) {
            return RoomAdmissionResult.Rejected("password_too_long")
        }
        return request(host, beacon, "join", password = password)
    }

    fun poll(host: String, beacon: RoomBeacon, requestId: String): RoomAdmissionResult =
        request(host, beacon, "poll", requestId = requestId)

    fun bind(host: String, beacon: RoomBeacon, access: RoomAdmissionAccess): RoomAdmissionResult =
        request(host, beacon, "bind", access = access)

    fun cancelApproval(host: String, beacon: RoomBeacon, requestId: String): RoomAdmissionResult =
        request(host, beacon, "cancel", requestId = requestId)

    override fun close() {
        val current = synchronized(lock) {
            closed = true
            activeSocket.also { activeSocket = null }
        }
        runCatching { current?.close() }
    }

    private fun track(socket: Socket) {
        synchronized(lock) {
            if (closed || activeSocket != null) {
                throw SocketException("Room admission session is unavailable.")
            }
            activeSocket = socket
        }
    }

    private fun request(
        host: String,
        beacon: RoomBeacon,
        action: String,
        password: String? = null,
        requestId: String? = null,
        access: RoomAdmissionAccess? = null,
    ): RoomAdmissionResult {
        val metadata = requireNotNull(beacon.admission) { "Room does not require admission." }
        if (access != null && access.fingerprint != metadata.fingerprint) {
            throw SecurityException("Host certificate changed during admission.")
        }
        var owned: Socket? = null
        try {
            openPinnedSocket(host, metadata.port, metadata.fingerprint, IO_TIMEOUT_MS) {
                track(it)
                owned = it
            }.use { connection ->
                val input = BufferedInputStream(connection.getInputStream())
                val nonce = (input.readMessage(connection).opt("nonce") as? String)?.takeIf(OPAQUE_VALUE::matches)
                    ?: throw IOException("Invalid Host admission challenge.")
                val payload = JSONObject().apply {
                    put("nonce", nonce)
                    put("identity", publicKey)
                    put("player_id", playerId)
                    put("name", deviceName)
                    put("launcher_version", beacon.version)
                    put("proto", beacon.protocol)
                    put("action", action)
                    put("password", password ?: JSONObject.NULL)
                    put("request_id", requestId ?: JSONObject.NULL)
                    put("token", access?.token ?: JSONObject.NULL)
                    put("sig", SignedRoomJson.sign(this, identity))
                }
                connection.getOutputStream().apply {
                    write(payload.toString().toByteArray(Charsets.UTF_8))
                    write('\n'.code)
                    flush()
                }
                val response = input.readMessage(connection)
                return when (val status = response.optString("status")) {
                    "allowed" -> {
                        val granted = access ?: RoomAdmissionAccess(
                            metadata.fingerprint, response.opaque("token"),
                        )
                        RoomAdmissionResult.Allowed(granted)
                    }
                    "pending" -> RoomAdmissionResult.Pending(response.opaque("request_id"))
                    "password_required", "wrong_password", "denied", "expired", "cancelled",
                    "invalid", "busy", "rate_limited", "version_mismatch" -> RoomAdmissionResult.Rejected(status)
                    else -> throw IOException("Unknown Host admission response.")
                }
            }
        } finally {
            synchronized(lock) {
                if (activeSocket === owned) activeSocket = null
            }
        }
    }

    private fun JSONObject.opaque(key: String): String =
        (opt(key) as? String)?.takeIf(OPAQUE_VALUE::matches)
            ?: throw IOException("Host admission response was incomplete.")

    private fun BufferedInputStream.readMessage(connection: Socket): JSONObject {
        val output = ByteArrayOutputStream()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(IO_TIMEOUT_MS.toLong())
        while (output.size() < MAX_MESSAGE_BYTES) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw SocketTimeoutException("Host admission timed out.")
            connection.soTimeout = TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1).toInt()
            val byte = read()
            if (byte < 0) throw IOException("Host closed the admission connection.")
            if (byte == '\n'.code) return JSONObject(output.toString(Charsets.UTF_8.name()))
            output.write(byte)
        }
        throw IOException("Host admission response was too large.")
    }

    companion object {
        private const val IO_TIMEOUT_MS = 5_000
        private const val MAX_PASSWORD_BYTES = 256
        private const val MAX_MESSAGE_BYTES = 8192
        private val OPAQUE_VALUE = Regex("[A-Za-z0-9_-]{1,128}")
        private val FINGERPRINT = Regex("[0-9a-f]{64}")

        private val identityLock = Any()

        private fun loadIdentity(context: Context): Ed25519PrivateKeyParameters = synchronized(identityLock) {
            val preferences = context.getSharedPreferences("room_admission_identity", Context.MODE_PRIVATE)
            val saved = preferences.getString("ed25519_seed", null)
            if (saved != null) {
                val seed = SignedRoomJson.unbase64(saved)
                require(seed.size == Ed25519PrivateKeyParameters.KEY_SIZE) { "Invalid stored room identity." }
                return@synchronized Ed25519PrivateKeyParameters(seed)
            }
            Ed25519PrivateKeyParameters(SecureRandom()).also { key ->
                if (!preferences.edit().putString("ed25519_seed", SignedRoomJson.base64(key.encoded)).commit()) {
                    throw IOException("Could not save this phone's room identity.")
                }
            }
        }

        /**
         * The certificate pin comes from the verified room beacon. [onSocket] receives
         * the underlying socket before connection so callers can cancel a pending handshake.
         */
        fun openPresenceSocket(
            host: String,
            port: Int,
            access: RoomAdmissionAccess,
            timeoutMs: Int = IO_TIMEOUT_MS,
            onSocket: ((Socket) -> Unit)? = null,
        ): SSLSocket = openPinnedSocket(host, port, access.fingerprint, timeoutMs, onSocket)

        private fun openPinnedSocket(
            host: String,
            port: Int,
            fingerprint: String,
            timeoutMs: Int,
            onSocket: ((Socket) -> Unit)?,
        ): SSLSocket {
            require(RoomMetadata.isIpv4(host) && port in 1..65535 && timeoutMs > 0)
            require(FINGERPRINT.matches(fingerprint))
            val pin = ByteArray(32) { index ->
                fingerprint.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
            val trust = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
                    throw CertificateException("Room client certificate is unsupported.")
                }

                override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                    val certificate = chain.firstOrNull()
                        ?: throw CertificateException("Host did not send a room certificate.")
                    val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                    if (!MessageDigest.isEqual(pin, actual)) {
                        throw CertificateException("Host certificate does not match the signed room.")
                    }
                    certificate.checkValidity()
                }
            }
            val context = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trust), null)
            }
            val socket = Socket()
            var tls: SSLSocket? = null
            try {
                onSocket?.invoke(socket)
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = timeoutMs
                tls = context.socketFactory.createSocket(socket, host, port, true) as SSLSocket
                tls.enabledProtocols = tls.enabledProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
                tls.soTimeout = timeoutMs
                tls.startHandshake()
                return tls
            } catch (error: Exception) {
                runCatching { tls?.close() }
                runCatching { socket.close() }
                throw error
            }
        }
    }
}
