package com.nononsense.vla.vpn

import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import com.nononsense.vla.model.RoomKey
import com.zerotier.sdk.Event
import com.zerotier.sdk.EventListener
import com.zerotier.sdk.Node
import com.zerotier.sdk.PacketSender
import com.zerotier.sdk.ResultCode
import com.zerotier.sdk.VirtualNetworkConfig
import com.zerotier.sdk.VirtualNetworkConfigListener
import com.zerotier.sdk.VirtualNetworkConfigOperation
import com.zerotier.sdk.VirtualNetworkFrameListener
import com.zerotier.sdk.VirtualNetworkStatus
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class ZeroTierTunnelEngine(private val vpnService: VpnService) : TunnelEngine {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var session: Session? = null
    @Volatile private var lastCounters: PacketCounters? = null

    @Synchronized
    override fun connect(roomKey: RoomKey, listener: TunnelEngine.Listener) {
        if (session != null) return
        val next = Session(roomKey.value.toULong(16).toLong(), listener)
        lastCounters = next.counters
        session = next
        nativeWorker.execute(next::initialize)
    }

    override fun attachInterface(fileDescriptor: ParcelFileDescriptor) {
        session?.attachInterface(fileDescriptor)
    }

    override fun packetCounters() = lastCounters?.snapshot().orEmpty()

    @Synchronized
    override fun disconnect() {
        val old = session ?: return
        session = null
        old.stop()
    }

    private inner class Session(
        private val networkId: Long,
        private val listener: TunnelEngine.Listener,
    ) : PacketSender, EventListener, VirtualNetworkConfigListener, VirtualNetworkFrameListener {
        val counters = PacketCounters()
        private val failed = AtomicBoolean(false)
        private val tun = AtomicReference<TunEndpoint?>()
        private val packetSlots = Semaphore(MAX_QUEUED_PACKETS)
        @Volatile private var socket: DatagramSocket? = null

        // Accessed only on nativeWorker. In particular, nothing can use Node while
        // another thread is still initializing it or tearing it down.
        private var node: Node? = null
        private var joined = false
        private var localMac = 0L
        private var localAddress = 0
        private var broadcastAddress: Int? = null
        private var configured: TunnelConfiguration? = null
        private var subscribedAddress: Int? = null
        private val authDeadline = SystemClock.elapsedRealtime() + AUTHORIZATION_GRACE_MS
        private val neighbors = Ipv4Neighbors()

        private fun isActive() = session === this && !failed.get()

        fun initialize() {
            if (!isActive()) return
            try {
                val udp = DatagramSocket(null)
                socket = udp
                if (!isActive()) {
                    udp.close()
                    return
                }
                udp.bind(InetSocketAddress(0))
                if (!vpnService.protect(udp)) error("Android could not protect the ZeroTier socket.")
                if (!isActive()) return
                val created = Node(nodeClock.updateAndGet { previous -> maxOf(previous + 1, System.currentTimeMillis()) })
                val store = ZeroTierDataStore(vpnService)
                requireSuccess(created.init(store, store, this, this, this, this, null), "initialize")
                node = created
                if (!isActive()) return
                requireSuccess(created.join(networkId), "join the VLA room")
                joined = true
                if (!isActive()) return
                Thread({ receiveWirePackets(udp) }, "VLA UDP receive").start()
                tick()
            } catch (error: Exception) {
                fail("ZeroTier failed to start: ${error.message ?: error.javaClass.simpleName}")
            } catch (error: LinkageError) {
                fail("ZeroTier native library could not start: ${error.message ?: error.javaClass.simpleName}")
            }
        }

        fun stop() {
            socket?.close()
            tun.getAndSet(null)?.stop()
            nativeWorker.execute {
                try {
                    if (joined) node?.leave(networkId)
                } catch (error: Exception) {
                    Log.w(TAG, "Could not leave ZeroTier network", error)
                } finally {
                    try {
                        node?.close()
                    } catch (error: Exception) {
                        Log.w(TAG, "Could not close ZeroTier node", error)
                    } finally {
                        socket?.close()
                        socket = null
                        node = null
                        joined = false
                        neighbors.clear()
                    }
                }
            }
        }

        fun attachInterface(fileDescriptor: ParcelFileDescriptor) {
            if (!isActive()) return
            try {
                val endpoint = TunEndpoint(fileDescriptor)
                tun.getAndSet(endpoint)?.stop()
                if (!isActive()) {
                    tun.compareAndSet(endpoint, null)
                    endpoint.stop()
                }
                endpoint.start()
            } catch (error: Exception) {
                fail("VLA tunnel could not be attached: ${error.message ?: error.javaClass.simpleName}")
            }
        }

        override fun onSendPacketRequested(
            localSocket: Long, remoteAddr: InetSocketAddress, packetData: ByteArray, ttl: Int,
        ): Int {
            val udp = socket ?: return -1
            if (!isActive()) return -1
            return try {
                udp.send(DatagramPacket(packetData, packetData.size, remoteAddr))
                counters.increment("UDP sent")
                0
            } catch (_: Exception) {
                counters.increment("UDP send errors")
                -1
            }
        }

        override fun onEvent(event: Event) {
            if (event == Event.EVENT_FATAL_ERROR_IDENTITY_COLLISION) {
                fail("ZeroTier identity collision. Clear app data and reconnect.")
            }
        }

        override fun onTrace(message: String) {
            Log.d(TAG, message)
        }

        override fun onNetworkConfigurationUpdated(
            nwid: Long, operation: VirtualNetworkConfigOperation, config: VirtualNetworkConfig?,
        ): Int {
            if (!isActive() || nwid != networkId) return 0
            if (operation == VirtualNetworkConfigOperation.VIRTUAL_NETWORK_CONFIG_OPERATION_DOWN ||
                operation == VirtualNetworkConfigOperation.VIRTUAL_NETWORK_CONFIG_OPERATION_DESTROY) {
                fail("The ZeroTier network is no longer available.")
                return 0
            }

            when (config?.status) {
                VirtualNetworkStatus.NETWORK_STATUS_ACCESS_DENIED -> {
                    if (configured != null || SystemClock.elapsedRealtime() >= authDeadline) {
                        fail("This device is no longer authorized for the VLA room.")
                    }
                }
                VirtualNetworkStatus.NETWORK_STATUS_NOT_FOUND -> fail("The VLA room was not found.")
                VirtualNetworkStatus.NETWORK_STATUS_PORT_ERROR -> fail("ZeroTier reported a VLA port error (${config.portError}).")
                VirtualNetworkStatus.NETWORK_STATUS_CLIENT_TOO_OLD -> fail("This ZeroTier client is too old for the VLA room.")
                VirtualNetworkStatus.NETWORK_STATUS_AUTHENTICATION_REQUIRED -> fail("The VLA room requires additional authentication.")
                else -> Unit
            }
            if (config?.status != VirtualNetworkStatus.NETWORK_STATUS_OK) return 0
            val assigned = config.assignedAddresses.firstOrNull { it.address is Inet4Address } ?: return 0
            val prefix = assigned.port.coerceIn(0, 32)
            val address = readInt(assigned.address.address, 0)
            val mask = prefixMask(prefix)
            val route = address and mask
            val next = TunnelConfiguration(
                address = intToIpv4(address),
                hostAddress = intToIpv4(route or 1),
                prefixLength = prefix,
                route = intToIpv4(route),
                routePrefixLength = prefix,
                mtu = config.mtu.coerceIn(1280, 2800),
            )
            if (configured == next && localMac == config.mac) return 0
            val interfaceChanged = configured != next
            configured = next
            localAddress = address
            localMac = config.mac
            broadcastAddress = directedBroadcastAddress(address, prefix)
            neighbors.clear()

            // Node's configuration listener runs inside JNI. Subscribe after that
            // callback returns, with the same ordering as all other native work.
            nativeWorker.execute {
                if (!isActive() || configured != next) return@execute
                try {
                    if (subscribedAddress != address) {
                        val currentNode = node ?: return@execute
                        requireSuccess(currentNode.multicastSubscribe(nwid, BROADCAST_MAC, address.toLong() and 0xffffffffL), "subscribe to address resolution")
                        subscribedAddress?.let { prior ->
                            currentNode.multicastUnsubscribe(nwid, BROADCAST_MAC, prior.toLong() and 0xffffffffL)
                        }
                        subscribedAddress = address
                    }
                    if (interfaceChanged) {
                        main.post { if (isActive()) listener.onConfigured(next) }
                    }
                } catch (error: Exception) {
                    fail(error.message ?: "Could not configure the VLA tunnel.")
                }
            }
            return 0
        }

        override fun onVirtualNetworkFrame(
            nwid: Long, srcMac: Long, destMac: Long, etherType: Long, vlanId: Long, frameData: ByteArray,
        ) {
            if (!isActive() || nwid != networkId) return
            when (etherType.toInt()) {
                ETHERTYPE_IPV4 -> {
                    counters.increment("IPv4 received")
                    counters.countSyn(frameData, "received")
                    if (frameData.size >= 20 && frameData[0].toInt() ushr 4 == 4) {
                        val source = readInt(frameData, 12)
                        submitPacket { learnNeighbor(source, srcMac) }
                        tun.get()?.offer(frameData)
                    }
                }
                ETHERTYPE_ARP -> {
                    counters.increment("ARP received")
                    submitPacket { handleArp(srcMac, frameData) }
                }
            }
        }

        private fun receiveWirePackets(udp: DatagramSocket) {
            val buffer = ByteArray(MAX_PACKET_SIZE)
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                while (isActive()) {
                    packet.length = buffer.size
                    udp.receive(packet)
                    counters.increment("UDP received")
                    val remote = packet.socketAddress as InetSocketAddress
                    val payload = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    submitPacket {
                        val currentNode = node ?: return@submitPacket
                        checkResult(currentNode.processWirePacket(System.currentTimeMillis(), -1, remote, payload, LongArray(1)), "receive network traffic")
                    }
                }
            } catch (error: Exception) {
                fail("VLA network receive failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }

        private fun submitPacket(action: () -> Unit) {
            if (!isActive()) return
            if (!packetSlots.tryAcquire()) {
                counters.increment("Dropped packets")
                return
            }
            nativeWorker.execute {
                try {
                    if (isActive()) action()
                } catch (error: Exception) {
                    fail(error.message ?: "ZeroTier packet processing failed.")
                } finally {
                    packetSlots.release()
                }
            }
        }

        private fun tick() {
            if (!isActive()) return
            try {
                val elapsed = SystemClock.elapsedRealtime()
                if (configured == null && elapsed >= authDeadline) {
                    fail("The Host did not authorize this phone within 60 seconds.")
                    return
                }
                for (target in neighbors.dueProbes(elapsed)) {
                    processFrame(BROADCAST_MAC, ETHERTYPE_ARP, arpRequest(target))
                }
                val currentNode = node ?: return
                val deadline = LongArray(1)
                checkResult(currentNode.processBackgroundTasks(System.currentTimeMillis(), deadline), "run background tasks")
                val delay = (deadline[0] - System.currentTimeMillis()).coerceIn(10L, 1000L)
                nativeWorker.schedule(::tick, delay, TimeUnit.MILLISECONDS)
            } catch (error: Exception) {
                fail("ZeroTier background task failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }

        private fun sendIpv4(packet: ByteArray) {
            if (configured == null) return
            val destination = readInt(packet, 16)
            val now = SystemClock.elapsedRealtime()
            val mac = when {
                destination == -1 || destination == broadcastAddress -> BROADCAST_MAC
                (destination and 0xf0000000.toInt()) == 0xe0000000.toInt() ->
                    0x01005e000000L or (destination.toLong() and 0x7fffffL)
                else -> neighbors.lookup(destination, now)
            }
            if (mac != null) {
                processFrame(mac, ETHERTYPE_IPV4, packet)
                return
            }

            if (neighbors.queue(destination, packet, now)) {
                processFrame(BROADCAST_MAC, ETHERTYPE_ARP, arpRequest(destination))
            }
        }

        private fun handleArp(sourceMac: Long, data: ByteArray) {
            if (configured == null || data.size < 28 || readUnsignedShort(data, 0) != 1 ||
                readUnsignedShort(data, 2) != ETHERTYPE_IPV4 || data[4].toInt() != 6 || data[5].toInt() != 4) return
            val senderIp = readInt(data, 14)
            val operation = readUnsignedShort(data, 6)
            if (operation != 1 && operation != 2) return
            learnNeighbor(senderIp, sourceMac)
            if (operation == 1 && readInt(data, 24) == localAddress) {
                processFrame(sourceMac, ETHERTYPE_ARP, arpReply(sourceMac, senderIp))
            }
        }

        private fun learnNeighbor(ip: Int, mac: Long) {
            if (configured == null || ip == 0 || ip == -1 || ip == localAddress ||
                ip == broadcastAddress || mac == localMac || (mac and 0x010000000000L) != 0L) return
            neighbors.learn(ip, mac, SystemClock.elapsedRealtime()).forEach {
                processFrame(mac, ETHERTYPE_IPV4, it)
            }
        }

        private fun arpRequest(targetIp: Int) = ByteArray(28).also {
            writeArpHeader(it, 1)
            writeMac(it, 8, localMac)
            writeInt(it, 14, localAddress)
            writeInt(it, 24, targetIp)
        }

        private fun arpReply(destinationMac: Long, destinationIp: Int) = ByteArray(28).also {
            writeArpHeader(it, 2)
            writeMac(it, 8, localMac)
            writeInt(it, 14, localAddress)
            writeMac(it, 18, destinationMac)
            writeInt(it, 24, destinationIp)
        }

        private fun processFrame(destinationMac: Long, etherType: Int, data: ByteArray) {
            if (!isActive() || destinationMac == localMac) return
            val currentNode = node ?: return
            val result = currentNode.processVirtualNetworkFrame(
                System.currentTimeMillis(), networkId, localMac, destinationMac, etherType, 0, data, LongArray(1),
            )
            if (result == ResultCode.RESULT_OK) {
                counters.increment(if (etherType == ETHERTYPE_ARP) "ARP submitted" else "IPv4 submitted")
                if (etherType == ETHERTYPE_IPV4) counters.countSyn(data, "submitted")
            } else if (result != ResultCode.RESULT_OK_IGNORED) {
                counters.increment("Frame errors")
                checkResult(result, "send VLA traffic")
            }
        }

        private fun fail(message: String) {
            if (session !== this || !failed.compareAndSet(false, true)) return
            main.post {
                if (session === this) listener.onFailure(message)
            }
        }

        private inner class TunEndpoint(original: ParcelFileDescriptor) {
            // The service owns original. This duplicate remains open until both
            // I/O threads have exited, so a stale thread cannot touch a reused FD.
            private val descriptor = ParcelFileDescriptor.dup(original.fileDescriptor)
            private val fd = descriptor.fileDescriptor
            private val open = AtomicBoolean(true)
            private val workers = AtomicInteger(2)
            private val inbound = ArrayBlockingQueue<ByteArray>(MAX_QUEUED_PACKETS)

            init {
                try {
                    val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
                    Os.fcntlInt(fd, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
                } catch (error: Exception) {
                    descriptor.close()
                    throw error
                }
            }

            fun start() {
                startWorker("VLA TUN read", ::readPackets)
                startWorker("VLA TUN write", ::writePackets)
            }

            fun stop() {
                open.set(false)
                inbound.clear()
            }

            fun offer(packet: ByteArray) {
                if (usable() && !inbound.offer(packet)) counters.increment("Dropped packets")
            }

            private fun usable() = open.get() && isActive() && tun.get() === this

            private fun startWorker(name: String, action: () -> Unit) = Thread({
                try {
                    action()
                } catch (error: Exception) {
                    if (usable()) fail("$name failed: ${error.message ?: error.javaClass.simpleName}")
                } finally {
                    if (workers.decrementAndGet() == 0) runCatching { descriptor.close() }
                }
            }, name).start()

            private fun readPackets() {
                val buffer = ByteArray(MAX_PACKET_SIZE)
                val ready = pollFd(OsConstants.POLLIN)
                while (usable()) {
                    if (Os.poll(ready, IO_POLL_MS) == 0 || !usable()) continue
                    val size = try {
                        Os.read(fd, buffer, 0, buffer.size)
                    } catch (error: ErrnoException) {
                        if (error.errno == OsConstants.EAGAIN || error.errno == OsConstants.EINTR) continue
                        throw error
                    }
                    if (size == 0) error("Interface closed")
                    if (size < 20 || buffer[0].toInt() ushr 4 != 4) continue
                    val packet = buffer.copyOf(size)
                    submitPacket { if (usable()) sendIpv4(packet) }
                }
            }

            private fun writePackets() {
                val ready = pollFd(OsConstants.POLLOUT)
                while (usable()) {
                    val packet = inbound.poll(IO_POLL_MS.toLong(), TimeUnit.MILLISECONDS) ?: continue
                    while (usable()) {
                        try {
                            if (Os.write(fd, packet, 0, packet.size) != packet.size) error("Incomplete packet write")
                            break
                        } catch (error: ErrnoException) {
                            if (error.errno == OsConstants.EINTR) continue
                            if (error.errno != OsConstants.EAGAIN) throw error
                            Os.poll(ready, IO_POLL_MS)
                        }
                    }
                }
            }

            private fun pollFd(events: Int) = arrayOf(StructPollfd().also {
                it.fd = fd
                it.events = events.toShort()
            })
        }
    }

    private class PacketCounters {
        private val values = listOf("UDP sent", "UDP received", "ARP submitted", "ARP received",
            "IPv4 submitted", "IPv4 received", "TCP SYN submitted", "TCP SYN received", "Frame errors",
            "UDP send errors", "Dropped packets").associateWith { AtomicLong() }

        fun snapshot() = values.entries.joinToString("\n") { (name, count) -> "$name: ${count.get()}" }
        fun increment(name: String) { values.getValue(name).incrementAndGet() }

        fun countSyn(packet: ByteArray, direction: String) {
            if (packet.size < 20 || packet[9].toInt() != 6 || packet[0].toInt() ushr 4 != 4) return
            if ((readUnsignedShort(packet, 6) and 0x1fff) != 0) return
            val flags = ((packet[0].toInt() and 15) * 4) + 13
            if (flags in 33 until packet.size && packet[flags].toInt() and 2 != 0) increment("TCP SYN $direction")
        }
    }

    companion object {
        private const val TAG = "ZeroTierTunnel"
        private const val ETHERTYPE_IPV4 = 0x0800
        private const val ETHERTYPE_ARP = 0x0806
        private const val BROADCAST_MAC = 0xffffffffffffL
        private const val AUTHORIZATION_GRACE_MS = 60_000L
        private const val MAX_QUEUED_PACKETS = 256
        private const val MAX_PACKET_SIZE = 65535
        private const val IO_POLL_MS = 200

        // A process-wide owner also prevents an old service instance's final
        // close from racing a new service instance's native initialization.
        private val nativeWorker = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "VLA native worker") }
        private val nodeClock = AtomicLong()

        private fun requireSuccess(result: ResultCode, action: String) {
            if (result != ResultCode.RESULT_OK && result != ResultCode.RESULT_OK_IGNORED) {
                error("Could not $action ($result)")
            }
        }

        private fun checkResult(result: ResultCode, action: String) {
            if (result.isFatal) error("Could not $action ($result)")
        }

        private fun intToIpv4(value: Int) = listOf(24, 16, 8, 0)
            .joinToString(".") { ((value ushr it) and 0xff).toString() }

        private fun readInt(data: ByteArray, offset: Int) =
            ((data[offset].toInt() and 0xff) shl 24) or ((data[offset + 1].toInt() and 0xff) shl 16) or
                ((data[offset + 2].toInt() and 0xff) shl 8) or (data[offset + 3].toInt() and 0xff)

        private fun writeInt(data: ByteArray, offset: Int, value: Int) {
            for (index in 0..3) data[offset + index] = (value ushr (24 - index * 8)).toByte()
        }

        private fun readUnsignedShort(data: ByteArray, offset: Int) =
            ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

        private fun writeMac(data: ByteArray, offset: Int, value: Long) {
            for (index in 0..5) data[offset + index] = (value ushr (40 - index * 8)).toByte()
        }

        private fun writeArpHeader(data: ByteArray, operation: Int) {
            data[1] = 1
            data[2] = 8
            data[4] = 6
            data[5] = 4
            data[7] = operation.toByte()
        }
    }
}

private fun prefixMask(prefix: Int) = if (prefix == 0) 0 else -1 shl (32 - prefix)

internal fun directedBroadcastAddress(address: Int, prefix: Int): Int? =
    if (prefix in 1..30) address or prefixMask(prefix).inv() else null

/** Neighbor state lives on the native worker and uses monotonic time. */
internal class Ipv4Neighbors {
    private data class Neighbor(val mac: Long, val expiresAt: Long)
    private class Pending(val expiresAt: Long, var nextProbeAt: Long) {
        val packets = ArrayList<ByteArray>()
    }

    private val learned = LinkedHashMap<Int, Neighbor>(16, 0.75f, true)
    private val pending = LinkedHashMap<Int, Pending>()

    fun lookup(ip: Int, now: Long): Long? = learned[ip]?.takeIf { it.expiresAt > now }?.mac

    fun queue(ip: Int, packet: ByteArray, now: Long): Boolean {
        pending.entries.removeAll { it.value.expiresAt <= now }
        val request = pending.getOrPut(ip) {
            if (pending.size >= MAX_PENDING_NEIGHBORS) pending.remove(pending.keys.first())
            Pending(now + PENDING_TTL_MS, now)
        }
        if (request.packets.size < MAX_PACKETS_PER_NEIGHBOR) request.packets.add(packet)
        if (now < request.nextProbeAt) return false
        request.nextProbeAt = now + ARP_RETRY_MS
        return true
    }

    fun dueProbes(now: Long): List<Int> {
        learned.entries.removeAll { it.value.expiresAt <= now }
        pending.entries.removeAll { it.value.expiresAt <= now }
        return pending.entries.mapNotNull { (ip, request) ->
            if (now < request.nextProbeAt) null else {
                request.nextProbeAt = now + ARP_RETRY_MS
                ip
            }
        }
    }

    fun learn(ip: Int, mac: Long, now: Long): List<ByteArray> {
        learned[ip] = Neighbor(mac, now + NEIGHBOR_TTL_MS)
        if (learned.size > MAX_LEARNED_NEIGHBORS) learned.remove(learned.keys.first())
        return pending.remove(ip)?.takeIf { it.expiresAt > now }?.packets.orEmpty()
    }

    fun clear() {
        learned.clear()
        pending.clear()
    }

    private companion object {
        const val PENDING_TTL_MS = 5_000L
        const val NEIGHBOR_TTL_MS = 60_000L
        const val ARP_RETRY_MS = 1_000L
        const val MAX_PENDING_NEIGHBORS = 64
        const val MAX_LEARNED_NEIGHBORS = 256
        const val MAX_PACKETS_PER_NEIGHBOR = 16
    }
}
