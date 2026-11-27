package com.nononsense.vla.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class LanHostDiscovery(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val current = AtomicReference<Search?>()
    @Volatile var lastResult = "Auto Find has not run"
        private set

    fun search(onFound: (DiscoveredHost) -> Unit, onFailure: (String) -> Unit) {
        val search = Search()
        if (!current.compareAndSet(null, search)) return
        lastResult = "Searching Wi-Fi"
        Thread({
            val result = runCatching { discover(search) }
            search.close()
            main.post {
                if (!current.compareAndSet(search, null)) return@post
                result.fold(
                    onSuccess = { (host, method) ->
                        lastResult = "Host found over $method at ${host.presenceAddress}"
                        onFound(host)
                    },
                    onFailure = { error ->
                        lastResult = error.message ?: "Host search failed."
                        onFailure(lastResult)
                    },
                )
            }
        }, "VLA host discovery").start()
    }

    fun cancel() {
        current.getAndSet(null)?.close()
    }

    private fun discover(search: Search): Pair<DiscoveredHost, String> {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val wifi = connectivity.activeNetwork?.takeIf { isWifi(connectivity, it) }
            ?: wifiNetworks(connectivity).firstOrNull { isWifi(connectivity, it) }
            ?: throw IOException("Connect the phone to Wi-Fi before searching.")
        val subnets = connectivity.getLinkProperties(wifi)?.linkAddresses.orEmpty().mapNotNull { link ->
            val ipv4 = link.address as? Inet4Address ?: return@mapNotNull null
            if (link.prefixLength !in 1..30) return@mapNotNull null
            Subnet(ipv4.address.ipv4Number(), link.prefixLength)
        }
        if (subnets.isEmpty()) throw IOException("This Wi-Fi connection has no usable IPv4 subnet.")
        val invalid = AtomicInteger()
        val udp = runCatching { findOverUdp(search, wifi, subnets, invalid) }
        udp.getOrNull()?.let { return it to "UDP" }
        val available = subnets.sumOf { it.broadcast - it.first - 2 }
        val tcp = findOverTcp(search, wifi, subnets, invalid)
        tcp?.let { return it to "TCP" }

        val details = mutableListOf("No VLA Host found on Wi-Fi.")
        udp.exceptionOrNull()?.message?.let { details.add("UDP: $it.") }
        val rejected = invalid.get()
        if (rejected != 0) details.add("Rejected $rejected invalid or expired Host announcements.")
        if (available > MAX_TCP_TARGETS) details.add("TCP search checked up to $MAX_TCP_TARGETS nearby addresses.")
        throw IOException(details.joinToString(" "))
    }

    private fun findOverUdp(
        search: Search,
        network: Network,
        subnets: List<Subnet>,
        invalid: AtomicInteger,
    ): DiscoveredHost? {
        val lock = runCatching {
            context.getSystemService(WifiManager::class.java)?.createMulticastLock("NN VLA discovery")?.also {
                it.setReferenceCounted(false)
                it.acquire()
            }
        }.getOrNull()
        try {
            return DatagramSocket(null).use { socket ->
                search.track(socket)
                socket.reuseAddress = true
                try {
                    socket.bind(InetSocketAddress(BEACON_PORT))
                } catch (_: SocketException) {
                    socket.bind(InetSocketAddress(0))
                }
                network.bindSocket(socket)
                socket.broadcast = true
                val targets = (subnets.map { it.broadcast } + 0xffffffffL).distinct()
                val query = QUERY.toByteArray(Charsets.US_ASCII)
                val packet = DatagramPacket(ByteArray(65_535), 65_535)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                var lastSendFailure: IOException? = null
                var sent = false
                while (!search.closed && System.nanoTime() < deadline) {
                    for (target in targets) {
                        try {
                            socket.send(DatagramPacket(query, query.size, target.ipv4Address(), QUERY_PORT))
                            sent = true
                        } catch (error: IOException) {
                            lastSendFailure = error
                        }
                    }
                    val roundEnd = minOf(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(650))
                    while (!search.closed && System.nanoTime() < roundEnd) {
                        val remaining = roundEnd - System.nanoTime()
                        if (remaining <= 0) break
                        socket.soTimeout = TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1).toInt()
                        packet.length = packet.data.size
                        try {
                            socket.receive(packet)
                        } catch (_: SocketTimeoutException) {
                            break
                        }
                        val source = packet.address as? Inet4Address ?: continue
                        if (subnets.none { it.contains(source.address.ipv4Number()) }) continue
                        val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                        try {
                            return RoomMetadata.parse(data, source.hostAddress.orEmpty())
                        } catch (_: IOException) {
                            invalid.incrementAndGet()
                        } catch (_: SecurityException) {
                            invalid.incrementAndGet()
                        }
                    }
                }
                if (!sent) lastSendFailure?.let { throw it }
                null
            }
        } finally {
            runCatching { lock?.release() }
        }
    }

    private fun findOverTcp(
        search: Search,
        network: Network,
        subnets: List<Subnet>,
        invalid: AtomicInteger,
    ): DiscoveredHost? {
        if (search.closed) return null
        val targets = subnets.asSequence()
            .flatMap(Subnet::neighbors)
            .distinct()
            .take(MAX_TCP_TARGETS)
            .toList()
        val executor = Executors.newFixedThreadPool(TCP_WORKERS)
        val completion = ExecutorCompletionService<DiscoveredHost?>(executor)
        try {
            val iterator = targets.iterator()
            fun submitNext() {
                val address = iterator.next().ipv4Address().hostAddress.orEmpty()
                completion.submit {
                    if (search.closed) return@submit null
                    var tracked: Closeable? = null
                    try {
                        RoomMetadata.fetch(address, network = network, timeoutMs = TCP_TIMEOUT_MS) {
                            search.track(it)
                            tracked = it
                        }
                    } catch (_: SecurityException) {
                        invalid.incrementAndGet()
                        null
                    } catch (_: IOException) {
                        null
                    } finally {
                        tracked?.let(search::untrack)
                    }
                }
            }
            var outstanding = minOf(TCP_WORKERS, targets.size)
            repeat(outstanding) { submitNext() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (outstanding != 0 && !search.closed) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return null
                val result = completion.poll(remaining, TimeUnit.NANOSECONDS) ?: return null
                result.get()?.let { return it }
                outstanding--
                if (iterator.hasNext()) {
                    submitNext()
                    outstanding++
                }
            }
        } finally {
            executor.shutdownNow()
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun wifiNetworks(connectivity: ConnectivityManager) = connectivity.allNetworks

    private fun isWifi(connectivity: ConnectivityManager, network: Network): Boolean {
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }

    private class Search : Closeable {
        private val sockets = ConcurrentHashMap.newKeySet<Closeable>()
        @Volatile var closed = false
            private set

        fun track(socket: Closeable) {
            sockets.add(socket)
            if (closed) {
                sockets.remove(socket)
                runCatching { socket.close() }
                throw SocketException("Host search cancelled.")
            }
        }

        fun untrack(socket: Closeable) {
            sockets.remove(socket)
        }

        override fun close() {
            closed = true
            sockets.forEach { runCatching { it.close() } }
            sockets.clear()
        }
    }

    private data class Subnet(val own: Long, val prefix: Int) {
        private val size = 1L shl (32 - prefix)
        val first = own and (size - 1).inv()
        val broadcast = first + size - 1

        fun contains(address: Long) = address in (first + 1) until broadcast && address != own

        fun neighbors(): Sequence<Long> = sequence {
            for (distance in 1L until size) {
                val lower = own - distance
                val higher = own + distance
                if (lower <= first && higher >= broadcast) break
                if (lower > first) yield(lower)
                if (higher < broadcast) yield(higher)
            }
        }
    }

    companion object {
        private const val BEACON_PORT = 47801
        private const val QUERY_PORT = 47806
        private const val QUERY = "GAMER-UTILITY-DISCOVER/1"
        private const val TCP_WORKERS = 32
        private const val MAX_TCP_TARGETS = 512
        private const val TCP_TIMEOUT_MS = 550

        private fun ByteArray.ipv4Number() = fold(0L) { address, octet ->
            (address shl 8) or (octet.toLong() and 0xff)
        }

        private fun Long.ipv4Address(): InetAddress =
            InetAddress.getByAddress(ByteArray(4) { index -> (this ushr ((3 - index) * 8)).toByte() })
    }
}
