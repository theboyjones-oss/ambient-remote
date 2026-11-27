package com.example.ambientremote

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

data class PairedDevice(val name: String, val address: String)

/**
 * Makes the phone act as a Bluetooth keyboard + mouse + media remote (HID device profile).
 * Works with Google TV, Nvidia Shield, Macs and PCs with nothing installed on them.
 * Process-wide singleton so the activity, screensaver and tile share one connection.
 */
@SuppressLint("MissingPermission")
object BluetoothHidRemote {

    enum class Status { UNAVAILABLE, NO_PERMISSION, BT_OFF, STARTING, READY, CONNECTING, CONNECTED }

    var status by mutableStateOf(Status.STARTING)
        private set
    var connectedName by mutableStateOf<String?>(null)
        private set
    var connectedAddress by mutableStateOf<String?>(null)
        private set

    /** True when connected to a Mac/PC: BACK, HOME and SEARCH use computer shortcuts. */
    var hostIsComputer by mutableStateOf(false)
        private set

    val requiredPermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            emptyArray()
        }

    private const val TAG = "BluetoothHidRemote"
    private const val PREFS = "ambient_remote_bt"
    private const val KEY_LAST_HOST = "last_host"

    private const val ID_KEYBOARD = 1
    private const val ID_MOUSE = 2
    private const val ID_CONSUMER = 3
    private const val MOD_GUI = 0x08

    private var appContext: Context? = null
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var registered = false
    private var proxyRequested = false

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    fun hasPermission(context: Context): Boolean =
        requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun adapter(context: Context) =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    /** Safe to call repeatedly (on resume, when settings open, after a permission grant). */
    fun start(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (!hasPermission(app)) {
            postStatus(Status.NO_PERMISSION)
            return
        }
        val adapter = adapter(app)
        if (adapter == null) {
            postStatus(Status.UNAVAILABLE)
            return
        }
        if (!adapter.isEnabled) {
            postStatus(Status.BT_OFF)
            return
        }
        val proxy = hid
        if (proxy != null) {
            if (!registered) register(proxy)
            return
        }
        if (proxyRequested) return
        proxyRequested = true
        postStatus(Status.STARTING)
        val ok = adapter.getProfileProxy(app, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                proxyRequested = false
                val device = proxy as BluetoothHidDevice
                hid = device
                register(device)
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                hid = null
                registered = false
                host = null
                proxyRequested = false
                main.post {
                    connectedName = null
                    connectedAddress = null
                    status = Status.STARTING
                }
            }
        }, BluetoothProfile.HID_DEVICE)
        if (!ok) {
            proxyRequested = false
            postStatus(Status.UNAVAILABLE)
        }
    }

    private fun register(device: BluetoothHidDevice) {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "Ambient Remote",
            "OLED TV remote",
            "Ambient Remote",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            DESCRIPTOR,
        )
        val ok = try {
            device.registerApp(sdp, null, null, executor, callback)
        } catch (e: Exception) {
            Log.w(TAG, "registerApp failed", e)
            false
        }
        if (!ok) postStatus(Status.UNAVAILABLE)
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@BluetoothHidRemote.registered = registered
            if (!registered) {
                host = null
                main.post {
                    connectedName = null
                    connectedAddress = null
                    status = Status.STARTING
                }
                return
            }
            if (host == null) postStatus(Status.READY)
            val target = pluggedDevice ?: lastHost()
            if (target != null && host == null) hid?.connect(target)
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    saveLastHost(device.address)
                    val name = device.name ?: device.address
                    val computer = device.bluetoothClass?.majorDeviceClass ==
                        BluetoothClass.Device.Major.COMPUTER ||
                        name.contains("mac", ignoreCase = true)
                    main.post {
                        hostIsComputer = computer
                        connectedName = name
                        connectedAddress = device.address
                        status = Status.CONNECTED
                    }
                }
                BluetoothProfile.STATE_CONNECTING -> if (host == null) postStatus(Status.CONNECTING)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host?.address == device.address) host = null
                    if (host == null) {
                        main.post {
                            connectedName = null
                            connectedAddress = null
                            status = if (registered) Status.READY else Status.STARTING
                        }
                    }
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val size = when (id.toInt()) {
                ID_MOUSE -> 4
                ID_CONSUMER -> 2
                else -> 8
            }
            hid?.replyReport(device, type, id, ByteArray(size))
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }
    }

    /** Disconnect, unregister the Bluetooth keyboard and release the profile so nothing runs. */
    fun shutdown(context: Context) {
        val proxy = hid
        try {
            host?.let { proxy?.disconnect(it) }
            if (registered) proxy?.unregisterApp()
        } catch (e: Exception) {
            Log.w(TAG, "shutdown", e)
        }
        if (proxy != null) {
            try {
                adapter(context)?.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy)
            } catch (_: Exception) {
            }
        }
        hid = null
        host = null
        registered = false
        proxyRequested = false
        main.post {
            connectedName = null
            connectedAddress = null
            hostIsComputer = false
            status = Status.STARTING
        }
    }

    fun pairedDevices(context: Context): List<PairedDevice> {
        if (!hasPermission(context)) return emptyList()
        return adapter(context)?.bondedDevices.orEmpty()
            .map { PairedDevice(it.name ?: it.address, it.address) }
            .sortedBy { it.name.lowercase() }
    }

    fun connect(context: Context, address: String) {
        val proxy = hid ?: return start(context)
        val device = adapter(context)?.getRemoteDevice(address) ?: return
        host?.let { if (it.address != address) proxy.disconnect(it) }
        postStatus(Status.CONNECTING)
        proxy.connect(device)
    }

    fun disconnect() {
        val h = host ?: return
        hid?.disconnect(h)
    }

    private fun lastHost(): BluetoothDevice? {
        val ctx = appContext ?: return null
        val address = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_HOST, null) ?: return null
        return try {
            adapter(ctx)?.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun saveLastHost(address: String) {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putString(KEY_LAST_HOST, address)?.apply()
    }

    private fun postStatus(s: Status) {
        if (Looper.myLooper() == Looper.getMainLooper()) status = s else main.post { status = s }
    }

    // ------------------------------------------------------------ command mapping

    /** Accepts the same command strings as the UDP client. */
    fun send(command: String) {
        if (host == null) return
        io.launch {
            try {
                perform(command)
            } catch (e: Exception) {
                Log.w(TAG, "HID send failed: $command", e)
            }
        }
    }

    private suspend fun perform(cmd: String) {
        when {
            cmd == "UP" -> tapKey(0x52)
            cmd == "DOWN" -> tapKey(0x51)
            cmd == "LEFT" -> tapKey(0x50)
            cmd == "RIGHT" -> tapKey(0x4F)
            cmd == "SELECT" -> tapKey(0x28)
            cmd == "KEY_ENTER" -> tapKey(0x28)
            cmd == "KEY_BACKSPACE" -> tapKey(0x2A)
            // Mac/PC: Escape for back, Cmd/Win+Tab for home (app switch), Cmd/Win+Space for search.
            cmd == "BACK" -> if (hostIsComputer) tapKey(0x29) else tapConsumer(0x0224)
            cmd == "HOME" -> if (hostIsComputer) tapKey(0x2B, MOD_GUI) else tapConsumer(0x0223)
            cmd == "KEY_SEARCH" -> if (hostIsComputer) tapKey(0x2C, MOD_GUI) else tapConsumer(0x0221)
            cmd == "POWER" -> if (!hostIsComputer) tapConsumer(0x0030)
            cmd == "REWIND" -> tapConsumer(0x00B4)
            cmd == "FAST_FORWARD" -> tapConsumer(0x00B3)
            cmd == "PLAY_PAUSE" -> tapConsumer(0x00CD)
            cmd == "MUTE" -> tapConsumer(0x00E2)
            cmd == "VOL_UP" -> tapConsumer(0x00E9)
            cmd == "VOL_DOWN" -> tapConsumer(0x00EA)
            cmd == "MOUSE_CLICK" -> click(0x01)
            cmd == "MOUSE_RIGHT_CLICK" -> click(0x02)
            cmd == "MOUSE_CENTER" -> click(0x04)
            cmd == "MOUSE_SCROLL_UP" -> mouse(0, 0, 0, 1)
            cmd == "MOUSE_SCROLL_DOWN" -> mouse(0, 0, 0, -1)
            cmd.startsWith("MOUSE_MOVE:") -> {
                val parts = cmd.removePrefix("MOUSE_MOVE:").split(",")
                var dx = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
                var dy = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                while (dx != 0 || dy != 0) {
                    val sx = dx.coerceIn(-127, 127)
                    val sy = dy.coerceIn(-127, 127)
                    mouse(0, sx, sy, 0)
                    dx -= sx
                    dy -= sy
                }
            }
            cmd.startsWith("KEY_CHAR:") -> typeText(cmd.removePrefix("KEY_CHAR:"))
            cmd.startsWith("KEY_TEXT:") -> typeText(cmd.removePrefix("KEY_TEXT:"))
        }
    }

    private fun report(id: Int, data: ByteArray): Boolean {
        val h = host ?: return false
        return hid?.sendReport(h, id, data) == true
    }

    private suspend fun tapKey(usage: Int, modifiers: Int = 0) {
        report(ID_KEYBOARD, byteArrayOf(modifiers.toByte(), 0, usage.toByte(), 0, 0, 0, 0, 0))
        delay(8)
        report(ID_KEYBOARD, ByteArray(8))
        delay(6)
    }

    private suspend fun tapConsumer(usage: Int) {
        report(ID_CONSUMER, byteArrayOf((usage and 0xFF).toByte(), ((usage shr 8) and 0xFF).toByte()))
        delay(10)
        report(ID_CONSUMER, ByteArray(2))
        delay(6)
    }

    private fun mouse(buttons: Int, dx: Int, dy: Int, wheel: Int) {
        report(ID_MOUSE, byteArrayOf(buttons.toByte(), dx.toByte(), dy.toByte(), wheel.toByte()))
    }

    private suspend fun click(button: Int) {
        mouse(button, 0, 0, 0)
        delay(12)
        mouse(0, 0, 0, 0)
        delay(6)
    }

    private suspend fun typeText(text: String) {
        for (ch in text) {
            val (usage, shift) = charToHid(ch) ?: continue
            tapKey(usage, if (shift) 0x02 else 0)
        }
    }

    /** US keyboard layout (what Google TV / Shield assume for a new keyboard). */
    private fun charToHid(c: Char): Pair<Int, Boolean>? = when (c) {
        in 'a'..'z' -> (0x04 + (c - 'a')) to false
        in 'A'..'Z' -> (0x04 + (c - 'A')) to true
        in '1'..'9' -> (0x1E + (c - '1')) to false
        '0' -> 0x27 to false
        '\n' -> 0x28 to false
        '\t' -> 0x2B to false
        ' ' -> 0x2C to false
        '-' -> 0x2D to false
        '_' -> 0x2D to true
        '=' -> 0x2E to false
        '+' -> 0x2E to true
        '[' -> 0x2F to false
        '{' -> 0x2F to true
        ']' -> 0x30 to false
        '}' -> 0x30 to true
        '\\' -> 0x31 to false
        '|' -> 0x31 to true
        ';' -> 0x33 to false
        ':' -> 0x33 to true
        '\'' -> 0x34 to false
        '"' -> 0x34 to true
        '`' -> 0x35 to false
        '~' -> 0x35 to true
        ',' -> 0x36 to false
        '<' -> 0x36 to true
        '.' -> 0x37 to false
        '>' -> 0x37 to true
        '/' -> 0x38 to false
        '?' -> 0x38 to true
        '!' -> 0x1E to true
        '@' -> 0x1F to true
        '#' -> 0x20 to true
        '$' -> 0x21 to true
        '%' -> 0x22 to true
        '^' -> 0x23 to true
        '&' -> 0x24 to true
        '*' -> 0x25 to true
        '(' -> 0x26 to true
        ')' -> 0x27 to true
        else -> null
    }

    /** Keyboard (id 1) + mouse with wheel (id 2) + consumer/media keys (id 3). */
    private val DESCRIPTOR: ByteArray = intArrayOf(
        // Keyboard
        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, ID_KEYBOARD,
        0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
        0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
        0xC0,
        // Mouse
        0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, ID_MOUSE, 0x09, 0x01, 0xA1, 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x05, 0x81, 0x01,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
        0xC0, 0xC0,
        // Consumer control
        0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, ID_CONSUMER,
        0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03, 0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
        0xC0,
    ).map { it.toByte() }.toByteArray()
}
