package com.taptotype

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

@SuppressLint("MissingPermission")
class BluetoothHidService(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothHidService"
        private const val KEY_PRESS_DELAY_MS = 20L
        private const val KEY_SEQUENCE_DELAY_MS = 30L
        private const val CONNECT_RETRY_DELAY_MS = 1500L
        private const val CONNECTION_TIMEOUT_MS = 10000L
        private const val REGISTER_TIMEOUT_MS = 3000L
        private const val MAX_REGISTER_RETRIES = 5
        private const val MAX_LOG_ENTRIES = 200
        private const val KEEP_ALIVE_INTERVAL_MS = 30_000L  // Send keep-alive every 30s
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

    /** When true, Enter sends Shift+Enter (line break in chat apps) */
    var useShiftEnter: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val keyExecutor = Executors.newSingleThreadExecutor()
    private val hidCallbackExecutor = Executors.newSingleThreadExecutor()

    private var pendingConnectDevice: BluetoothDevice? = null
    private var connectionTimeoutRunnable: Runnable? = null
    private var registerTimeoutRunnable: Runnable? = null
    private var registerRetryCount = 0
    private var keepAliveRunnable: Runnable? = null

    // Flag to distinguish intentional unregister (cleanup/reinit) from
    // unexpected system unregistration (Android BT stack timeout)
    private var isCleaningUp = false

    // ============================================================
    // In-App Log Buffer
    // ============================================================

    private val logBuffer = mutableListOf<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * Add a timestamped entry to the in-app log and also Log.d
     */
    private fun log(level: String, message: String) {
        val timestamp = timeFormat.format(Date())
        val entry = "[$timestamp] $level: $message"
        synchronized(logBuffer) {
            logBuffer.add(entry)
            if (logBuffer.size > MAX_LOG_ENTRIES) {
                logBuffer.removeAt(0)
            }
        }
        when (level) {
            "E" -> Log.e(TAG, message)
            "W" -> Log.w(TAG, message)
            "I" -> Log.i(TAG, message)
            else -> Log.d(TAG, message)
        }
    }

    /**
     * Get all log entries as a single string.
     */
    fun getLogText(): String {
        synchronized(logBuffer) {
            return if (logBuffer.isEmpty()) {
                "(No log entries yet)"
            } else {
                logBuffer.joinToString("\n")
            }
        }
    }

    /**
     * Clear the log buffer.
     */
    fun clearLog() {
        synchronized(logBuffer) { logBuffer.clear() }
    }

    // ============================================================
    // Diagnostics
    // ============================================================

    fun getDiagnosticInfo(): String {
        val adapter = bluetoothAdapter
        val sb = StringBuilder()
        sb.appendLine("=== Bluetooth HID Diagnostics ===")
        sb.appendLine("Time: ${timeFormat.format(Date())}")
        sb.appendLine("Bluetooth adapter: ${if (adapter != null) "Available" else "NOT AVAILABLE"}")
        sb.appendLine("Bluetooth enabled: ${adapter?.isEnabled ?: false}")
        sb.appendLine("Adapter name: ${adapter?.name ?: "N/A"}")
        sb.appendLine("Adapter address: ${adapter?.address ?: "N/A"}")
        sb.appendLine("Adapter state: ${adapter?.state ?: -1} (12=ON, 10=OFF)")
        sb.appendLine("Scan mode: ${adapter?.scanMode ?: -1} (23=connectable+discoverable, 21=connectable)")
        sb.appendLine("HID proxy obtained: ${hidDevice != null}")
        sb.appendLine("HID app registered: $isRegistered")
        sb.appendLine("Register retries: $registerRetryCount / $MAX_REGISTER_RETRIES")
        sb.appendLine("Connected: $isConnected")
        sb.appendLine("Connected device: ${connectedDevice?.name ?: connectedDevice?.address ?: "None"}")
        sb.appendLine("Pending connect: ${pendingConnectDevice?.name ?: "None"}")

        if (adapter != null && adapter.isEnabled) {
            val bondedDevices = adapter.bondedDevices
            sb.appendLine("\nPaired devices (${bondedDevices.size}):")
            for (device in bondedDevices) {
                val bondStr = when (device.bondState) {
                    BluetoothDevice.BOND_BONDED -> "BONDED"
                    BluetoothDevice.BOND_BONDING -> "BONDING"
                    BluetoothDevice.BOND_NONE -> "NONE"
                    else -> "?"
                }
                val typeStr = when (device.type) {
                    BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic"
                    BluetoothDevice.DEVICE_TYPE_LE -> "BLE"
                    BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual"
                    else -> "Unknown"
                }
                val uuids = try {
                    device.uuids?.joinToString(", ") { it.uuid.toString().take(8) } ?: "none"
                } catch (e: Exception) { "error" }
                sb.appendLine("  • ${device.name ?: "?"} (${device.address})")
                sb.appendLine("    Bond=$bondStr, Type=$typeStr, UUIDs=[$uuids]")
            }

            hidDevice?.let { hid ->
                try {
                    val connectedHidDevices = hid.getConnectedDevices()
                    sb.appendLine("\nHID connected devices (${connectedHidDevices.size}):")
                    for (d in connectedHidDevices) {
                        sb.appendLine("  • ${d.name ?: "?"} (${d.address})")
                    }
                } catch (e: Exception) {
                    sb.appendLine("\nHID getConnectedDevices() error: ${e.message}")
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
        0x05.toByte(), 0x01.toByte(),
        0x09.toByte(), 0x06.toByte(),
        0xA1.toByte(), 0x01.toByte(),
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
    // HID Callback
    // ============================================================

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            super.onAppStatusChanged(pluggedDevice, registered)
            log("I", "onAppStatusChanged(registered=$registered, device=${pluggedDevice?.name ?: pluggedDevice?.address ?: "null"})")
            isRegistered = registered
            cancelRegisterTimeout()

            if (registered) {
                log("I", "✅ HID app registered successfully!")
                registerRetryCount = 0
                postDetailedStatus("✅ HID Keyboard registered — ready to connect")
                notifyConnectionState()

                pendingConnectDevice?.let { device ->
                    log("I", "Processing queued connection to: ${device.name ?: device.address}")
                    postDetailedStatus("Connecting to queued device: ${device.name ?: device.address}...")
                    pendingConnectDevice = null
                    mainHandler.postDelayed({ connectToDevice(device) }, 500)
                }
            } else {
                isConnected = false
                connectedDevice = null

                if (isCleaningUp) {
                    log("I", "HID unregistered (intentional cleanup)")
                    postDetailedStatus("HID Keyboard unregistered (cleanup)")
                    notifyConnectionState()
                } else {
                    // UNEXPECTED unregistration — Android BT stack killed our registration.
                    // This typically happens ~5-20s after registerApp() if no device connects.
                    // The old proxy becomes STALE after this — registerApp() on it returns false.
                    // Fix: get a completely fresh proxy via initialize().
                    log("W", "⚠️ UNEXPECTED unregistration by system! Getting fresh proxy...")
                    postDetailedStatus("⚠️ System unregistered HID — reinitializing...")
                    notifyConnectionState()

                    // Close the stale proxy and get a fresh one
                    mainHandler.postDelayed({
                        if (!isRegistered && !isCleaningUp) {
                            log("I", "Getting fresh Bluetooth HID proxy after system unregistration...")
                            // Close the old stale proxy
                            try {
                                bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
                                log("D", "Closed stale proxy")
                            } catch (e: Exception) {
                                log("W", "Error closing stale proxy: ${e.message}")
                            }
                            hidDevice = null
                            registerRetryCount = 0
                            // Get a completely new proxy — this triggers onServiceConnected → registerApp()
                            initialize()
                        }
                    }, 1000)
                }
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            super.onConnectionStateChanged(device, state)
            val stateStr = when (state) {
                BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
                BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
                else -> "UNKNOWN($state)"
            }
            val deviceName = device?.name ?: device?.address ?: "null"
            log("I", "onConnectionStateChanged($stateStr, device=$deviceName)")
            cancelConnectionTimeout()

            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    isConnected = true
                    log("I", "✅ Connected to $deviceName")
                    postDetailedStatus("✅ Connected as keyboard to $deviceName!")
                    startKeepAlive()
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    log("D", "Connecting to $deviceName...")
                    postDetailedStatus("⏳ Connecting to $deviceName...")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("I", "Disconnected from $deviceName")
                    stopKeepAlive()
                    if (device?.address == connectedDevice?.address || connectedDevice == null) {
                        connectedDevice = null
                        isConnected = false
                        postDetailedStatus("Disconnected from $deviceName")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTING -> {
                    log("D", "Disconnecting from $deviceName...")
                    postDetailedStatus("Disconnecting from $deviceName...")
                }
            }
            notifyConnectionState()
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            super.onGetReport(device, type, id, bufferSize)
            log("D", "onGetReport(type=$type, id=$id, bufferSize=$bufferSize)")
            hidDevice?.replyReport(device, type, id, ByteArray(8))
        }

        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            super.onSetReport(device, type, id, data)
            log("D", "onSetReport(type=$type, id=$id, data=${data?.size ?: 0} bytes)")
            hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }

        override fun onInterruptData(device: BluetoothDevice?, reportId: Byte, data: ByteArray?) {
            super.onInterruptData(device, reportId, data)
            log("D", "onInterruptData(reportId=$reportId, data=${data?.size ?: 0} bytes)")
        }
    }

    // ============================================================
    // Profile Listener
    // ============================================================

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            log("I", "ProfileListener.onServiceConnected(profile=$profile, proxy=${proxy?.javaClass?.simpleName ?: "null"})")
            postDetailedStatus("HID service connected — registering...")
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as BluetoothHidDevice
                log("I", "HID proxy obtained ✅")

                // Pre-flight checks
                try {
                    val connDevices = (proxy as BluetoothHidDevice).getConnectedDevices()
                    log("D", "Pre-flight: ${connDevices.size} device(s) currently connected via HID")
                    for (d in connDevices) {
                        log("D", "  Already connected: ${d.name ?: "?"} (${d.address})")
                    }
                } catch (e: Exception) {
                    log("E", "Pre-flight getConnectedDevices() failed: ${e.javaClass.simpleName}: ${e.message}")
                }

                registerRetryCount = 0
                registerHidDevice()
            } else {
                log("W", "Unexpected profile connected: $profile (expected ${BluetoothProfile.HID_DEVICE})")
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            log("W", "ProfileListener.onServiceDisconnected(profile=$profile)")
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
        log("I", "=== initialize() called ===")
        val adapter = bluetoothAdapter ?: run {
            log("E", "No Bluetooth adapter available")
            postDetailedStatus("❌ No Bluetooth adapter")
            return false
        }

        log("D", "Adapter state: ${adapter.state} (12=ON), enabled: ${adapter.isEnabled}")
        log("D", "Adapter name: ${adapter.name}, address: ${adapter.address}")

        if (!adapter.isEnabled) {
            log("E", "Bluetooth is disabled")
            postDetailedStatus("❌ Bluetooth is off")
            return false
        }

        // Clean up existing proxy
        hidDevice?.let { oldProxy ->
            log("D", "Cleaning up previous proxy...")
            isCleaningUp = true
            try {
                oldProxy.unregisterApp()
                log("D", "unregisterApp() called on old proxy")
                Thread.sleep(200)
            } catch (e: Exception) {
                log("W", "Cleanup unregisterApp error: ${e.javaClass.simpleName}: ${e.message}")
            }
            isCleaningUp = false
            try {
                adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, oldProxy)
                log("D", "closeProfileProxy() called")
            } catch (e: Exception) {
                log("W", "Cleanup closeProfileProxy error: ${e.javaClass.simpleName}: ${e.message}")
            }
            hidDevice = null
            isRegistered = false
        }

        log("I", "Calling getProfileProxy(HID_DEVICE)...")
        postDetailedStatus("Requesting Bluetooth HID profile...")

        val result: Boolean
        try {
            result = adapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
        } catch (e: Exception) {
            log("E", "getProfileProxy() threw: ${e.javaClass.simpleName}: ${e.message}")
            postDetailedStatus("❌ getProfileProxy failed: ${e.message}")
            return false
        }

        log("I", "getProfileProxy() returned: $result")

        if (!result) {
            log("E", "getProfileProxy returned false — HID_DEVICE profile not supported")
            postDetailedStatus("❌ Device does not support Bluetooth HID profile")
        } else {
            postDetailedStatus("Waiting for HID service callback...")
        }

        return result
    }

    private fun registerHidDevice() {
        val hid = hidDevice ?: run {
            log("E", "registerHidDevice: hidDevice is null!")
            postDetailedStatus("❌ HID proxy lost")
            return
        }

        log("D", "Attempting unregisterApp() to clear stale state...")
        isCleaningUp = true
        try {
            val unregResult = hid.unregisterApp()
            log("D", "unregisterApp() returned: $unregResult")
        } catch (e: Exception) {
            log("D", "unregisterApp() threw (expected if no prior registration): ${e.message}")
        }
        isCleaningUp = false

        mainHandler.postDelayed({ doRegister() }, 500)
    }

    private fun doRegister() {
        val hid = hidDevice ?: run {
            log("E", "doRegister: hidDevice became null!")
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
        log("I", "=== registerApp() attempt $registerRetryCount/$MAX_REGISTER_RETRIES ===")
        log("D", "  SDP: name='TapToType Keyboard', subclass=KEYBOARD")
        log("D", "  Descriptor: ${hidDescriptor.size} bytes")
        log("D", "  Executor: $hidCallbackExecutor")
        postDetailedStatus("Registering HID keyboard (attempt $registerRetryCount)...")

        val result: Boolean
        try {
            result = hid.registerApp(sdpRecord, null, qosOut, hidCallbackExecutor, hidCallback)
        } catch (e: SecurityException) {
            log("E", "registerApp() SecurityException: ${e.message}")
            log("E", "  This usually means a Bluetooth permission is missing")
            postDetailedStatus("❌ Permission denied: ${e.message}")
            return
        } catch (e: Exception) {
            log("E", "registerApp() threw ${e.javaClass.simpleName}: ${e.message}")
            e.stackTrace.take(5).forEach { log("E", "  at $it") }
            postDetailedStatus("❌ registerApp() error: ${e.message}")
            scheduleRegistrationRetry()
            return
        }

        log("I", "registerApp() returned: $result")

        if (result) {
            log("I", "registerApp() accepted ✅ — waiting for onAppStatusChanged callback...")
            postDetailedStatus("registerApp() accepted — waiting for callback...")
            startRegisterTimeout()
        } else {
            log("E", "registerApp() returned FALSE ❌")
            log("E", "  Possible reasons:")
            log("E", "  - Another app has the HID profile registered")
            log("E", "  - A previous registration wasn't cleaned up")
            log("E", "  - The Bluetooth stack is in a bad state")
            log("E", "  - The device doesn't support registering as HID keyboard")

            // Try to get more info
            try {
                val devices = hid.getConnectedDevices()
                log("D", "  Connected devices after failed register: ${devices.size}")
            } catch (e: Exception) {
                log("E", "  getConnectedDevices() after failed register threw: ${e.message}")
            }

            postDetailedStatus("❌ registerApp() returned false (attempt $registerRetryCount)")
            scheduleRegistrationRetry()
        }
    }

    private fun startRegisterTimeout() {
        cancelRegisterTimeout()
        registerTimeoutRunnable = Runnable {
            if (!isRegistered) {
                log("W", "⏰ Registration timeout — onAppStatusChanged never fired (attempt $registerRetryCount)")
                log("W", "  hidDevice is still: ${if (hidDevice != null) "available" else "NULL"}")
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
            val delay = 1000L * registerRetryCount
            log("I", "🔄 Scheduling registration retry in ${delay}ms (attempt ${registerRetryCount + 1})")
            postDetailedStatus("🔄 Retrying in ${delay / 1000}s... ($registerRetryCount/$MAX_REGISTER_RETRIES)")
            mainHandler.postDelayed({
                if (!isRegistered && hidDevice != null) {
                    doRegister()
                } else {
                    log("D", "Retry cancelled: registered=$isRegistered, proxy=${hidDevice != null}")
                }
            }, delay)
        } else {
            log("E", "❌ Registration failed after $MAX_REGISTER_RETRIES attempts")
            log("E", "  Final state: proxy=${hidDevice != null}, registered=$isRegistered")
            val msg = "❌ HID registration failed after $MAX_REGISTER_RETRIES attempts.\n" +
                    "Toggle Bluetooth OFF/ON, restart app, or check Logs for details."
            postDetailedStatus(msg)
            mainHandler.post {
                onConnectionStateChanged?.invoke(false, "HID registration failed — check Logs")
            }
        }
    }

    fun retryRegistration() {
        log("I", "=== Manual retry registration triggered ===")
        registerRetryCount = 0
        val hid = hidDevice
        if (hid != null) {
            registerHidDevice()
        } else {
            log("I", "No proxy — calling initialize()")
            initialize()
        }
    }

    fun connectToDevice(device: BluetoothDevice): ConnectResult {
        val deviceName = device.name ?: device.address
        log("I", "=== connectToDevice($deviceName, ${device.address}) ===")
        log("D", "  State: proxy=${hidDevice != null}, registered=$isRegistered, connected=$isConnected")
        log("D", "  Device bond=${device.bondState}, type=${device.type}")

        val hid = hidDevice
        if (hid == null) {
            log("W", "No HID proxy — queuing connection and initializing")
            pendingConnectDevice = device
            postDetailedStatus("⏳ Setting up HID — $deviceName queued")
            val initResult = initialize()
            return if (initResult) {
                ConnectResult(true, "Setting up Bluetooth HID...\nWill connect to $deviceName automatically.")
            } else {
                pendingConnectDevice = null
                ConnectResult(false, "Bluetooth HID not available.")
            }
        }

        if (!isRegistered) {
            log("W", "HID not registered — queuing connection for $deviceName")
            pendingConnectDevice = device
            postDetailedStatus("⏳ HID registering — $deviceName queued")
            if (registerRetryCount >= MAX_REGISTER_RETRIES) {
                registerRetryCount = 0
                doRegister()
            }
            return ConnectResult(true, "Setting up HID keyboard...\nWill connect to $deviceName when ready.")
        }

        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            log("E", "Device not bonded: bondState=${device.bondState}")
            return ConnectResult(false, "$deviceName is not paired.\nPair first via Bluetooth settings.")
        }

        if (device.type == BluetoothDevice.DEVICE_TYPE_LE) {
            log("E", "Device is BLE-only — not compatible with HID classic")
            return ConnectResult(false, "$deviceName is BLE-only.\nHID requires Classic Bluetooth.")
        }

        // Check for stale connections
        try {
            val connectedHidDevices = hid.getConnectedDevices()
            log("D", "Currently ${connectedHidDevices.size} HID device(s) connected")
            if (connectedHidDevices.isNotEmpty()) {
                for (d in connectedHidDevices) {
                    if (d.address == device.address) {
                        log("I", "Already connected to $deviceName!")
                        connectedDevice = device
                        isConnected = true
                        notifyConnectionState()
                        return ConnectResult(true, "Already connected to $deviceName!")
                    }
                    log("D", "Disconnecting stale: ${d.name ?: d.address}")
                    hid.disconnect(d)
                }
                log("D", "Retrying connect in ${CONNECT_RETRY_DELAY_MS}ms...")
                mainHandler.postDelayed({ doConnect(hid, device) }, CONNECT_RETRY_DELAY_MS)
                return ConnectResult(true, "Clearing stale connection, then connecting...")
            }
        } catch (e: Exception) {
            log("E", "getConnectedDevices() error: ${e.message}")
        }

        return doConnect(hid, device)
    }

    private fun doConnect(hid: BluetoothHidDevice, device: BluetoothDevice): ConnectResult {
        val deviceName = device.name ?: device.address
        log("I", ">>> hid.connect(${device.address}) — $deviceName")
        postDetailedStatus("⏳ Connecting to $deviceName...")
        mainHandler.post {
            onConnectionStateChanged?.invoke(false, "Connecting to $deviceName...")
        }

        val result: Boolean
        try {
            result = hid.connect(device)
        } catch (e: SecurityException) {
            log("E", "connect() SecurityException: ${e.message}")
            postDetailedStatus("❌ Permission denied: ${e.message}")
            return ConnectResult(false, "Permission error: ${e.message}")
        } catch (e: Exception) {
            log("E", "connect() threw ${e.javaClass.simpleName}: ${e.message}")
            e.stackTrace.take(3).forEach { log("E", "  at $it") }
            postDetailedStatus("❌ Connect error: ${e.message}")
            return ConnectResult(false, "Connection error: ${e.message}")
        }

        log("I", "connect() returned: $result")

        if (result) {
            log("I", "✅ connect() accepted — waiting for onConnectionStateChanged...")
            postDetailedStatus("✅ Request sent — waiting for $deviceName to respond...")
            startConnectionTimeout(deviceName)
            return ConnectResult(true, "Connecting to $deviceName...\nWaiting for PC to respond.")
        } else {
            log("E", "❌ connect() returned false for $deviceName")
            log("E", "  Troubleshooting:")
            log("E", "  1. Remove phone from PC's Bluetooth")
            log("E", "  2. Remove PC from phone's Bluetooth")
            log("E", "  3. Toggle BT OFF/ON on both")
            log("E", "  4. Re-pair and try again")
            postDetailedStatus("❌ connect() rejected — see Logs for details")
            return ConnectResult(false, "Connection rejected by $deviceName.\n\n" +
                    "Try re-pairing:\n1. Remove the phone from PC's Bluetooth\n" +
                    "2. Remove PC from phone's Bluetooth\n" +
                    "3. Toggle BT OFF/ON\n4. Re-pair and retry")
        }
    }

    private fun startConnectionTimeout(deviceName: String) {
        cancelConnectionTimeout()
        connectionTimeoutRunnable = Runnable {
            if (!isConnected) {
                log("W", "⏰ Connection to $deviceName timed out (${CONNECTION_TIMEOUT_MS}ms)")
                postDetailedStatus("⏰ Connection timed out — PC didn't respond")
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
        stopKeepAlive()
        val hid = hidDevice ?: return
        val device = connectedDevice ?: return
        log("I", "Disconnecting from ${device.name ?: device.address}...")
        postDetailedStatus("Disconnecting...")
        hid.disconnect(device)
    }

    fun getPairedDevices(): Set<BluetoothDevice> = bluetoothAdapter?.bondedDevices ?: emptySet()

    // ============================================================
    // Keep-Alive (prevents Windows from dropping idle HID connection)
    // ============================================================

    private fun startKeepAlive() {
        stopKeepAlive()
        log("D", "Starting keep-alive (every ${KEEP_ALIVE_INTERVAL_MS / 1000}s)")
        keepAliveRunnable = object : Runnable {
            override fun run() {
                if (isConnected) {
                    val hid = hidDevice
                    val device = connectedDevice
                    if (hid != null && device != null) {
                        try {
                            // Send an empty key-up report (all zeros = no keys pressed)
                            val emptyReport = HidKeyMapper.createKeyUpReport()
                            val sent = hid.sendReport(device, 0, emptyReport)
                            log("D", "Keep-alive sent: $sent")
                        } catch (e: Exception) {
                            log("W", "Keep-alive failed: ${e.message}")
                        }
                    }
                    mainHandler.postDelayed(this, KEEP_ALIVE_INTERVAL_MS)
                } else {
                    log("D", "Keep-alive stopped (no longer connected)")
                }
            }
        }
        mainHandler.postDelayed(keepAliveRunnable!!, KEEP_ALIVE_INTERVAL_MS)
    }

    private fun stopKeepAlive() {
        keepAliveRunnable?.let {
            mainHandler.removeCallbacks(it)
            log("D", "Keep-alive stopped")
        }
        keepAliveRunnable = null
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
            } catch (e: Exception) { log("E", "sendBackspace error: ${e.message}") }
        }
        return true
    }

    fun sendString(text: String) {
        if (!isConnected) return
        keyExecutor.execute {
            for (char in text) {
                if (char == '\n') {
                    // Use the Enter setting for newlines in text
                    val hid = hidDevice ?: return@execute
                    val device = connectedDevice ?: return@execute
                    val report = if (useShiftEnter) HidKeyMapper.createShiftEnterReport()
                                 else HidKeyMapper.createEnterReport()
                    hid.sendReport(device, 0, report)
                    Thread.sleep(KEY_PRESS_DELAY_MS)
                    hid.sendReport(device, 0, HidKeyMapper.createKeyUpReport())
                    Thread.sleep(KEY_SEQUENCE_DELAY_MS)
                } else {
                    val keyEvent = HidKeyMapper.charToHidKeyEvent(char) ?: continue
                    sendHidReportSync(keyEvent)
                    Thread.sleep(KEY_SEQUENCE_DELAY_MS)
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
                val report = if (useShiftEnter) HidKeyMapper.createShiftEnterReport()
                             else HidKeyMapper.createEnterReport()
                hid.sendReport(device, 0, report)
                Thread.sleep(KEY_PRESS_DELAY_MS)
                hid.sendReport(device, 0, HidKeyMapper.createKeyUpReport())
            } catch (e: Exception) { log("E", "sendEnter error: ${e.message}") }
        }
        return true
    }

    private fun sendHidReport(event: HidKeyMapper.HidKeyEvent): Boolean {
        val hid = hidDevice ?: return false
        val device = connectedDevice ?: return false
        keyExecutor.execute {
            try {
                hid.sendReport(device, 0, HidKeyMapper.createKeyDownReport(event))
                Thread.sleep(KEY_PRESS_DELAY_MS)
                hid.sendReport(device, 0, HidKeyMapper.createKeyUpReport())
            } catch (e: Exception) { log("E", "sendHidReport error: ${e.message}") }
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
        } catch (e: Exception) { log("E", "sendHidReportSync error: ${e.message}") }
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
        log("I", "cleanup() called")
        isCleaningUp = true

        // Remove ALL pending callbacks to prevent zombie re-register loops
        // (critical during theme changes which recreate the Activity)
        mainHandler.removeCallbacksAndMessages(null)

        stopKeepAlive()
        cancelConnectionTimeout()
        cancelRegisterTimeout()

        // Detach listeners so dead Activity doesn't get callbacks
        onConnectionStateChanged = null
        onDetailedStatus = null

        try {
            hidDevice?.let { hid ->
                connectedDevice?.let { hid.disconnect(it) }
                hid.unregisterApp()
            }
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
        } catch (e: Exception) { log("E", "cleanup error: ${e.message}") }
        hidDevice = null
        connectedDevice = null
        isRegistered = false
        isConnected = false
        pendingConnectDevice = null
        // NOTE: isCleaningUp stays true — this instance is being destroyed.
        // A new BluetoothHidService will be created by the new Activity.
    }

    data class ConnectResult(val accepted: Boolean, val message: String)
}
