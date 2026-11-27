package com.nononsense.vla.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.Environment
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.Build
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.nononsense.vla.MainActivity
import com.nononsense.vla.R
import com.nononsense.vla.model.RoomKey
import com.nononsense.vla.model.AppPreferences
import com.nononsense.vla.model.TunnelState
import com.nononsense.vla.model.TunnelStateStore
import com.nononsense.vla.model.RoomUser
import com.nononsense.vla.share.PhoneFileServer
import java.net.InetSocketAddress
import java.net.Socket

class VlaVpnService : VpnService(), TunnelEngine.Listener {
    private val engine: TunnelEngine by lazy { ZeroTierTunnelEngine(this) }
    private var vpnInterface: ParcelFileDescriptor? = null
    private var activeRoomKey: RoomKey? = null
    private val main = Handler(Looper.getMainLooper())
    private var presence: VlaPresenceClient? = null
    private var presenceGeneration = 0L
    private var rosterReady = false
    private var passwordPrompt: String? = null
    private var expectedHostKey: String? = null
    private val fileServer by lazy { PhoneFileServer() }
    private var activeAddress: String? = null
    private var activeShareUrl: String? = null
    private var shareRequest: String? = null
    private var shareGeneration = 0L
    private var sharingError: String? = null
    private var activeUsers: List<RoomUser> = emptyList()
    @Volatile private var activePresenceStatus = "Connecting to room roster…"
    private val rosterEvents = ArrayDeque<String>()
    private var activeHostAddress: String? = null
    private var activeLanHost: String? = null
    private var activeRosterPort = 47800

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VLA connection",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shows the active NN VLA room connection" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> connect(intent)
            ACTION_DISCONNECT -> finishRoom(TunnelState.Disconnected)
            ACTION_REFRESH_SHARE -> refreshFileShare()
            ACTION_PASSWORD -> if (passwordPrompt != null) {
                intent.getStringExtra(EXTRA_PASSWORD)?.let { value ->
                    passwordPrompt = null
                    presence?.submitPassword(value)
                    publishRoom()
                }
            }
            ACTION_DIAGNOSTICS -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<ResultReceiver>(EXTRA_REPORT)?.let(::runDiagnostics)
            }
        }
        if (activeRoomKey == null) stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        main.post { if (activeRoomKey != null) finishRoom(TunnelState.Disconnected) }
    }

    override fun onDestroy() {
        val wasActive = activeRoomKey != null
        closeRoom()
        if (wasActive) TunnelStateStore.update(TunnelState.Disconnected)
        super.onDestroy()
    }

    private fun connect(intent: Intent) {
        if (activeRoomKey != null) return
        val roomKey = RoomKey.parse(intent.getStringExtra(EXTRA_ROOM_KEY).orEmpty()).getOrElse {
            TunnelStateStore.update(TunnelState.Failed(it.message ?: "Invalid room key."))
            stopSelf()
            return
        }
        activeRoomKey = roomKey
        expectedHostKey = intent.getStringExtra(EXTRA_HOST_KEY)
        activeLanHost = intent.getStringExtra(EXTRA_LAN_HOST)
        startForeground(NOTIFICATION_ID, notification("Connecting to ${roomKey.displayValue}"))
        TunnelStateStore.update(TunnelState.Connecting(roomKey.displayValue))
        engine.connect(roomKey, this)
    }

    override fun onConfigured(configuration: TunnelConfiguration) {
        val roomKey = activeRoomKey ?: return
        val established = runCatching { Builder()
            .setSession("NN VLA ${roomKey.displayValue}")
            .setMtu(configuration.mtu)
            .setBlocking(true)
            .addAddress(configuration.address, configuration.prefixLength)
            .addRoute(configuration.route, configuration.routePrefixLength)
            .establish() }.getOrNull()
        if (established == null) {
            onFailure("Android did not create the VLA VPN interface.")
            return
        }
        runCatching { vpnInterface?.close() }
        vpnInterface = established
        try { engine.attachInterface(established) } catch (_: Exception) {
            onFailure("Could not attach to the Android VPN interface.")
            return
        }
        val generation = ++presenceGeneration
        presence?.stop()
        rosterReady = false
        passwordPrompt = null
        stopSharing()
        activeAddress = configuration.address
        activeUsers = emptyList()
        activePresenceStatus = "Connecting to room roster…"
        synchronized(rosterEvents) { rosterEvents.clear() }
        activeHostAddress = configuration.hostAddress
        activeRosterPort = 47800
        presence = VlaPresenceClient(this, configuration.hostAddress, roomKey.value, expectedHostKey,
            onUsers = { users -> rosterEvent(generation) { activeUsers = users; publishRoom() } },
            onRoster = { ready, status -> rosterEvent(generation) { updatePresenceStatus(ready, status) } },
            onPrompt = { prompt -> rosterEvent(generation) { passwordPrompt = prompt; publishRoom() } },
            onFailure = { message -> rosterEvent(generation) { onFailure(message) } },
            onPort = { port -> rosterEvent(generation) { activeRosterPort = port } },
        ).also { it.start() }
        publishRoom()
        updateNotification("VPN active • connecting to Host")
    }

    private fun rosterEvent(generation: Long, action: () -> Unit) {
        main.post { if (generation == presenceGeneration && activeRoomKey != null) action() }
    }

    override fun onFailure(message: String) {
        if (activeRoomKey != null) finishRoom(TunnelState.Failed(message))
    }

    private fun finishRoom(state: TunnelState) {
        closeRoom()
        TunnelStateStore.update(state)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closeRoom() {
        activeRoomKey = null
        presenceGeneration++
        presence?.stop()
        presence = null
        rosterReady = false
        passwordPrompt = null
        stopSharing()
        engine.disconnect()
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        activeAddress = null
        activeUsers = emptyList()
        activeHostAddress = null
        activeLanHost = null
        expectedHostKey = null
    }

    private fun notification(message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val leaveRoom = PendingIntent.getService(
            this, 1, disconnectIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("NN VLA")
            .setContentText(message)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Disconnect", leaveRoom).build())
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(message))
    }

    private fun refreshFileShare() {
        val address = activeAddress ?: return
        val allowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        if (!rosterReady || !allowed || !AppPreferences.isFileSharingEnabled(this)) {
            stopSharing()
            publishRoom()
            updateRoomNotification()
            return
        }
        if (shareRequest == address) return
        val generation = ++shareGeneration
        shareRequest = address
        sharingError = null
        fileServer.start(address) { port ->
            main.post {
                if (generation != shareGeneration || !rosterReady || activeAddress != address) return@post
                activeShareUrl = when (port) {
                    80 -> "http://$address/"
                    null -> null
                    else -> "http://$address:$port/"
                }
                sharingError = if (port != null) null else "Could not start phone file sharing. Toggle sharing to retry."
                if (port == null) shareRequest = null
                publishRoom()
                updateRoomNotification()
            }
        }
    }

    private fun stopSharing() {
        shareGeneration++
        shareRequest = null
        activeShareUrl = null
        sharingError = null
        fileServer.stop()
    }

    private fun updateRoomNotification() {
        val address = activeAddress ?: return
        updateNotification(when {
            activeShareUrl != null -> "Connected • phone files shared"
            rosterReady -> "Connected as $address"
            else -> "VPN active • waiting for room access"
        })
    }

    private fun publishRoom() {
        val address = activeAddress ?: return
        val roomKey = activeRoomKey ?: return
        TunnelStateStore.update(TunnelState.Connected(
            roomKey.displayValue, address, activeShareUrl, activeUsers, activePresenceStatus,
            passwordPrompt, sharingError,
        ))
    }

    private fun updatePresenceStatus(ready: Boolean, status: String) {
        val changed = rosterReady != ready
        rosterReady = ready
        if (!ready) activeUsers = emptyList()
        activePresenceStatus = status
        synchronized(rosterEvents) {
            if (rosterEvents.size == 12) rosterEvents.removeFirst()
            rosterEvents.addLast(status)
        }
        if (changed) refreshFileShare()
        publishRoom()
        if (changed) updateRoomNotification()
    }

    private fun runDiagnostics(receiver: ResultReceiver) {
        val vlaAddress = activeAddress ?: "unavailable"
        val rosterStatus = activePresenceStatus
        val rosterPort = activeRosterPort
        val targets = listOfNotNull(activeHostAddress, activeLanHost).distinct()
        Thread({
            val connectivity = getSystemService(ConnectivityManager::class.java)
            val wifiAddress = connectivity.allNetworks.asSequence()
                .filter { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
                .flatMap { connectivity.getLinkProperties(it)?.linkAddresses.orEmpty().asSequence() }
                .firstOrNull { it.address.address.size == 4 }?.address?.hostAddress ?: "unavailable"
            val results = targets.flatMap { target ->
                listOf(47802, rosterPort).distinct().map { port ->
                    val result = try {
                        Socket().use { it.connect(InetSocketAddress(target, port), 2_500) }
                        "reachable"
                    } catch (error: Exception) { error.javaClass.simpleName }
                    "$target:$port $result"
                }
            }
            val history = synchronized(rosterEvents) { rosterEvents.toList() }
            val report = listOf("Wi-Fi: $wifiAddress", "VLA: $vlaAddress", "Roster: $rosterStatus") +
                results + listOf("", "Recent roster events:") + history + listOf("", engine.packetCounters())
            receiver.send(0, Bundle().apply { putString(EXTRA_REPORT, report.joinToString("\n")) })
        }, "VLA diagnostics").start()
    }

    companion object {
        private const val ACTION_CONNECT = "com.nononsense.vla.CONNECT"
        private const val ACTION_DISCONNECT = "com.nononsense.vla.DISCONNECT"
        private const val ACTION_REFRESH_SHARE = "com.nononsense.vla.REFRESH_SHARE"
        private const val ACTION_PASSWORD = "com.nononsense.vla.ROOM_PASSWORD"
        private const val ACTION_DIAGNOSTICS = "com.nononsense.vla.DIAGNOSTICS"
        const val EXTRA_REPORT = "diagnostic_report"
        private const val EXTRA_ROOM_KEY = "room_key"
        private const val EXTRA_HOST_KEY = "host_key"
        private const val EXTRA_LAN_HOST = "lan_host"
        private const val EXTRA_PASSWORD = "room_password"
        private const val CHANNEL_ID = "vla_connection"
        private const val NOTIFICATION_ID = 147

        fun connectIntent(context: Context, roomKey: RoomKey, hostKey: String?, lanHost: String?) =
            Intent(context, VlaVpnService::class.java)
                .setAction(ACTION_CONNECT)
                .putExtra(EXTRA_ROOM_KEY, roomKey.value)
                .putExtra(EXTRA_HOST_KEY, hostKey)
                .putExtra(EXTRA_LAN_HOST, lanHost)

        fun passwordIntent(context: Context, password: String) =
            Intent(context, VlaVpnService::class.java).setAction(ACTION_PASSWORD).putExtra(EXTRA_PASSWORD, password)

        fun disconnectIntent(context: Context) =
            Intent(context, VlaVpnService::class.java).setAction(ACTION_DISCONNECT)

        fun refreshShareIntent(context: Context) =
            Intent(context, VlaVpnService::class.java).setAction(ACTION_REFRESH_SHARE)

        fun diagnosticsIntent(context: Context, receiver: ResultReceiver) =
            Intent(context, VlaVpnService::class.java).setAction(ACTION_DIAGNOSTICS)
                .putExtra(EXTRA_REPORT, receiver)
    }
}
