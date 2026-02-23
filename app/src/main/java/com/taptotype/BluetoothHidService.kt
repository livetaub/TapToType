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
 * The Android BluetoothHidDevice API (added in API 28) allows the phone to
 * act as an HID peripheral (keyboard, mouse, gamepad, etc.).
 *
 * This service:
 *   1. Registers the app as a Bluetooth HID keyboard device
 *   2. Manages connection state with the host (Windows PC)
 *   3. Sends 8-byte HID keyboard reports for key presses
 */
@SuppressLint("MissingPermission")
class BluetoothHidService(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothHidService"

        // Delay between key-down and key-up reports (ms)
        private const val KEY_PRESS_DELAY_MS = 20L

        // Delay between sequential key events when sending a string (ms)
        private const val KEY_SEQUENCE_DELAY_MS = 30L
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

    // Listener for connection state changes
    var onConnectionStateChanged: ((Boolean, String) -> Unit)? = null

    // Handler for main thread operations
    private val mainHandler = Handler(Looper.getMainLooper())

    // Executor for sending key events with delays
    private val keyExecutor = Executors.newSingleThreadExecutor()

    // ============================================================
    // HID Descriptor for a standard keyboard
    // ============================================================

    /**
     * USB HID Report Descriptor for a standard keyboard.
     * This tells the host (Windows) what kind of device this is and
     * how to interpret the reports we send.
     *
     * Based on the USB HID specification for boot keyboards.
     */
    private val hidDescriptor: ByteArray = byteArrayOf(
        0x05.toByte(), 0x01.toByte(),   // Usage Page (Generic Desktop)
        0x09.toByte(), 0x06.toByte(),   // Usage (Keyboard)
        0xA1.toByte(), 0x01.toByte(),   // Collection (Application)

        // Modifier keys (8 bits: Ctrl, Shift, Alt, GUI for left and right)
        0x05.toByte(), 0x07.toByte(),   //   Usage Page (Keyboard/Keypad)
        0x19.toByte(), 0xE0.toByte(),   //   Usage Minimum (Left Control)
        0x29.toByte(), 0xE7.toByte(),   //   Usage Maximum (Right GUI)
        0x15.toByte(), 0x00.toByte(),   //   Logical Minimum (0)
        0x25.toByte(), 0x01.toByte(),   //   Logical Maximum (1)
        0x75.toByte(), 0x01.toByte(),   //   Report Size (1 bit)
        0x95.toByte(), 0x08.toByte(),   //   Report Count (8 bits)
        0x81.toByte(), 0x02.toByte(),   //   Input (Data, Variable, Absolute)

        // Reserved byte
        0x75.toByte(), 0x08.toByte(),   //   Report Size (8 bits)
        0x95.toByte(), 0x01.toByte(),   //   Report Count (1)
        0x81.toByte(), 0x01.toByte(),   //   Input (Constant) - reserved byte

        // LED output report (Num Lock, Caps Lock, Scroll Lock, etc.)
        0x05.toByte(), 0x08.toByte(),   //   Usage Page (LEDs)
        0x19.toByte(), 0x01.toByte(),   //   Usage Minimum (Num Lock)
        0x29.toByte(), 0x05.toByte(),   //   Usage Maximum (Kana)
        0x75.toByte(), 0x01.toByte(),   //   Report Size (1)
        0x95.toByte(), 0x05.toByte(),   //   Report Count (5)
        0x91.toByte(), 0x02.toByte(),   //   Output (Data, Variable, Absolute)

        // LED padding (3 bits)
        0x75.toByte(), 0x03.toByte(),   //   Report Size (3)
        0x95.toByte(), 0x01.toByte(),   //   Report Count (1)
        0x91.toByte(), 0x01.toByte(),   //   Output (Constant) - padding

        // Key codes (6 simultaneous keys)
        0x05.toByte(), 0x07.toByte(),   //   Usage Page (Keyboard/Keypad)
        0x19.toByte(), 0x00.toByte(),   //   Usage Minimum (0)
        0x29.toByte(), 0x65.toByte(),   //   Usage Maximum (101)
        0x15.toByte(), 0x00.toByte(),   //   Logical Minimum (0)
        0x25.toByte(), 0x65.toByte(),   //   Logical Maximum (101)
        0x75.toByte(), 0x08.toByte(),   //   Report Size (8 bits)
        0x95.toByte(), 0x06.toByte(),   //   Report Count (6)
        0x81.toByte(), 0x00.toByte(),   //   Input (Data, Array)

        0xC0.toByte()                    // End Collection
    )

    // ============================================================
    // HID Device Callback
    // ============================================================

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            super.onAppStatusChanged(pluggedDevice, registered)
            Log.d(TAG, "App status changed: registered=$registered, device=$pluggedDevice")
            isRegistered = registered

            if (registered) {
                Log.i(TAG, "HID Keyboard registered successfully")
                notifyConnectionState()
            } else {
                Log.w(TAG, "HID Keyboard unregistered")
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
            Log.d(TAG, "Connection state changed: $stateStr for device: ${device?.name ?: device?.address}")

            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    isConnected = true
                    Log.i(TAG, "Connected to: ${device?.name ?: device?.address}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (device?.address == connectedDevice?.address) {
                        connectedDevice = null
                        isConnected = false
                        Log.i(TAG, "Disconnected from: ${device?.name ?: device?.address}")
                    }
                }
            }
            notifyConnectionState()
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            super.onGetReport(device, type, id, bufferSize)
            Log.d(TAG, "onGetReport: type=$type, id=$id, bufferSize=$bufferSize")
            // Respond with an empty report
            hidDevice?.replyReport(device, type, id, ByteArray(8))
        }

        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            super.onSetReport(device, type, id, data)
            Log.d(TAG, "onSetReport: type=$type, id=$id, data=${data?.contentToString()}")
            // Acknowledge the report
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
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as BluetoothHidDevice
                registerHidDevice()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            Log.d(TAG, "HID Device service disconnected")
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

    /**
     * Initialize the Bluetooth HID service.
     * Gets the HID Device profile proxy from the Bluetooth adapter.
     */
    fun initialize(): Boolean {
        val adapter = bluetoothAdapter ?: run {
            Log.e(TAG, "Bluetooth not available on this device")
            return false
        }

        if (!adapter.isEnabled) {
            Log.e(TAG, "Bluetooth is not enabled")
            return false
        }

        Log.d(TAG, "Getting HID Device profile proxy...")
        return adapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
    }

    /**
     * Register this app as an HID keyboard device.
     */
    private fun registerHidDevice() {
        val hid = hidDevice ?: run {
            Log.e(TAG, "HID Device proxy not available")
            return
        }

        // Create the SDP record for a keyboard
        val sdpRecord = BluetoothHidDeviceAppSdpSettings(
            "TapToType",                                    // Name
            "Bluetooth HID Keyboard",                       // Description
            "Android",                                      // Provider
            BluetoothHidDevice.SUBCLASS1_KEYBOARD,          // Subclass: Keyboard
            hidDescriptor                                    // HID descriptor
        )

        // QoS settings (use defaults)
        val qosOut = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800,    // Token rate
            9,      // Token bucket size
            0,      // Peak bandwidth
            11250,  // Latency (microseconds)
            11250   // Delay variation
        )

        Log.d(TAG, "Registering HID keyboard app...")
        val result = hid.registerApp(sdpRecord, null, qosOut, Executors.newSingleThreadExecutor(), hidCallback)
        Log.d(TAG, "registerApp result: $result")
    }

    /**
     * Connect to a specific Bluetooth device (the Windows PC).
     */
    fun connectToDevice(device: BluetoothDevice): Boolean {
        val hid = hidDevice ?: run {
            Log.e(TAG, "HID Device proxy not available")
            return false
        }

        if (!isRegistered) {
            Log.e(TAG, "HID app not registered yet")
            return false
        }

        Log.d(TAG, "Connecting to device: ${device.name ?: device.address}")
        return hid.connect(device)
    }

    /**
     * Disconnect from the currently connected device.
     */
    fun disconnect() {
        val hid = hidDevice ?: return
        val device = connectedDevice ?: return
        hid.disconnect(device)
    }

    /**
     * Get paired devices from the Bluetooth adapter.
     */
    fun getPairedDevices(): Set<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices ?: emptySet()
    }

    // ============================================================
    // Key Event Sending
    // ============================================================

    /**
     * Send a single character as an HID key press (key-down + key-up).
     */
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

    /**
     * Send a backspace key press.
     */
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

    /**
     * Send an entire string as a sequence of HID key events.
     * Each character becomes a key-down followed by a key-up report.
     */
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

    /**
     * Send enter key press.
     */
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

    /**
     * Send a single HID report (key-down + key-up) asynchronously.
     */
    private fun sendHidReport(event: HidKeyMapper.HidKeyEvent): Boolean {
        val hid = hidDevice ?: return false
        val device = connectedDevice ?: return false

        keyExecutor.execute {
            try {
                // Send key-down report
                val downReport = HidKeyMapper.createKeyDownReport(event)
                hid.sendReport(device, 0, downReport)

                // Brief delay
                Thread.sleep(KEY_PRESS_DELAY_MS)

                // Send key-up report (all zeros)
                hid.sendReport(device, 0, HidKeyMapper.createKeyUpReport())
            } catch (e: Exception) {
                Log.e(TAG, "Error sending HID report", e)
            }
        }
        return true
    }

    /**
     * Send a single HID report synchronously (must be called from keyExecutor).
     */
    private fun sendHidReportSync(event: HidKeyMapper.HidKeyEvent) {
        val hid = hidDevice ?: return
        val device = connectedDevice ?: return

        try {
            val downReport = HidKeyMapper.createKeyDownReport(event)
            hid.sendReport(device, 0, downReport)
            Thread.sleep(KEY_PRESS_DELAY_MS)
            hid.sendReport(device, 0, HidKeyMapper.createKeyUpReport())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending HID report (sync)", e)
        }
    }

    /**
     * Notify the listener about connection state changes.
     */
    private fun notifyConnectionState() {
        mainHandler.post {
            val statusMessage = when {
                isConnected -> "Connected as Keyboard to ${connectedDevice?.name ?: connectedDevice?.address ?: "Unknown"}"
                isRegistered -> "Registered — Waiting for connection"
                else -> "Not Connected"
            }
            onConnectionStateChanged?.invoke(isConnected, statusMessage)
        }
    }

    /**
     * Clean up resources.
     */
    fun cleanup() {
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
    }
}
