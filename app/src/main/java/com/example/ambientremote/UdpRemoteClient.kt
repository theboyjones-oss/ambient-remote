package com.example.ambientremote

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Fire-and-forget UDP sender. Every command is sent as a single UTF-8 datagram.
 * Sends run on a single-lane view of Dispatchers.IO so commands arrive in order.
 * The target is read on every send, so changes in settings apply immediately.
 */
class UdpRemoteClient(
    private val scope: CoroutineScope,
    private val target: () -> Pair<String, Int>,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(1)

    private var socket: DatagramSocket? = null
    private var cachedHost: String? = null
    private var cachedAddress: InetAddress? = null

    fun send(command: String) {
        val (host, port) = target()
        scope.launch(io) {
            try {
                val address = resolve(host)
                val bytes = command.toByteArray(Charsets.UTF_8)
                val s = socket ?: DatagramSocket().also { socket = it }
                s.send(DatagramPacket(bytes, bytes.size, address, port))
            } catch (e: Exception) {
                Log.w(TAG, "UDP send failed ($command -> $host:$port): ${e.message}")
                socket?.close()
                socket = null
                cachedHost = null
            }
        }
    }

    private fun resolve(host: String): InetAddress {
        val cached = cachedAddress
        if (cached != null && cachedHost == host) return cached
        return InetAddress.getByName(host).also {
            cachedHost = host
            cachedAddress = it
        }
    }

    fun close() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    companion object {
        private const val TAG = "UdpRemoteClient"
        const val DEFAULT_IP = "192.168.1.100"
        const val DEFAULT_PORT = 9876
    }
}
