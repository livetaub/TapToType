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
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat

/**
 * MainActivity handles the UI for the TapToType app.
 *
 * Features:
 *   - Toggle between Live Input Mode and Send Button Mode
 *   - Bluetooth pairing flow and device selection
 *   - Connection status display
 *   - Multi-step setup wizard when not connected
 *   - Dark/Light theme toggle (default: Light)
 *   - Settings with disconnect option
 *   - Text input and send/clear controls
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val DISCOVERABLE_DURATION = 120 // seconds
        private const val PREFS_NAME = "TapToTypePrefs"
        private const val PREF_THEME_MODE = "theme_mode" // 0=light, 1=dark, 2=system
    }

    // Bluetooth HID service
    private lateinit var hidService: BluetoothHidService

    // UI elements
    private lateinit var statusIndicator: View
    private lateinit var statusText: TextView
    private lateinit var inputField: EditText
    private lateinit var modeToggle: Switch
    private lateinit var modeLabel: TextView
    private lateinit var sendButton: ImageButton
    private lateinit var clearButton: Button
    private lateinit var connectButton: Button
    private lateinit var discoverableButton: Button
    private lateinit var deviceListLabel: TextView
    private lateinit var setupHelpButton: Button
    private lateinit var settingsButton: ImageButton

    // State
    private var isLiveMode: Boolean = true
    private var previousText: String = ""

    // Setup wizard dialog reference
    private var wizardDialog: AlertDialog? = null

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
        // Apply saved theme before calling super.onCreate
        applySavedTheme()

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
        setupHelpButton = findViewById(R.id.setupHelpButton)
        settingsButton = findViewById(R.id.settingsButton)

        // Initialize HID service
        hidService = BluetoothHidService(this)

        setupUI()
        checkPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        wizardDialog?.dismiss()
        hidService.cleanup()
    }

    // ============================================================
    // Theme Management
    // ============================================================

    private fun applySavedTheme() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        when (prefs.getInt(PREF_THEME_MODE, 0)) {
            0 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            1 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            2 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    private fun setThemeMode(mode: Int) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putInt(PREF_THEME_MODE, mode).apply()

        when (mode) {
            0 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            1 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            2 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
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

        // Send button (now an ImageButton)
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

        // Setup help button — opens the wizard
        setupHelpButton.setOnClickListener {
            showSetupWizard()
        }

        // Settings button
        settingsButton.setOnClickListener {
            showSettingsDialog()
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
            // Hide setup help when connected
            setupHelpButton.visibility = View.GONE
            // Dismiss wizard if open
            wizardDialog?.dismiss()
        } else {
            statusIndicator.setBackgroundResource(R.drawable.status_disconnected)
            connectButton.text = "Connect to Device"
            // Show setup help when not connected
            setupHelpButton.visibility = View.VISIBLE
        }
    }

    // ============================================================
    // Settings Dialog
    // ============================================================

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val currentTheme = prefs.getInt(PREF_THEME_MODE, 0)

        val items = mutableListOf<String>()
        items.add("Theme: ${when(currentTheme) { 0 -> "Light ☀️"; 1 -> "Dark 🌙"; else -> "System 🔄" }}")

        if (hidService.isConnected) {
            items.add("⚡ Disconnect from PC")
        }

        items.add("🔄 Re-initialize Bluetooth HID")
        items.add("📖 Setup Guide")

        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("⚙️  Settings")
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> showThemeSelector()
                    1 -> if (hidService.isConnected) {
                        confirmDisconnect()
                    } else {
                        reinitializeBluetooth()
                    }
                    2 -> if (hidService.isConnected) {
                        reinitializeBluetooth()
                    } else {
                        showSetupWizard()
                    }
                    3 -> showSetupWizard()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showThemeSelector() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val currentTheme = prefs.getInt(PREF_THEME_MODE, 0)

        val themes = arrayOf("☀️  Light", "🌙  Dark", "🔄  Follow System")

        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("Choose Theme")
            .setSingleChoiceItems(themes, currentTheme) { dialog, which ->
                setThemeMode(which)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDisconnect() {
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("Disconnect from PC")
            .setMessage("Are you sure you want to disconnect from the currently connected device?")
            .setPositiveButton("Disconnect") { _, _ ->
                hidService.disconnect()
                Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun reinitializeBluetooth() {
        hidService.cleanup()
        val success = hidService.initialize()
        if (success) {
            Toast.makeText(this, "Bluetooth HID re-initialized", Toast.LENGTH_SHORT).show()
            statusText.text = "Initializing HID Keyboard..."
        } else {
            Toast.makeText(this, "Failed to re-initialize HID", Toast.LENGTH_SHORT).show()
        }
    }

    // ============================================================
    // Setup Wizard Dialog
    // ============================================================

    @SuppressLint("MissingPermission")
    private fun showSetupWizard() {
        val view = layoutInflater.inflate(R.layout.dialog_setup_wizard, null)

        val stepBadge = view.findViewById<TextView>(R.id.wizardStepBadge)
        val stepTitle = view.findViewById<TextView>(R.id.wizardStepTitle)
        val stepDescription = view.findViewById<TextView>(R.id.wizardStepDescription)
        val stepHint = view.findViewById<TextView>(R.id.wizardStepHint)
        val statusRow = view.findViewById<LinearLayout>(R.id.wizardStatusRow)
        val statusDot = view.findViewById<View>(R.id.wizardStatusDot)
        val statusLabel = view.findViewById<TextView>(R.id.wizardStatusLabel)
        val backButton = view.findViewById<Button>(R.id.wizardBackButton)
        val nextButton = view.findViewById<Button>(R.id.wizardNextButton)
        val dismissButton = view.findViewById<TextView>(R.id.wizardDismissButton)

        val dots = arrayOf(
            view.findViewById<View>(R.id.wizardDot1),
            view.findViewById<View>(R.id.wizardDot2),
            view.findViewById<View>(R.id.wizardDot3),
            view.findViewById<View>(R.id.wizardDot4),
            view.findViewById<View>(R.id.wizardDot5)
        )

        var currentStep = 0

        // Step data
        data class WizardStep(
            val title: String,
            val description: String,
            val hint: String,
            val canAutoDetect: Boolean,
            val detectCheck: () -> Boolean,
            val detectLabel: (Boolean) -> String
        )

        val steps = listOf(
            WizardStep(
                title = "Enable Bluetooth",
                description = "Make sure Bluetooth is turned on in your phone's Settings.",
                hint = "Go to Settings → Bluetooth and toggle it ON.\nThe app needs Bluetooth to communicate with your PC.",
                canAutoDetect = true,
                detectCheck = { hidService.bluetoothAdapter?.isEnabled == true },
                detectLabel = { if (it) "✅ Bluetooth is ON" else "❌ Bluetooth is OFF — please enable it" }
            ),
            WizardStep(
                title = "Grant Permissions",
                description = "The app needs Bluetooth permissions to discover and connect to your PC.",
                hint = "If you haven't already, tap \"Allow\" when prompted.\nYou can also go to Settings → Apps → TapToType → Permissions.",
                canAutoDetect = true,
                detectCheck = {
                    requiredPermissions.all {
                        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
                    }
                },
                detectLabel = { if (it) "✅ All permissions granted" else "❌ Some permissions missing — please grant them" }
            ),
            WizardStep(
                title = "Make Phone Discoverable",
                description = "Tap the \"Make Discoverable\" button on the main screen to let your Windows PC find your phone.",
                hint = "This makes your phone visible via Bluetooth for 2 minutes.\nDo this BEFORE searching on your PC.",
                canAutoDetect = false,
                detectCheck = { true },
                detectLabel = { "Tap \"Make Discoverable\" on the main screen after closing this guide" }
            ),
            WizardStep(
                title = "Pair from Windows",
                description = "On your Windows PC, go to:\n\nSettings → Bluetooth & Devices → Add device → Bluetooth",
                hint = "Your phone should appear in the list as \"TapToType\" or your phone's name.\nSelect it and accept the pairing prompt on BOTH devices.\n\nThis step happens on your Windows PC.",
                canAutoDetect = true,
                detectCheck = { hidService.getPairedDevices().isNotEmpty() },
                detectLabel = { if (it) "✅ Paired device(s) found" else "⏳ No paired devices detected yet — pair from your PC" }
            ),
            WizardStep(
                title = "Connect in the App",
                description = "Tap \"Connect to Device\" on the main screen and select your Windows PC from the list.",
                hint = "The status will change to \"Connected as Keyboard\".\nOnce connected, click any text field on your PC and start typing!\n\nIf it fails, try unpairing and re-pairing, or restart Bluetooth on both devices.",
                canAutoDetect = true,
                detectCheck = { hidService.isConnected },
                detectLabel = { if (it) "✅ Connected!" else "⏳ Not connected yet — tap \"Connect to Device\" on the main screen" }
            )
        )

        fun updateWizardUI() {
            val step = steps[currentStep]
            stepBadge.text = "${currentStep + 1}"
            stepTitle.text = step.title
            stepDescription.text = step.description
            stepHint.text = step.hint

            // Update dots
            for (i in dots.indices) {
                when {
                    i < currentStep -> dots[i].setBackgroundResource(R.drawable.wizard_dot_complete)
                    i == currentStep -> dots[i].setBackgroundResource(R.drawable.wizard_dot_active)
                    else -> dots[i].setBackgroundResource(R.drawable.wizard_dot_inactive)
                }
            }

            // Update status detection
            if (step.canAutoDetect) {
                statusRow.visibility = View.VISIBLE
                val detected = step.detectCheck()
                statusLabel.text = step.detectLabel(detected)
                statusDot.setBackgroundResource(
                    if (detected) R.drawable.status_connected else R.drawable.status_disconnected
                )

                // Only enable Next if this auto-detectable step is done
                nextButton.isEnabled = detected
                nextButton.alpha = if (detected) 1.0f else 0.5f
            } else {
                statusRow.visibility = View.GONE
                // Non-detectable steps always allow Next (user confirms "Done")
                nextButton.isEnabled = true
                nextButton.alpha = 1.0f
            }

            // Back button visibility
            backButton.visibility = if (currentStep > 0) View.VISIBLE else View.GONE

            // Next button text
            nextButton.text = if (currentStep == steps.size - 1) "Done" else "Next"
        }

        nextButton.setOnClickListener {
            if (currentStep < steps.size - 1) {
                currentStep++
                updateWizardUI()
            } else {
                wizardDialog?.dismiss()
            }
        }

        backButton.setOnClickListener {
            if (currentStep > 0) {
                currentStep--
                updateWizardUI()
            }
        }

        dismissButton.setOnClickListener {
            wizardDialog?.dismiss()
        }

        // Build dialog
        wizardDialog = AlertDialog.Builder(this, R.style.SetupWizardDialog)
            .setView(view)
            .setCancelable(true)
            .create()

        wizardDialog?.window?.setBackgroundDrawableResource(R.drawable.card_background)

        updateWizardUI()
        wizardDialog?.show()
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

        // Show wizard automatically if not connected and no paired devices
        if (!hidService.isConnected) {
            val pairedDevices = hidService.getPairedDevices()
            if (pairedDevices.isEmpty()) {
                // First time — show setup wizard automatically
                setupHelpButton.postDelayed({ showSetupWizard() }, 500)
            }
        }
    }

    // ============================================================
    // Device selection
    // ============================================================

    @SuppressLint("MissingPermission")
    private fun showDeviceSelectionDialog() {
        val pairedDevices = hidService.getPairedDevices().toList()

        if (pairedDevices.isEmpty()) {
            Toast.makeText(this, "No paired devices. Pair your Windows PC via Bluetooth settings first.", Toast.LENGTH_LONG).show()
            showSetupWizard()
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
                    Toast.makeText(this, "Failed to initiate connection. Try re-initializing from Settings.", Toast.LENGTH_LONG).show()
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
