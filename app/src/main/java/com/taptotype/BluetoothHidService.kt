package com.taptotype

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

/**
 * BluetoothHidService manages the Bluetooth HID Device profile registration
 * and sends keyboard HID reports to the connected Windows host.
 *
 * Connection Flow:
 *   1. initialize() → getProfileProxy() → onServiceConnected → registerApp()
 *   2. onAppStatusChanged(registered=true) → HID ready
 *   3. connectToDevice(device) → connect() → onConnectionStateChanged(CONNECTED)
 *   4. sendKeyPress() / sendString() / sendEnter() / sendBackspace()
 */
@SuppressLint("MissingPermission")
class BluetoothHidService(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothHidService"
        private const val KEY_PRESS_DELAY_MS = 20L
        private const val KEY_SEQUENCE_DELAY_MS = 30L
        private const val CONNECT_RETRY_DELAY_MS = 1500L
        private const val CONNECTION_TIMEOUT_MS = 10000L // 10 seconds
    }

    // Bluetooth components
    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    // HID Device profile proxy
    private var hidDevice: BluetoothHidDevice? = null

    // Currently connected host device (the Windows PC)
    private var connectedDevice: BluetoothDevice? = null

    // Connection state
    var isRegistered: Boolean = false
        private set
    var isConnected: Boolean = false
        private set

    // Listener for connection state changes (Boolean=connected, String=status message)
    var onConnectionStateChanged: ((Boolean, String) -> Unit)? = null

    // Listener for detailed error/info messages (for UI display)
    var onDetailedStatus: ((String) -> Unit)? = null

    // Handler for main thread operations
    private val mainHandler = Handler(Looper.getMainLooper())

    // Executor for sending key events with delays
    private val keyExecutor = Executors.newSingleThreadExecutor()

    // Pending device to connect after registration
    private var pendingConnectDevice: BluetoothDevice? = null

    // Connection timeout runnable
    private var connectionTimeoutRunnable: Runnable? = null

    // ============================================================
    // Diagnostic info
    // ============================================================

    /**
     * Returns a human-readable diagnostic string of the current state.
     */
    fun getDiagnosticInfo(): String {
        val adapter = bluetoothAdapter
        val sb = StringBuilder()
        sb.appendLine("=== Bluetooth HID Diagnostics ===")
        sb.appendLine("Bluetooth adapter: ${if (adapter != null) "Available" else "NOT AVAILABLE"}")
        sb.appendLine("Bluetooth enabled: ${adapter?.isEnabled ?: false}")
        sb.appendLine("HID proxy obtained: ${hidDevice != null}")
        sb.appendLine("HID app registered: $isRegistered")
        sb.appendLine("Connected: $isConnected")
        sb.appendLine("Connected device: ${connectedDevice?.name ?: connectedDevice?.address ?: "None"}")
        sb.appendLine("Pending connect: ${pendingConnectDevice?.name ?: "None"}")

        if (adapter != null && adapter.isEnabled) {
            val bondedDevices = adapter.bondedDevices
            sb.appendLine("Paired devices (${bondedDevices.size}):")
            for (device in bondedDevices) {
                val bondStr = when (device.bondState) {
                    BluetoothDevice.BOND_BONDED -> "BONDED"
                    BluetoothDevice.BOND_BONDING -> "BONDING"
                    BluetoothDevice.BOND_NONE -> "NONE"
                    else -> "UNKNOWN"
                }
                val typeStr = when (device.type) {
                    BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic"
                    BluetoothDevice.DEVICE_TYPE_LE -> "BLE"
                    BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual"
                    BluetoothDevice.DEVICE_TYPE_UNKNOWN -> "Unknown"
                    else -> "?"
                }
                sb.appendLine("  • ${device.name ?: "?"} (${device.address}) — $bondStr, $typeStr")
            }

            // Check if any HID devices are currently connected via the proxy
            hidDevice?.let { hid ->
                val connectedHidDevices = hid.getConnectedDevices()
                sb.appendLine("Currently connected HID devices (${connectedHidDevices.size}):")
                for (d in connectedHidDevices) {
                    sb.appendLine("  • ${d.name ?: "?"} (${d.address})")
                }
            }
        }

        sb.appendLine("=================================")
        return sb.toString()
    }

    // ============================================================
    // HID Descriptor for a standard keyboard
    // ============================================================

    private val hidDescriptor: ByteArray = byteArrayOf(
        0x05.toByte(), 0x01.toByte(),   // Usage Page (Generic Desktop)
        0x09.toByte(), 0x06.toByte(),   // Usage (Keyboard)
        0xA1.toByte(), 0x01.toByte(),   // Collection (Application)

        // Modifier keys
        0x05.toByte(), 0x07.toByte(),
        0x19.toByte(), 0xE0.toByte(),
        0x29.toByte(), 0xE7.toByte(),
        0x15.toByte(), 0x00.toByte(),
        0x25.toByte(), 0x01.toByte(),
        0x75.toByte(), 0x01.toByte(),
        0x95.toByte(), 0x08.toByte(),
        0x81.toByte(), 0x02.toByte(),

        // Reserved byte
        0x75.toByte(), 0x08.toByte(),
        0x95.toByte(), 0x01.toByte(),
        0x81.toByte(), 0x01.toByte(),

        // LED output report
        0x05.toByte(), 0x08.toByte(),
        0x19.toByte(), 0x01.toByte(),
        0x29.toByte(), 0x05.toByte(),
        0x75.toByte(), 0x01.toByte(),
        0x95.toByte(), 0x05.toByte(),
        0x91.toByte(), 0x02.toByte(),

        // LED padding
        0x75.toByte(), 0x03.toByte(),
        0x95.toByte(), 0x01.toByte(),
        0x91.toByte(), 0x01.toByte(),

        // Key codes (6 simultaneous keys)
        0x05.toByte(), 0x07.toByte(),
        0x19.toByte(), 0x00.toByte(),
        0x29.toByte(), 0x65.toByte(),
        0x15.toByte(), 0x00.toByte(),
        0x25.toByte(), 0x65.toByte(),
        0x75.toByte(), 0x08.toByte(),
        0x95.toByte(), 0x06.toByte(),
        0x81.toByte(), 0x00.toByte(),

        0xC0.toByte()                    // End Collection
    )

    // ============================================================
    // HID Device Callback
    // ============================================================

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            super.onAppStatusChanged(pluggedDevice, registered)
            val msg = "HID App status: registered=$registered, device=${pluggedDevice?.name ?: pluggedDevice?.address ?: "none"}"
            Log.d(TAG, msg)
            postDetailedStatus(msg)
            isRegistered = registered

            if (registered) {
                Log.i(TAG, "✅ HID Keyboard registered successfully")
                postDetailedStatus("✅ HID Keyboard registered — ready to connect")
                notifyConnectionState()

                // If there's a pending connect, try it now
                pendingConnectDevice?.let { device ->
                    val pendingMsg = "Attempting queued connection to: ${device.name ?: device.address}"
                    Log.d(TAG, pendingMsg)
                    postDetailedStatus(pendingMsg)
                    pendingConnectDevice = null
                    mainHandler.postDelayed({
                        connectToDevice(device)
                    }, 500)
                }
            } else {
                Log.w(TAG, "❌ HID Keyboard unregistered")
                postDetailedStatus("❌ HID Keyboard was unregistered")
                isConnected = false
                connectedDevice = null
                notifyConnectionState()
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            super.onConnectionStateChanged(device, state)
            val stateStr = when (state) {
                BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
                BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
                else -> "UNKNOWN ($state)"
            }
            val deviceName = device?.name ?: device?.address ?: "unknown"
            val msg = "Connection: $stateStr → $deviceName"
            Log.d(TAG, msg)
            postDetailedStatus(msg)

            // Cancel connection timeout since we got a callback
            cancelConnectionTimeout()

            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    isConnected = true
                    Log.i(TAG, "✅ Connected to: $deviceName")
                    postDetailedStatus("✅ Connected as keyboard to $deviceName!")
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    Log.i(TAG, "⏳ Connecting to: $deviceName")
                    postDetailedStatus("⏳ Connecting to $deviceName...")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (device?.address == connectedDevice?.address || connectedDevice == null) {
                        connectedDevice = null
                        isConnected = false
                        Log.i(TAG, "❌ Disconnected from: $deviceName")
                        postDetailedStatus("❌ Disconnected from $deviceName")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTING -> {
                    Log.i(TAG, "⏳ Disconnecting from: $deviceName")
                    postDetailedStatus("⏳ Disconnecting from $deviceName...")
                }
            }
            notifyConnectionState()
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            super.onGetReport(device, type, id, bufferSize)
            Log.d(TAG, "onGetReport: type=$type, id=$id, bufferSize=$bufferSize")
            hidDevice?.replyReport(device, type, id, ByteArray(8))
        }

        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            super.onSetReport(device, type, id, data)
            Log.d(TAG, "onSetReport: type=$type, id=$id")
            hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }

        override fun onInterruptData(device: BluetoothDevice?, reportId: Byte, data: ByteArray?) {
            super.onInterruptData(device, reportId, data)
            Log.d(TAG, "onInterruptData: reportId=$reportId")
        }
    }

    // ============================================================
    // Profile Service Listener
    // ============================================================

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            Log.d(TAG, "HID Device service connected (profile=$profile)")
            postDetailedStatus("HID profile service connected")
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as BluetoothHidDevice
                Log.d(TAG, "HID Device proxy obtained ✅")
                postDetailedStatus("HID proxy obtained — registering app...")
                registerHidDevice()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            Log.d(TAG, "HID Device service disconnected")
            postDetailedStatus("⚠️ HID profile service disconnected")
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null
                isRegistered = false
                isConnected = false
                connectedDevice = null
                notifyConnectionState()
            }
        }
    }

    // ============================================================
    // Public API
    // ============================================================

    fun initialize(): Boolean {
        val adapter = bluetoothAdapter ?: run {
            Log.e(TAG, "Bluetooth not available")
            postDetailedStatus("❌ Bluetooth adapter not available on this device")
            return false
        }

        if (!adapter.isEnabled) {
            Log.e(TAG, "Bluetooth is not enabled")
            postDetailedStatus("❌ Bluetooth is not enabled")
            return false
        }

        // If already registered, unregister first for a clean state
        hidDevice?.let { hid ->
            try {
                Log.d(TAG, "Cleaning up previous HID registration...")
                postDetailedStatus("Cleaning up previous session...")
                hid.unregisterApp()
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering previous HID app", e)
            }
        }

        Log.d(TAG, "Requesting HID Device profile proxy...")
        postDetailedStatus("Requesting Bluetooth HID profile...")
        val result = adapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)

        if (!result) {
            Log.e(TAG, "❌ getProfileProxy returned false — device may not support HID_DEVICE profile")
            postDetailedStatus("❌ Your device does not support the Bluetooth HID Device profile.\nThis feature requires manufacturer support.")
        } else {
            Log.d(TAG, "getProfileProxy returned true — waiting for service connection callback...")
            postDetailedStatus("Waiting for HID profile service...")
        }

        return result
    }

    private fun registerHidDevice() {
        val hid = hidDevice ?: run {
            Log.e(TAG, "HID Device proxy not available")
            postDetailedStatus("❌ HID proxy not available — cannot register")
            return
        }

        val sdpRecord = BluetoothHidDeviceAppSdpSettings(
            "TapToType Keyboard",
            "Bluetooth HID Keyboard",
            "Android",
            BluetoothHidDevice.SUBCLASS1_KEYBOARD,
            hidDescriptor
        )

        val qosOut = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800, 9, 0, 11250, 11250
        )

        Log.d(TAG, "Calling registerApp()...")
        postDetailedStatus("Registering as HID keyboard...")
        val result = hid.registerApp(sdpRecord, null, qosOut, Executors.newSingleThreadExecutor(), hidCallback)
        Log.d(TAG, "registerApp result: $result")

        if (!result) {
            Log.e(TAG, "❌ registerApp() returned false")
            postDetailedStatus("❌ Failed to register HID keyboard.\nYour device may not fully support the Bluetooth HID Device profile.\nSome manufacturers disable this feature.")
            mainHandler.post {
                onConnectionStateChanged?.invoke(false, "HID registration failed — device may not support HID")
            }
        } else {
            postDetailedStatus("registerApp() accepted — waiting for confirmation...")
        }
    }

    /**
     * Connect to a specific Bluetooth device (the Windows PC).
     * Returns a detailed status string for the UI.
     */
    fun connectToDevice(device: BluetoothDevice): ConnectResult {
        val deviceName = device.name ?: device.address
        Log.d(TAG, "connectToDevice called for: $deviceName (${device.address})")
        Log.d(TAG, "  Current state: hidDevice=${hidDevice != null}, isRegistered=$isRegistered, isConnected=$isConnected")

        // 1) Check if HID proxy is available
        val hid = hidDevice
        if (hid == null) {
            val msg = "HID service not ready. Queued connection — will retry after initialization."
            Log.w(TAG, msg)
            postDetailedStatus("⏳ $msg")
            pendingConnectDevice = device

            // Try to re-initialize
            val initResult = initialize()
            return if (initResult) {
                ConnectResult(false, "HID service initializing... connection queued for $deviceName")
            } else {
                pendingConnectDevice = null
                ConnectResult(false, "Failed to initialize Bluetooth HID service. Your device may not support HID.")
            }
        }

        // 2) Check if HID app is registered
        if (!isRegistered) {
            val msg = "HID app not registered yet. Connection queued — will connect after registration."
            Log.w(TAG, msg)
            postDetailedStatus("⏳ $msg")
            pendingConnectDevice = device
            return ConnectResult(false, "HID registering... connection queued for $deviceName.\nPlease wait a moment and try again.")
        }

        // 3) Check bond state
        val bondState = device.bondState
        Log.d(TAG, "  Bond state: $bondState")
        if (bondState != BluetoothDevice.BOND_BONDED) {
            val bondStr = when (bondState) {
                BluetoothDevice.BOND_NONE -> "not paired"
                BluetoothDevice.BOND_BONDING -> "pairing in progress"
                else -> "unknown bond state ($bondState)"
            }
            val msg = "Device $deviceName is $bondStr. Please pair first via Bluetooth settings."
            Log.e(TAG, msg)
            postDetailedStatus("❌ $msg")
            mainHandler.post {
                onConnectionStateChanged?.invoke(false, "Not paired with $deviceName")
            }
            return ConnectResult(false, msg)
        }

        // 4) Check device type
        val deviceType = device.type
        Log.d(TAG, "  Device type: $deviceType")
        val typeStr = when (deviceType) {
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic"
            BluetoothDevice.DEVICE_TYPE_LE -> "BLE-only"
            BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual (Classic+BLE)"
            else -> "Unknown"
        }
        postDetailedStatus("Device: $deviceName — Type: $typeStr, Bonded: yes")

        // BLE-only devices won't work with HID classic
        if (deviceType == BluetoothDevice.DEVICE_TYPE_LE) {
            val msg = "$deviceName is BLE-only. Bluetooth HID requires Classic Bluetooth. This device cannot be used."
            Log.e(TAG, msg)
            postDetailedStatus("❌ $msg")
            return ConnectResult(false, msg)
        }

        // 5) Check if already connected to something — disconnect first
        val connectedHidDevices = hid.getConnectedDevices()
        Log.d(TAG, "  Currently connected HID devices: ${connectedHidDevices.size}")
        if (connectedHidDevices.isNotEmpty()) {
            for (d in connectedHidDevices) {
                if (d.address == device.address) {
                    // Already connected to this device!
                    val msg = "Already connected to $deviceName!"
                    Log.d(TAG, msg)
                    connectedDevice = device
                    isConnected = true
                    notifyConnectionState()
                    return ConnectResult(true, msg)
                }
                Log.d(TAG, "  Disconnecting stale device: ${d.name ?: d.address}")
                postDetailedStatus("Disconnecting from ${d.name ?: d.address} first...")
                hid.disconnect(d)
            }
            // Wait a bit then retry
            postDetailedStatus("⏳ Disconnected stale device. Retrying in 1.5s...")
            mainHandler.postDelayed({
                doConnect(hid, device)
            }, CONNECT_RETRY_DELAY_MS)
            return ConnectResult(true, "Disconnecting previous device, then connecting to $deviceName...")
        }

        // 6) Attempt connection
        return doConnect(hid, device)
    }

    private fun doConnect(hid: BluetoothHidDevice, device: BluetoothDevice): ConnectResult {
        val deviceName = device.name ?: device.address
        Log.d(TAG, ">>> Calling hid.connect(${device.address})...")
        postDetailedStatus("⏳ Sending connect request to $deviceName...")

        mainHandler.post {
            onConnectionStateChanged?.invoke(false, "Connecting to $deviceName...")
        }

        val result: Boolean
        try {
            result = hid.connect(device)
        } catch (e: Exception) {
            val msg = "Exception during connect(): ${e.message}"
            Log.e(TAG, msg, e)
            postDetailedStatus("❌ $msg")
            mainHandler.post {
                onConnectionStateChanged?.invoke(false, "Connection error: ${e.message}")
            }
            return ConnectResult(false, msg)
        }

        Log.d(TAG, ">>> connect() returned: $result")

        if (result) {
            postDetailedStatus("✅ Connect request accepted — waiting for PC to respond...")

            // Start connection timeout
            startConnectionTimeout(deviceName)

            return ConnectResult(true, "Connection request sent to $deviceName.\nWaiting for response...")
        } else {
            // connect() returned false — detailed diagnosis
            val diag = StringBuilder()
            diag.appendLine("❌ connect() returned false for $deviceName")
            diag.appendLine("")
            diag.appendLine("Possible causes:")
            diag.appendLine("• The PC may need to be re-paired")
            diag.appendLine("• Another app may be using the HID profile")
            diag.appendLine("• Your phone's Bluetooth stack may need a restart")
            diag.appendLine("")
            diag.appendLine("Try these steps:")
            diag.appendLine("1. On PC: Remove the phone from Bluetooth devices")
            diag.appendLine("2. On Phone: Remove the PC from Bluetooth settings")
            diag.appendLine("3. Restart Bluetooth on both devices")
            diag.appendLine("4. Re-pair from scratch")
            diag.appendLine("5. In the app: Settings → Re-initialize Bluetooth HID")

            val diagStr = diag.toString()
            Log.e(TAG, diagStr)
            postDetailedStatus(diagStr)

            mainHandler.post {
                onConnectionStateChanged?.invoke(false, "Connection rejected — try re-pairing")
            }

            return ConnectResult(false, diagStr)
        }
    }

    private fun startConnectionTimeout(deviceName: String) {
        cancelConnectionTimeout()
        connectionTimeoutRunnable = Runnable {
            if (!isConnected) {
                val msg = "⏰ Connection to $deviceName timed out after ${CONNECTION_TIMEOUT_MS / 1000}s.\n\n" +
                        "The PC did not respond. Try:\n" +
                        "• Make sure the PC's Bluetooth is on\n" +
                        "• Remove and re-pair the devices\n" +
                        "• Restart Bluetooth on both devices\n" +
                        "• Move closer to the PC"
                Log.w(TAG, msg)
                postDetailedStatus(msg)
                mainHandler.post {
                    onConnectionStateChanged?.invoke(false, "Connection timed out — PC did not respond")
                }
            }
        }
        mainHandler.postDelayed(connectionTimeoutRunnable!!, CONNECTION_TIMEOUT_MS)
    }

    private fun cancelConnectionTimeout() {
        connectionTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        connectionTimeoutRunnable = null
    }

    fun disconnect() {
        val hid = hidDevice ?: return
        val device = connectedDevice ?: return
        Log.d(TAG, "Disconnecting from: ${device.name ?: device.address}")
        postDetailedStatus("Disconnecting from ${device.name ?: device.address}...")
        hid.disconnect(device)
    }

    fun getPairedDevices(): Set<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices ?: emptySet()
    }

    // ============================================================
    // Key Event Sending
    // ============================================================

    fun sendKeyPress(char: Char): Boolean {
        if (!isConnected) {
            Log.w(TAG, "Not connected, cannot send key")
            return false
        }
        val keyEvent = HidKeyMapper.charToHidKeyEvent(char) ?: run {
            Log.w(TAG, "Unsupported character: '$char' (${char.code})")
            return false
        }
        return sendHidReport(keyEvent)
    }

    fun sendBackspace(): Boolean {
        if (!isConnected) return false
        val hid = hidDevice ?: return false
        val device = connectedDevice ?: return false

        keyExecutor.execute {
            try {
                hid.sendReport(device, 0, HidKeyMapper.createBackspaceReport())
                Thread.sleep(KEY_PRESS_DELAY_MS)
                hid.sendReport(device, 0, HidKeyMapper.createKeyUpReport())
            } catch (e: Exception) {
                Log.e(TAG, "Error sending backspace", e)
            }
        }
        return true
    }

    fun sendString(text: String) {
        if (!isConnected) {
            Log.w(TAG, "Not connected, cannot send string")
            return
        }
        keyExecutor.execute {
            for (char in text) {
                val keyEvent = HidKeyMapper.charToHidKeyEvent(char)
                if (keyEvent != null) {
                    sendHidReportSync(keyEvent)
                    Thread.sleep(KEY_SEQUENCE_DELAY_MS)
                } else {
                    Log.w(TAG, "Skipping unsupported character: '$char'")
                }
            }
        }
    }

    fun sendEnter(): Boolean {
        if (!isConnected) return false
        val hid = hidDevice ?: return false
        val device = connectedDevice ?: return false

        keyExecutor.execute {
            try {
                hid.sendReport(device, 0, HidKeyMapper.createEnterReport())
                Thread.sleep(KEY_PRESS_DELAY_MS)
                hid.sendReport(device, 0, HidKeyMapper.createKeyUpReport())
            } catch (e: Exception) {
                Log.e(TAG, "Error sending enter", e)
            }
        }
        return true
    }

    // ============================================================
    // Internal helpers
    // ============================================================

    private fun sendHidReport(event: HidKeyMapper.HidKeyEvent): Boolean {
        val hid = hidDevice ?: return false
        val device = connectedDevice ?: return false

        keyExecutor.execute {
            try {
                hid.sendReport(device, 0, HidKeyMapper.createKeyDownReport(event))
                Thread.sleep(KEY_PRESS_DELAY_MS)
                hid.sendReport(device, 0, HidKeyMapper.createKeyUpReport())
            } catch (e: Exception) {
                Log.e(TAG, "Error sending HID report", e)
            }
        }
        return true
    }

    private fun sendHidReportSync(event: HidKeyMapper.HidKeyEvent) {
        val hid = hidDevice ?: return
        val device = connectedDevice ?: return

        try {
            hid.sendReport(device, 0, HidKeyMapper.createKeyDownReport(event))
            Thread.sleep(KEY_PRESS_DELAY_MS)
            hid.sendReport(device, 0, HidKeyMapper.createKeyUpReport())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending HID report (sync)", e)
        }
    }

    private fun notifyConnectionState() {
        mainHandler.post {
            val statusMessage = when {
                isConnected -> "Connected as Keyboard to ${connectedDevice?.name ?: connectedDevice?.address ?: "Unknown"}"
                isRegistered -> "Ready — Tap 'Connect to Device'"
                else -> "Not Connected"
            }
            onConnectionStateChanged?.invoke(isConnected, statusMessage)
        }
    }

    private fun postDetailedStatus(message: String) {
        mainHandler.post {
            onDetailedStatus?.invoke(message)
        }
    }

    fun cleanup() {
        cancelConnectionTimeout()
        try {
            hidDevice?.let { hid ->
                connectedDevice?.let { device ->
                    hid.disconnect(device)
                }
                hid.unregisterApp()
            }
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
        hidDevice = null
        connectedDevice = null
        isRegistered = false
        isConnected = false
        pendingConnectDevice = null
    }

    // ============================================================
    // Result class
    // ============================================================

    data class ConnectResult(
        val accepted: Boolean,
        val message: String
    )
}
