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
        private const val CONNECTION_TIMEOUT_MS = 10000L
        private const val REGISTER_TIMEOUT_MS = 3000L  // Retry registration after 3s
        private const val MAX_REGISTER_RETRIES = 3
    }

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null

    var isRegistered: Boolean = false
        private set
    var isConnected: Boolean = false
        private set

    var onConnectionStateChanged: ((Boolean, String) -> Unit)? = null
    var onDetailedStatus: ((String) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val keyExecutor = Executors.newSingleThreadExecutor()
    // Use a persistent executor for HID callbacks (some devices need this)
    private val hidCallbackExecutor = Executors.newSingleThreadExecutor()

    private var pendingConnectDevice: BluetoothDevice? = null
    private var connectionTimeoutRunnable: Runnable? = null
    private var registerTimeoutRunnable: Runnable? = null
    private var registerRetryCount = 0

    // ============================================================
    // Diagnostics
    // ============================================================

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
        sb.appendLine("Register retry count: $registerRetryCount / $MAX_REGISTER_RETRIES")

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
    // HID Descriptor
    // ============================================================

    private val hidDescriptor: ByteArray = byteArrayOf(
        0x05.toByte(), 0x01.toByte(),   // Usage Page (Generic Desktop)
        0x09.toByte(), 0x06.toByte(),   // Usage (Keyboard)
        0xA1.toByte(), 0x01.toByte(),   // Collection (Application)
        0x05.toByte(), 0x07.toByte(),
        0x19.toByte(), 0xE0.toByte(),
        0x29.toByte(), 0xE7.toByte(),
        0x15.toByte(), 0x00.toByte(),
        0x25.toByte(), 0x01.toByte(),
        0x75.toByte(), 0x01.toByte(),
        0x95.toByte(), 0x08.toByte(),
        0x81.toByte(), 0x02.toByte(),
        0x75.toByte(), 0x08.toByte(),
        0x95.toByte(), 0x01.toByte(),
        0x81.toByte(), 0x01.toByte(),
        0x05.toByte(), 0x08.toByte(),
        0x19.toByte(), 0x01.toByte(),
        0x29.toByte(), 0x05.toByte(),
        0x75.toByte(), 0x01.toByte(),
        0x95.toByte(), 0x05.toByte(),
        0x91.toByte(), 0x02.toByte(),
        0x75.toByte(), 0x03.toByte(),
        0x95.toByte(), 0x01.toByte(),
        0x91.toByte(), 0x01.toByte(),
        0x05.toByte(), 0x07.toByte(),
        0x19.toByte(), 0x00.toByte(),
        0x29.toByte(), 0x65.toByte(),
        0x15.toByte(), 0x00.toByte(),
        0x25.toByte(), 0x65.toByte(),
        0x75.toByte(), 0x08.toByte(),
        0x95.toByte(), 0x06.toByte(),
        0x81.toByte(), 0x00.toByte(),
        0xC0.toByte()
    )

    // ============================================================
    // HID Device Callback
    // ============================================================

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            super.onAppStatusChanged(pluggedDevice, registered)
            Log.d(TAG, "onAppStatusChanged: registered=$registered, device=${pluggedDevice?.name ?: pluggedDevice?.address ?: "none"}")
            isRegistered = registered

            // Cancel registration timeout since we got a response
            cancelRegisterTimeout()

            if (registered) {
                Log.i(TAG, "✅ HID Keyboard registered successfully!")
                registerRetryCount = 0
                postDetailedStatus("✅ HID Keyboard registered — ready to connect")
                notifyConnectionState()

                // Process pending connection
                pendingConnectDevice?.let { device ->
                    val msg = "Connecting to queued device: ${device.name ?: device.address}..."
                    Log.d(TAG, msg)
                    postDetailedStatus(msg)
                    pendingConnectDevice = null
                    mainHandler.postDelayed({
                        connectToDevice(device)
                    }, 500)
                }
            } else {
                Log.w(TAG, "❌ HID Keyboard unregistered")
                postDetailedStatus("HID Keyboard was unregistered")
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
            Log.d(TAG, "onConnectionStateChanged: $stateStr → $deviceName")

            cancelConnectionTimeout()

            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    isConnected = true
                    Log.i(TAG, "✅ Connected to: $deviceName")
                    postDetailedStatus("✅ Connected as keyboard to $deviceName!")
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    postDetailedStatus("⏳ Connecting to $deviceName...")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (device?.address == connectedDevice?.address || connectedDevice == null) {
                        connectedDevice = null
                        isConnected = false
                        postDetailedStatus("Disconnected from $deviceName")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTING -> {
                    postDetailedStatus("Disconnecting from $deviceName...")
                }
            }
            notifyConnectionState()
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            super.onGetReport(device, type, id, bufferSize)
            Log.d(TAG, "onGetReport: type=$type, id=$id")
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
            Log.d(TAG, "HID Device service connected")
            postDetailedStatus("HID service connected — registering...")
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as BluetoothHidDevice
                registerRetryCount = 0
                registerHidDevice()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            Log.d(TAG, "HID Device service disconnected")
            postDetailedStatus("⚠️ HID service disconnected")
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
            postDetailedStatus("❌ No Bluetooth adapter")
            return false
        }

        if (!adapter.isEnabled) {
            postDetailedStatus("❌ Bluetooth is off")
            return false
        }

        // Clean up any existing proxy first
        hidDevice?.let { oldProxy ->
            try {
                Log.d(TAG, "Cleaning up previous HID state...")
                postDetailedStatus("Cleaning up previous session...")
                oldProxy.unregisterApp()
                // Give it a moment to unregister
                Thread.sleep(200)
            } catch (e: Exception) {
                Log.w(TAG, "Error during cleanup", e)
            }
            try {
                adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, oldProxy)
            } catch (e: Exception) {
                Log.w(TAG, "Error closing old proxy", e)
            }
            hidDevice = null
            isRegistered = false
        }

        Log.d(TAG, "Requesting HID Device profile proxy...")
        postDetailedStatus("Requesting Bluetooth HID profile...")
        val result = adapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)

        if (!result) {
            postDetailedStatus("❌ Device does not support Bluetooth HID profile")
        } else {
            postDetailedStatus("Waiting for HID service...")
        }

        return result
    }

    private fun registerHidDevice() {
        val hid = hidDevice ?: run {
            postDetailedStatus("❌ HID proxy lost")
            return
        }

        // Try unregistering first in case a stale registration exists
        try {
            hid.unregisterApp()
            Log.d(TAG, "Unregistered previous app (if any)")
        } catch (e: Exception) {
            Log.d(TAG, "No previous registration to clear: ${e.message}")
        }

        // Small delay after unregister before re-registering
        mainHandler.postDelayed({
            doRegister()
        }, 500)
    }

    private fun doRegister() {
        val hid = hidDevice ?: run {
            postDetailedStatus("❌ HID proxy lost during registration")
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

        registerRetryCount++
        Log.d(TAG, "Calling registerApp() — attempt $registerRetryCount/$MAX_REGISTER_RETRIES")
        postDetailedStatus("Registering HID keyboard (attempt $registerRetryCount)...")

        val result: Boolean
        try {
            result = hid.registerApp(sdpRecord, null, qosOut, hidCallbackExecutor, hidCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Exception in registerApp()", e)
            postDetailedStatus("❌ registerApp() threw: ${e.message}")
            scheduleRegistrationRetry()
            return
        }

        Log.d(TAG, "registerApp() returned: $result")

        if (result) {
            postDetailedStatus("registerApp() accepted — waiting for callback...")
            // Start a timeout — if onAppStatusChanged doesn't fire, retry
            startRegisterTimeout()
        } else {
            Log.e(TAG, "registerApp() returned false")
            postDetailedStatus("❌ registerApp() returned false (attempt $registerRetryCount)")
            scheduleRegistrationRetry()
        }
    }

    private fun startRegisterTimeout() {
        cancelRegisterTimeout()
        registerTimeoutRunnable = Runnable {
            if (!isRegistered) {
                Log.w(TAG, "Registration timeout — callback never fired (attempt $registerRetryCount)")
                postDetailedStatus("⏰ Registration timed out (attempt $registerRetryCount)")
                scheduleRegistrationRetry()
            }
        }
        mainHandler.postDelayed(registerTimeoutRunnable!!, REGISTER_TIMEOUT_MS)
    }

    private fun cancelRegisterTimeout() {
        registerTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        registerTimeoutRunnable = null
    }

    private fun scheduleRegistrationRetry() {
        if (registerRetryCount < MAX_REGISTER_RETRIES) {
            val delay = 1000L * registerRetryCount // Increasing delay: 1s, 2s, 3s
            postDetailedStatus("🔄 Retrying registration in ${delay / 1000}s... (${registerRetryCount}/$MAX_REGISTER_RETRIES)")
            mainHandler.postDelayed({
                if (!isRegistered && hidDevice != null) {
                    doRegister()
                }
            }, delay)
        } else {
            val msg = "❌ HID registration failed after $MAX_REGISTER_RETRIES attempts.\n\n" +
                    "Try these steps:\n" +
                    "1. Toggle Bluetooth OFF and ON\n" +
                    "2. Restart the app\n" +
                    "3. Restart your phone\n\n" +
                    "Some phones don't fully support HID Device profile."
            Log.e(TAG, msg)
            postDetailedStatus(msg)
            mainHandler.post {
                onConnectionStateChanged?.invoke(false, "HID registration failed — tap Settings to retry")
            }
        }
    }

    /**
     * Force re-register the HID app. Can be called from UI.
     */
    fun retryRegistration() {
        registerRetryCount = 0
        val hid = hidDevice
        if (hid != null) {
            registerHidDevice()
        } else {
            initialize()
        }
    }

    fun connectToDevice(device: BluetoothDevice): ConnectResult {
        val deviceName = device.name ?: device.address

        // 1) Check HID proxy
        val hid = hidDevice
        if (hid == null) {
            pendingConnectDevice = device
            val initResult = initialize()
            return if (initResult) {
                ConnectResult(true, "Setting up Bluetooth HID...\nWill connect to $deviceName automatically.")
            } else {
                pendingConnectDevice = null
                ConnectResult(false, "Bluetooth HID not available on this device.")
            }
        }

        // 2) Check registration
        if (!isRegistered) {
            pendingConnectDevice = device
            postDetailedStatus("⏳ HID registering — $deviceName queued for connection")
            // Kick registration if it's stalled
            if (registerRetryCount >= MAX_REGISTER_RETRIES) {
                registerRetryCount = 0
                doRegister()
            }
            return ConnectResult(true, "Setting up HID keyboard...\nWill connect to $deviceName when ready.")
        }

        // 3) Check bond state
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            return ConnectResult(false, "$deviceName is not paired.\nPlease pair first via Bluetooth settings.")
        }

        // 4) Check device type
        if (device.type == BluetoothDevice.DEVICE_TYPE_LE) {
            return ConnectResult(false, "$deviceName is BLE-only.\nBluetooth HID requires Classic Bluetooth.")
        }

        // 5) Disconnect stale devices
        val connectedHidDevices = hid.getConnectedDevices()
        if (connectedHidDevices.isNotEmpty()) {
            for (d in connectedHidDevices) {
                if (d.address == device.address) {
                    connectedDevice = device
                    isConnected = true
                    notifyConnectionState()
                    return ConnectResult(true, "Already connected to $deviceName!")
                }
                hid.disconnect(d)
            }
            postDetailedStatus("Disconnected stale device, retrying...")
            mainHandler.postDelayed({ doConnect(hid, device) }, CONNECT_RETRY_DELAY_MS)
            return ConnectResult(true, "Clearing previous connection, then connecting...")
        }

        // 6) Connect
        return doConnect(hid, device)
    }

    private fun doConnect(hid: BluetoothHidDevice, device: BluetoothDevice): ConnectResult {
        val deviceName = device.name ?: device.address
        postDetailedStatus("⏳ Connecting to $deviceName...")
        mainHandler.post {
            onConnectionStateChanged?.invoke(false, "Connecting to $deviceName...")
        }

        val result: Boolean
        try {
            result = hid.connect(device)
        } catch (e: Exception) {
            postDetailedStatus("❌ Connect exception: ${e.message}")
            return ConnectResult(false, "Connection error: ${e.message}")
        }

        Log.d(TAG, "connect() returned: $result")

        if (result) {
            postDetailedStatus("✅ Connect request sent — waiting for $deviceName to respond...")
            startConnectionTimeout(deviceName)
            return ConnectResult(true, "Connecting to $deviceName...\nWaiting for PC to respond.")
        } else {
            val msg = "Connection to $deviceName was rejected.\n\n" +
                    "Try these steps:\n" +
                    "1. On PC: Remove the phone from Bluetooth devices\n" +
                    "2. On Phone: Remove the PC from Bluetooth\n" +
                    "3. Toggle Bluetooth OFF/ON on both\n" +
                    "4. Re-pair from scratch\n" +
                    "5. Settings → Re-initialize Bluetooth HID"
            postDetailedStatus("❌ connect() returned false")
            return ConnectResult(false, msg)
        }
    }

    private fun startConnectionTimeout(deviceName: String) {
        cancelConnectionTimeout()
        connectionTimeoutRunnable = Runnable {
            if (!isConnected) {
                postDetailedStatus("⏰ Connection to $deviceName timed out")
                mainHandler.post {
                    onConnectionStateChanged?.invoke(false, "Connection timed out — try again")
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
        postDetailedStatus("Disconnecting from ${device.name ?: device.address}...")
        hid.disconnect(device)
    }

    fun getPairedDevices(): Set<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices ?: emptySet()
    }

    // ============================================================
    // Key Sending
    // ============================================================

    fun sendKeyPress(char: Char): Boolean {
        if (!isConnected) return false
        val keyEvent = HidKeyMapper.charToHidKeyEvent(char) ?: return false
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
            } catch (e: Exception) { Log.e(TAG, "Error sending backspace", e) }
        }
        return true
    }

    fun sendString(text: String) {
        if (!isConnected) return
        keyExecutor.execute {
            for (char in text) {
                val keyEvent = HidKeyMapper.charToHidKeyEvent(char) ?: continue
                sendHidReportSync(keyEvent)
                Thread.sleep(KEY_SEQUENCE_DELAY_MS)
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
            } catch (e: Exception) { Log.e(TAG, "Error sending enter", e) }
        }
        return true
    }

    // ============================================================
    // Internal
    // ============================================================

    private fun sendHidReport(event: HidKeyMapper.HidKeyEvent): Boolean {
        val hid = hidDevice ?: return false
        val device = connectedDevice ?: return false
        keyExecutor.execute {
            try {
                hid.sendReport(device, 0, HidKeyMapper.createKeyDownReport(event))
                Thread.sleep(KEY_PRESS_DELAY_MS)
                hid.sendReport(device, 0, HidKeyMapper.createKeyUpReport())
            } catch (e: Exception) { Log.e(TAG, "Error sending HID report", e) }
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
        } catch (e: Exception) { Log.e(TAG, "Error sending HID report (sync)", e) }
    }

    private fun notifyConnectionState() {
        mainHandler.post {
            val msg = when {
                isConnected -> "Connected as Keyboard to ${connectedDevice?.name ?: connectedDevice?.address ?: "Unknown"}"
                isRegistered -> "Ready — Tap 'Connect to Device'"
                else -> "Not Connected"
            }
            onConnectionStateChanged?.invoke(isConnected, msg)
        }
    }

    private fun postDetailedStatus(message: String) {
        mainHandler.post { onDetailedStatus?.invoke(message) }
    }

    fun cleanup() {
        cancelConnectionTimeout()
        cancelRegisterTimeout()
        try {
            hidDevice?.let { hid ->
                connectedDevice?.let { hid.disconnect(it) }
                hid.unregisterApp()
            }
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
        } catch (e: Exception) { Log.e(TAG, "Error during cleanup", e) }
        hidDevice = null
        connectedDevice = null
        isRegistered = false
        isConnected = false
        pendingConnectDevice = null
    }

    data class ConnectResult(val accepted: Boolean, val message: String)
}
