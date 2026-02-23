package com.taptotype

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * MainActivity handles the UI for the TapToType app.
 *
 * Features:
 *   - Toggle between Live Input Mode and Send Button Mode
 *   - Bluetooth pairing flow and device selection
 *   - Connection status display
 *   - Text input and send/clear controls
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val DISCOVERABLE_DURATION = 120 // seconds
    }

    // Bluetooth HID service
    private lateinit var hidService: BluetoothHidService

    // UI elements
    private lateinit var statusIndicator: View
    private lateinit var statusText: TextView
    private lateinit var inputField: EditText
    private lateinit var modeToggle: Switch
    private lateinit var modeLabel: TextView
    private lateinit var sendButton: Button
    private lateinit var clearButton: Button
    private lateinit var connectButton: Button
    private lateinit var discoverableButton: Button
    private lateinit var deviceListLabel: TextView
    private lateinit var instructionsHeader: LinearLayout
    private lateinit var instructionsBody: LinearLayout
    private lateinit var instructionsArrow: TextView

    // State
    private var isLiveMode: Boolean = true
    private var previousText: String = ""

    // ============================================================
    // Permission handling
    // ============================================================

    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Log.d(TAG, "All Bluetooth permissions granted")
            initializeBluetooth()
        } else {
            Log.w(TAG, "Some permissions denied: $permissions")
            Toast.makeText(this, "Bluetooth permissions are required", Toast.LENGTH_LONG).show()
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            initializeBluetooth()
        } else {
            Toast.makeText(this, "Bluetooth must be enabled", Toast.LENGTH_SHORT).show()
        }
    }

    private val discoverableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_CANCELED) {
            Toast.makeText(this, "Discoverable mode denied", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Device is now discoverable for $DISCOVERABLE_DURATION seconds", Toast.LENGTH_SHORT).show()
        }
    }

    // ============================================================
    // Lifecycle
    // ============================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        statusIndicator = findViewById(R.id.statusIndicator)
        statusText = findViewById(R.id.statusText)
        inputField = findViewById(R.id.inputField)
        modeToggle = findViewById(R.id.modeToggle)
        modeLabel = findViewById(R.id.modeLabel)
        sendButton = findViewById(R.id.sendButton)
        clearButton = findViewById(R.id.clearButton)
        connectButton = findViewById(R.id.connectButton)
        discoverableButton = findViewById(R.id.discoverableButton)
        deviceListLabel = findViewById(R.id.deviceListLabel)
        instructionsHeader = findViewById(R.id.instructionsHeader)
        instructionsBody = findViewById(R.id.instructionsBody)
        instructionsArrow = findViewById(R.id.instructionsArrow)

        // Initialize HID service
        hidService = BluetoothHidService(this)

        setupUI()
        checkPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        hidService.cleanup()
    }

    // ============================================================
    // UI Setup
    // ============================================================

    private fun setupUI() {
        // Connection state listener
        hidService.onConnectionStateChanged = { connected, status ->
            runOnUiThread {
                updateConnectionStatus(connected, status)
            }
        }

        // Mode toggle
        modeToggle.isChecked = true // Default: Live Input Mode
        updateModeUI(true)

        modeToggle.setOnCheckedChangeListener { _, isChecked ->
            isLiveMode = isChecked
            updateModeUI(isChecked)
        }

        // Send button
        sendButton.setOnClickListener {
            val text = inputField.text.toString()
            if (text.isNotEmpty()) {
                if (hidService.isConnected) {
                    hidService.sendString(text)
                    Toast.makeText(this, "Sent: ${text.take(30)}${if (text.length > 30) "..." else ""}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Not connected to any device", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Clear button
        clearButton.setOnClickListener {
            inputField.text.clear()
            previousText = ""
        }

        // Connect button
        connectButton.setOnClickListener {
            if (hidService.isConnected) {
                hidService.disconnect()
            } else {
                showDeviceSelectionDialog()
            }
        }

        // Discoverable button
        discoverableButton.setOnClickListener {
            requestDiscoverableMode()
        }

        // Instructions expand/collapse
        instructionsHeader.setOnClickListener {
            val isVisible = instructionsBody.visibility == View.VISIBLE
            if (isVisible) {
                instructionsBody.visibility = View.GONE
                instructionsArrow.text = "▼"
            } else {
                instructionsBody.visibility = View.VISIBLE
                instructionsArrow.text = "▲"
            }
        }

        // Live mode text watcher
        inputField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (!isLiveMode) return
                if (!hidService.isConnected) return

                val currentText = s?.toString() ?: ""

                if (currentText.length < previousText.length) {
                    // Characters deleted - send backspace(s)
                    val deletedCount = previousText.length - currentText.length
                    for (i in 0 until deletedCount) {
                        hidService.sendBackspace()
                    }
                } else if (currentText.length > previousText.length) {
                    // Characters added - send new characters
                    val newChars = currentText.substring(previousText.length)
                    for (char in newChars) {
                        hidService.sendKeyPress(char)
                    }
                }

                previousText = currentText
            }
        })

        // Handle Enter key in live mode
        inputField.setOnKeyListener { _, keyCode, event ->
            if (isLiveMode && keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                if (hidService.isConnected) {
                    hidService.sendEnter()
                }
                // Don't consume - let the newline appear in the field
                false
            } else {
                false
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateModeUI(isLive: Boolean) {
        if (isLive) {
            modeLabel.text = "Mode: Live Input"
            sendButton.visibility = View.GONE
            inputField.hint = "Start typing — keystrokes sent live..."
        } else {
            modeLabel.text = "Mode: Send Button"
            sendButton.visibility = View.VISIBLE
            inputField.hint = "Type your message here..."
        }
    }

    private fun updateConnectionStatus(connected: Boolean, status: String) {
        statusText.text = status

        if (connected) {
            statusIndicator.setBackgroundResource(R.drawable.status_connected)
            connectButton.text = "Disconnect"
            // Auto-collapse instructions when connected
            instructionsBody.visibility = View.GONE
            instructionsArrow.text = "▼"
        } else {
            statusIndicator.setBackgroundResource(R.drawable.status_disconnected)
            connectButton.text = "Connect to Device"
        }
    }

    // ============================================================
    // Permissions
    // ============================================================

    private fun checkPermissions() {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            initializeBluetooth()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    // ============================================================
    // Bluetooth initialization
    // ============================================================

    @SuppressLint("MissingPermission")
    private fun initializeBluetooth() {
        val adapter = hidService.bluetoothAdapter
        if (adapter == null) {
            Toast.makeText(this, "Bluetooth not available on this device", Toast.LENGTH_LONG).show()
            return
        }

        if (!adapter.isEnabled) {
            val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableIntent)
            return
        }

        // Initialize HID service
        val success = hidService.initialize()
        if (success) {
            Log.d(TAG, "Bluetooth HID initialization started")
            statusText.text = "Initializing HID Keyboard..."
        } else {
            Log.e(TAG, "Failed to initialize Bluetooth HID")
            statusText.text = "Failed to initialize HID"
            Toast.makeText(this, "Failed to initialize Bluetooth HID service", Toast.LENGTH_LONG).show()
        }

        // Show paired devices
        updatePairedDevicesList()
    }

    // ============================================================
    // Device selection
    // ============================================================

    @SuppressLint("MissingPermission")
    private fun showDeviceSelectionDialog() {
        val pairedDevices = hidService.getPairedDevices().toList()

        if (pairedDevices.isEmpty()) {
            Toast.makeText(this, "No paired devices. Pair your Windows PC via Bluetooth settings first.", Toast.LENGTH_LONG).show()
            return
        }

        val deviceNames = pairedDevices.map { "${it.name ?: "Unknown"}\n${it.address}" }.toTypedArray()

        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("Select Device")
            .setItems(deviceNames) { _, which ->
                val selectedDevice = pairedDevices[which]
                Log.d(TAG, "Selected device: ${selectedDevice.name} (${selectedDevice.address})")

                val connected = hidService.connectToDevice(selectedDevice)
                if (connected) {
                    statusText.text = "Connecting to ${selectedDevice.name ?: selectedDevice.address}..."
                } else {
                    Toast.makeText(this, "Failed to initiate connection", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun updatePairedDevicesList() {
        val devices = hidService.getPairedDevices()
        if (devices.isEmpty()) {
            deviceListLabel.text = "No paired devices. Pair your PC first."
        } else {
            val names = devices.joinToString(", ") { it.name ?: it.address }
            deviceListLabel.text = "Paired: $names"
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestDiscoverableMode() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_DURATION)
        }
        discoverableLauncher.launch(intent)
    }
}
