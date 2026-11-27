package com.example.ambientremote

enum class Transport { BLUETOOTH, WIFI }

/** Sends each command over the transport chosen in settings. */
class CommandRouter(
    private val settings: RemoteSettings,
    private val udp: UdpRemoteClient,
) {
    fun send(command: String) {
        when (settings.transport) {
            Transport.BLUETOOTH -> BluetoothHidRemote.send(command)
            Transport.WIFI -> udp.send(command)
        }
    }

    fun close() = udp.close()
}
