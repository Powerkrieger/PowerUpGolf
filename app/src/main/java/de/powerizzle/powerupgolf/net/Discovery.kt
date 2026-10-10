package de.powerizzle.powerupgolf.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Finding games on the local network without typing IPs: joiners broadcast a UDP question,
 * hosts answer with a [Beacon]. Works on a shared Wi-Fi and on a phone's hotspot; if a router
 * blocks broadcasts, players can still type the host's address.
 */
object Discovery {
    const val PORT = 47411
    private const val ASK = "POWERUPGOLF?"
    private const val ANSWER = "POWERUPGOLF!"

    @Serializable
    data class Beacon(val name: String, val players: Int, val open: Boolean, val version: Int = PROTOCOL_VERSION)

    data class FoundHost(val address: String, val beacon: Beacon, val seenAt: Long)

    /** Host side: answers questions until cancelled. */
    suspend fun respond(beacon: () -> Beacon) {
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(PORT))
        }
        val buf = ByteArray(512)
        try {
            while (true) {
                val packet = DatagramPacket(buf, buf.size)
                cancellableBlocking({ socket.close() }) { socket.receive(packet) }
                if (String(packet.data, 0, packet.length) != ASK) continue
                val reply = (ANSWER + wireJson.encodeToString(Beacon.serializer(), beacon())).toByteArray()
                runCatching { socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port)) }
            }
        } finally {
            socket.close()
        }
    }

    /** Joiner side: the hosts that answered within the last few seconds, refreshed continuously. */
    fun scan(): Flow<List<FoundHost>> = channelFlow {
        val socket = DatagramSocket().apply {
            broadcast = true
            soTimeout = 400
        }
        val found = LinkedHashMap<String, FoundHost>()
        launch {
            val ask = ASK.toByteArray()
            while (isActive) {
                for (target in broadcastAddresses()) {
                    runCatching { socket.send(DatagramPacket(ask, ask.size, target, PORT)) }
                }
                delay(1500)
            }
        }
        val buf = ByteArray(1024)
        try {
            while (isActive) {
                val packet = DatagramPacket(buf, buf.size)
                val got = try {
                    socket.receive(packet)
                    true
                } catch (_: SocketTimeoutException) {
                    false
                }
                val now = System.currentTimeMillis()
                if (got) {
                    val text = String(packet.data, 0, packet.length)
                    if (text.startsWith(ANSWER)) {
                        val beacon = runCatching { wireJson.decodeFromString(Beacon.serializer(), text.removePrefix(ANSWER)) }.getOrNull()
                        val address = packet.address.hostAddress
                        if (beacon != null && address != null) found[address] = FoundHost(address, beacon, now)
                    }
                }
                found.values.removeAll { now - it.seenAt > 5000 }
                send(found.values.toList())
            }
        } finally {
            socket.close()
        }
    }.flowOn(Dispatchers.IO)

    private fun broadcastAddresses(): List<InetAddress> {
        val perInterface = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .mapNotNull { it.broadcast }
        }.getOrDefault(emptyList())
        return (perInterface + InetAddress.getByName("255.255.255.255")).distinct()
    }

    /** This device's IPv4 addresses, shown in the lobby so others can type one in. */
    fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())
}
