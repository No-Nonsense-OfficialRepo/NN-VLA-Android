package com.nononsense.vla.vpn

import android.content.Context
import android.os.Build
import com.nononsense.vla.admission.RoomAdmissionAccess
import com.nononsense.vla.admission.RoomAdmissionClient
import com.nononsense.vla.admission.RoomAdmissionResult
import com.nononsense.vla.discovery.RoomBeacon
import com.nononsense.vla.discovery.RoomMetadata
import com.nononsense.vla.model.RoomUser
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class VlaPresenceClient(
    context: Context,
    private val host: String,
    private val roomKey: String,
    private val expectedHostKey: String?,
    private val onUsers: (List<RoomUser>) -> Unit,
    private val onRoster: (Boolean, String) -> Unit,
    private val onPrompt: (String?) -> Unit,
    private val onFailure: (String) -> Unit,
    private val onPort: (Int) -> Unit,
) {
    private val stopped = AtomicBoolean()
    private val socket = AtomicReference<Socket?>()
    private val deviceName = Build.MODEL.take(32).ifBlank { "Android phone" }
    private val admission = RoomAdmissionClient(context.applicationContext)
    private val passwords = ArrayBlockingQueue<String>(1)
    private val worker = Thread(::run, "VLA presence")

    fun start() = worker.start()
    fun submitPassword(password: String) { passwords.offer(password) }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        worker.interrupt()
        passwords.clear()
        runCatching { socket.getAndSet(null)?.close() }
        admission.close()
    }

    private fun trackSocket(connection: Socket) {
        socket.set(connection)
        if (stopped.get()) {
            connection.close()
            throw InterruptedException()
        }
    }

    private fun run() {
        try {
            val beacon = findRoom() ?: return
            onPort(beacon.presencePort)
            val access = if (beacon.admission != null) authorize(beacon) else null
            while (!stopped.get()) {
                onRoster(false, "Connecting to room roster…")
                val message = try {
                    observeRoster(beacon, access)
                    "Host closed the roster connection."
                } catch (error: IOException) {
                    "Roster unavailable at $host:${beacon.presencePort} (${error.javaClass.simpleName}). Reconnecting…"
                } finally {
                    runCatching { socket.getAndSet(null)?.close() }
                }
                if (stopped.get()) return
                onRoster(false, message)
                Thread.sleep(1_000)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            if (!stopped.get()) onFailure(error.message ?: "Could not join the room roster.")
        }
    }

    private fun findRoom(): RoomBeacon? {
        repeat(6) {
            if (stopped.get()) return null
            onRoster(false, "Reading signed room details from $host…")
            try {
                val beacon = RoomMetadata.fetch(host, expectedRoomKey = roomKey, expectedHostKey = expectedHostKey,
                    onSocket = ::trackSocket)
                if (beacon.hostOverlayAddress != null && beacon.hostOverlayAddress != host) {
                    throw SecurityException("The Host address in the signed room details does not match this VPN route.")
                }
                return beacon
            } catch (_: IOException) {
                if (!stopped.get()) Thread.sleep(1_000)
            } finally {
                socket.set(null)
            }
        }
        if (!stopped.get()) onFailure("Could not read verified room details from $host. Check the Host connection and try again.")
        return null
    }

    private fun authorize(beacon: RoomBeacon): RoomAdmissionAccess {
        var result = admission.join(host, beacon, null)
        var approvalDeadline = Long.MAX_VALUE
        while (!stopped.get()) {
            result = when (val reply = result) {
                is RoomAdmissionResult.Allowed -> return bind(beacon, reply.access)
                is RoomAdmissionResult.Pending -> {
                    if (approvalDeadline == Long.MAX_VALUE) {
                        approvalDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
                    }
                    if (System.nanoTime() >= approvalDeadline) error("Host approval timed out.")
                    onRoster(false, "Waiting for Host approval…")
                    Thread.sleep(1_500)
                    admission.poll(host, beacon, reply.requestId)
                }
                is RoomAdmissionResult.Rejected -> when (reply.status) {
                    "password_required" -> requestPassword(beacon, "Enter the Host's room password.")
                    "wrong_password" -> requestPassword(beacon, "Incorrect password. Try again.")
                    "rate_limited" -> requestPassword(beacon, "The Host asked you to wait before trying again.")
                    else -> error("Host could not authorize this phone (${reply.status}).")
                }
            }
        }
        throw InterruptedException()
    }

    private fun requestPassword(beacon: RoomBeacon, message: String): RoomAdmissionResult {
        onPrompt(message)
        try {
            while (!stopped.get()) {
                val password = passwords.poll(180, TimeUnit.SECONDS) ?: error("The room password prompt timed out.")
                if (password.toByteArray(Charsets.UTF_8).size <= 256) {
                    onPrompt(null)
                    onRoster(false, "Authenticating with Host…")
                    return admission.join(host, beacon, password)
                }
                onPrompt("The room password must be 256 bytes or fewer.")
            }
            throw InterruptedException()
        } finally {
            onPrompt(null)
        }
    }

    private fun bind(beacon: RoomBeacon, access: RoomAdmissionAccess): RoomAdmissionAccess {
        repeat(8) {
            if (stopped.get()) throw InterruptedException()
            onRoster(false, "Activating protected room access…")
            when (val result = admission.bind(host, beacon, access)) {
                is RoomAdmissionResult.Allowed -> return access
                is RoomAdmissionResult.Rejected -> if (result.status != "busy") {
                    error("Host could not activate protected room access (${result.status}).")
                }
                is RoomAdmissionResult.Pending -> error("Unexpected admission response from the Host.")
            }
            Thread.sleep(750)
        }
        error("The Host could not activate protected room access in time.")
    }

    private fun observeRoster(beacon: RoomBeacon, access: RoomAdmissionAccess?) {
        val connection = if (access != null) {
            RoomAdmissionClient.openPresenceSocket(host, beacon.presencePort, access, onSocket = ::trackSocket)
        } else Socket().also {
            trackSocket(it)
            it.connect(InetSocketAddress(host, beacon.presencePort), 5_000)
        }
        connection.use {
            it.soTimeout = 20_000
            val reader = it.getInputStream().bufferedReader(Charsets.UTF_8)
            val writer = it.getOutputStream().bufferedWriter(Charsets.UTF_8)
            send(writer, JSONObject().apply {
                put("type", "hello")
                put("player_id", admission.playerId)
                put("name", deviceName)
                put("avatar", "")
                put("version", 1)
                put("launcher_version", beacon.version)
                put("proto", beacon.protocol)
                if (access != null) put("admission_token", access.token)
            })
            send(writer, JSONObject().put("type", "subscribe_players"))
            var welcomed = false
            while (!stopped.get()) {
                val message = JSONObject(readRosterLine(reader) ?: return)
                when (message.optString("type")) {
                    "welcome" -> {
                        welcomed = true
                        onRoster(true, "Room roster connected.")
                    }
                    "error" -> error("Host rejected the roster connection (${message.optString("code", "unknown")}).")
                    "ping" -> send(writer, JSONObject().put("type", "pong").put("id", message.opt("id")))
                    "players" -> if (welcomed) onUsers(readUsers(message))
                    "kicked" -> {
                        if (welcomed) onFailure("The Host removed you from the room.")
                        return
                    }
                }
                send(writer, JSONObject().put("type", "heartbeat"))
            }
        }
    }

    private fun readRosterLine(reader: BufferedReader): String? {
        val line = StringBuilder()
        while (line.length < 32 * 1024 * 1024) {
            val next = reader.read()
            if (next == -1) return null
            if (next == '\n'.code) return line.toString()
            line.append(next.toChar())
        }
        throw IOException("Room roster exceeded the message limit.")
    }

    private fun readUsers(message: JSONObject) = buildList {
        add(RoomUser(
            name = message.optJSONObject("host_profile")?.optString("name")?.ifBlank { "Host" } ?: "Host",
            address = host,
            isHost = true,
        ))
        val players = message.optJSONArray("players") ?: return@buildList
        for (index in 0 until players.length()) {
            val player = players.optJSONObject(index) ?: continue
            add(RoomUser(
                name = player.optString("name", "User"),
                address = player.optString("ip"),
                state = player.optString("state", "browsing"),
                pingMs = if (player.isNull("ping_ms")) null else player.optInt("ping_ms"),
            ))
        }
    }

    private fun send(writer: BufferedWriter, message: JSONObject) {
        writer.write(message.toString())
        writer.newLine()
        writer.flush()
    }
}
