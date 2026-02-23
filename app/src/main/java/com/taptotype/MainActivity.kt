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
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val DISCOVERABLE_DURATION = 120
        private const val PREFS_NAME = "TapToTypePrefs"
        private const val PREF_THEME_MODE = "theme_mode"
    }

    private lateinit var hidService: BluetoothHidService

    // UI elements
    private lateinit var statusIndicator: View
    private lateinit var statusText: TextView
    private lateinit var inputField: EditText
    private lateinit var modeToggle: Switch
    private lateinit var modeLabel: TextView
    private lateinit var sendButton: ImageButton
    private lateinit var clearButton: Button
    private lateinit var setupHelpButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var settingsButton: ImageButton
    private lateinit var deviceListLabel: TextView

    private var isLiveMode: Boolean = true
    private var previousText: String = ""
    private var wizardDialog: AlertDialog? = null

    // Wizard UI refs (held for refreshing from callbacks)
    private var wizardStatusDot: View? = null
    private var wizardStatusLabel: TextView? = null
    private var wizardNextButton: Button? = null
    private var wizardActionButton: Button? = null
    private var wizardCurrentStep: Int = 0
    private var wizardSteps: List<WizardStep>? = null

    // ============================================================
    // Wizard Step data class
    // ============================================================

    data class WizardStep(
        val title: String,
        val description: String,
        val hint: String,
        val canAutoDetect: Boolean,
        val detectCheck: () -> Boolean,
        val detectLabel: (Boolean) -> String,
        val actionLabel: String?,       // Button text, null = no action button
        val actionCallback: (() -> Unit)?  // What happens when action button tapped
    )

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
            refreshWizardStatus()
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
            refreshWizardStatus()
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
            Toast.makeText(this, "Discoverable for $DISCOVERABLE_DURATION seconds ✅", Toast.LENGTH_SHORT).show()
            refreshWizardStatus()
        }
    }

    // ============================================================
    // Lifecycle
    // ============================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        applySavedTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusIndicator = findViewById(R.id.statusIndicator)
        statusText = findViewById(R.id.statusText)
        inputField = findViewById(R.id.inputField)
        modeToggle = findViewById(R.id.modeToggle)
        modeLabel = findViewById(R.id.modeLabel)
        sendButton = findViewById(R.id.sendButton)
        clearButton = findViewById(R.id.clearButton)
        setupHelpButton = findViewById(R.id.setupHelpButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        settingsButton = findViewById(R.id.settingsButton)
        deviceListLabel = findViewById(R.id.deviceListLabel)

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
    // Theme
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
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putInt(PREF_THEME_MODE, mode).apply()
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
        hidService.onConnectionStateChanged = { connected, status ->
            runOnUiThread {
                updateConnectionStatus(connected, status)
                refreshWizardStatus()
            }
        }

        hidService.onDetailedStatus = { message ->
            runOnUiThread {
                Log.d(TAG, "[Status] $message")
                deviceListLabel.text = message
            }
        }

        modeToggle.isChecked = true
        updateModeUI(true)
        modeToggle.setOnCheckedChangeListener { _, isChecked ->
            isLiveMode = isChecked
            updateModeUI(isChecked)
        }

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

        clearButton.setOnClickListener {
            inputField.text.clear()
            previousText = ""
        }

        setupHelpButton.setOnClickListener {
            showSetupWizard()
        }

        disconnectButton.setOnClickListener {
            confirmDisconnect()
        }

        settingsButton.setOnClickListener {
            showSettingsDialog()
        }

        inputField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!isLiveMode || !hidService.isConnected) return
                val currentText = s?.toString() ?: ""
                if (currentText.length < previousText.length) {
                    val deletedCount = previousText.length - currentText.length
                    repeat(deletedCount) { hidService.sendBackspace() }
                } else if (currentText.length > previousText.length) {
                    val newChars = currentText.substring(previousText.length)
                    for (char in newChars) { hidService.sendKeyPress(char) }
                }
                previousText = currentText
            }
        })

        inputField.setOnKeyListener { _, keyCode, event ->
            if (isLiveMode && keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                if (hidService.isConnected) hidService.sendEnter()
                false
            } else false
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
            setupHelpButton.visibility = View.GONE
            disconnectButton.visibility = View.VISIBLE
            wizardDialog?.dismiss()
        } else {
            statusIndicator.setBackgroundResource(R.drawable.status_disconnected)
            setupHelpButton.visibility = View.VISIBLE
            disconnectButton.visibility = View.GONE
        }
    }

    // ============================================================
    // Settings
    // ============================================================

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val currentTheme = prefs.getInt(PREF_THEME_MODE, 0)

        val items = mutableListOf<String>()
        items.add("🎨  Theme: ${when(currentTheme) { 0 -> "Light ☀️"; 1 -> "Dark 🌙"; else -> "System 🔄" }}")
        if (hidService.isConnected) items.add("⚡  Disconnect from PC")
        items.add("🔄  Re-initialize Bluetooth HID")
        items.add("🔍  Show Diagnostics")

        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("⚙️  Settings")
            .setItems(items.toTypedArray()) { _, which ->
                when {
                    which == 0 -> showThemeSelector()
                    hidService.isConnected && which == 1 -> confirmDisconnect()
                    which == (if (hidService.isConnected) 2 else 1) -> reinitializeBluetooth()
                    which == (if (hidService.isConnected) 3 else 2) -> showDiagnostics()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showThemeSelector() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val currentTheme = prefs.getInt(PREF_THEME_MODE, 0)
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("Choose Theme")
            .setSingleChoiceItems(arrayOf("☀️  Light", "🌙  Dark", "🔄  Follow System"), currentTheme) { dialog, which ->
                setThemeMode(which)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDisconnect() {
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("Disconnect from PC")
            .setMessage("Are you sure you want to disconnect?")
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
            Toast.makeText(this, "Failed to re-initialize", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun showDiagnostics() {
        val diagInfo = hidService.getDiagnosticInfo()
        val tv = TextView(this).apply {
            text = diagInfo
            setPadding(48, 32, 48, 32)
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
        }
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("🔍  Diagnostics")
            .setView(tv)
            .setPositiveButton("OK", null)
            .setNeutralButton("Copy") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Diagnostics", diagInfo))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ============================================================
    // Setup Wizard
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
        val actionButton = view.findViewById<Button>(R.id.wizardActionButton)
        val backButton = view.findViewById<Button>(R.id.wizardBackButton)
        val nextButton = view.findViewById<Button>(R.id.wizardNextButton)
        val dismissButton = view.findViewById<TextView>(R.id.wizardDismissButton)

        // Save refs for external refresh
        wizardStatusDot = statusDot
        wizardStatusLabel = statusLabel
        wizardNextButton = nextButton
        wizardActionButton = actionButton

        val dots = arrayOf(
            view.findViewById<View>(R.id.wizardDot1),
            view.findViewById<View>(R.id.wizardDot2),
            view.findViewById<View>(R.id.wizardDot3),
            view.findViewById<View>(R.id.wizardDot4),
            view.findViewById<View>(R.id.wizardDot5)
        )

        wizardCurrentStep = 0

        val steps = listOf(
            WizardStep(
                title = "Enable Bluetooth",
                description = "Make sure Bluetooth is turned on in your phone's Settings.",
                hint = "The app needs Bluetooth to communicate with your PC.",
                canAutoDetect = true,
                detectCheck = { hidService.bluetoothAdapter?.isEnabled == true },
                detectLabel = { if (it) "✅ Bluetooth is ON" else "❌ Bluetooth is OFF" },
                actionLabel = "Turn On Bluetooth",
                actionCallback = {
                    val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    enableBluetoothLauncher.launch(enableIntent)
                }
            ),
            WizardStep(
                title = "Grant Permissions",
                description = "The app needs Bluetooth permissions to discover and connect to your PC.",
                hint = "Tap the button below to grant permissions, or go to:\nSettings → Apps → TapToType → Permissions",
                canAutoDetect = true,
                detectCheck = {
                    requiredPermissions.all {
                        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
                    }
                },
                detectLabel = { if (it) "✅ All permissions granted" else "❌ Permissions needed" },
                actionLabel = "Grant Permissions",
                actionCallback = {
                    val missing = requiredPermissions.filter {
                        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isNotEmpty()) {
                        permissionLauncher.launch(missing.toTypedArray())
                    } else {
                        Toast.makeText(this, "All permissions already granted ✅", Toast.LENGTH_SHORT).show()
                        refreshWizardStatus()
                    }
                }
            ),
            WizardStep(
                title = "Make Phone Discoverable",
                description = "Let your Windows PC find your phone via Bluetooth.",
                hint = "This makes your phone visible for 2 minutes.\nDo this BEFORE searching on your PC.",
                canAutoDetect = false,
                detectCheck = { true },
                detectLabel = { "" },
                actionLabel = "📡  Make Discoverable",
                actionCallback = {
                    requestDiscoverableMode()
                }
            ),
            WizardStep(
                title = "Pair from Windows PC",
                description = "On your Windows PC, go to:\n\nSettings → Bluetooth & Devices\n→ Add device → Bluetooth",
                hint = "Select your phone from the list.\nAccept the pairing prompt on BOTH devices.",
                canAutoDetect = true,
                detectCheck = { hidService.getPairedDevices().isNotEmpty() },
                detectLabel = { if (it) "✅ Paired device(s) found" else "⏳ Waiting — pair from your PC" },
                actionLabel = null,
                actionCallback = null
            ),
            WizardStep(
                title = "Connect to your PC",
                description = "Select your Windows PC from the list below to connect as a keyboard.",
                hint = "Once connected, click any text field on your PC and start typing!",
                canAutoDetect = true,
                detectCheck = { hidService.isConnected },
                detectLabel = { if (it) "✅ Connected!" else "⏳ Not connected yet" },
                actionLabel = "🔗  Connect to Device",
                actionCallback = {
                    showDeviceSelectionDialog()
                }
            )
        )
        wizardSteps = steps

        fun updateWizardUI() {
            val step = steps[wizardCurrentStep]
            stepBadge.text = "${wizardCurrentStep + 1}"
            stepTitle.text = step.title
            stepDescription.text = step.description
            stepHint.text = step.hint

            for (i in dots.indices) {
                when {
                    i < wizardCurrentStep -> dots[i].setBackgroundResource(R.drawable.wizard_dot_complete)
                    i == wizardCurrentStep -> dots[i].setBackgroundResource(R.drawable.wizard_dot_active)
                    else -> dots[i].setBackgroundResource(R.drawable.wizard_dot_inactive)
                }
            }

            // Status detection row
            if (step.canAutoDetect) {
                statusRow.visibility = View.VISIBLE
                val detected = step.detectCheck()
                statusLabel.text = step.detectLabel(detected)
                statusDot.setBackgroundResource(
                    if (detected) R.drawable.status_connected else R.drawable.status_disconnected
                )
                nextButton.isEnabled = detected
                nextButton.alpha = if (detected) 1.0f else 0.5f
            } else {
                statusRow.visibility = View.GONE
                nextButton.isEnabled = true
                nextButton.alpha = 1.0f
            }

            // Action button
            if (step.actionLabel != null && step.actionCallback != null) {
                actionButton.visibility = View.VISIBLE
                actionButton.text = step.actionLabel

                // Hide action button if step is already complete
                if (step.canAutoDetect && step.detectCheck()) {
                    actionButton.visibility = View.GONE
                }
            } else {
                actionButton.visibility = View.GONE
            }

            backButton.visibility = if (wizardCurrentStep > 0) View.VISIBLE else View.GONE
            nextButton.text = if (wizardCurrentStep == steps.size - 1) "Done ✅" else "Next →"
        }

        // Action button click — delegates to the current step's callback
        actionButton.setOnClickListener {
            steps[wizardCurrentStep].actionCallback?.invoke()
        }

        nextButton.setOnClickListener {
            if (wizardCurrentStep < steps.size - 1) {
                wizardCurrentStep++
                updateWizardUI()
            } else {
                wizardDialog?.dismiss()
            }
        }

        backButton.setOnClickListener {
            if (wizardCurrentStep > 0) {
                wizardCurrentStep--
                updateWizardUI()
            }
        }

        dismissButton.setOnClickListener {
            wizardDialog?.dismiss()
        }

        wizardDialog = AlertDialog.Builder(this, R.style.SetupWizardDialog)
            .setView(view)
            .setCancelable(true)
            .setOnDismissListener {
                // Clear refs when dismissed
                wizardStatusDot = null
                wizardStatusLabel = null
                wizardNextButton = null
                wizardActionButton = null
                wizardSteps = null
            }
            .create()

        wizardDialog?.window?.setBackgroundDrawableResource(R.drawable.card_background)
        updateWizardUI()
        wizardDialog?.show()
    }

    /**
     * Refresh the wizard's status indicator from outside (e.g., after a BT callback).
     * This lets the wizard react in real-time to Bluetooth state changes.
     */
    private fun refreshWizardStatus() {
        val steps = wizardSteps ?: return
        if (wizardCurrentStep >= steps.size) return
        val step = steps[wizardCurrentStep]

        if (step.canAutoDetect) {
            val detected = step.detectCheck()
            wizardStatusLabel?.text = step.detectLabel(detected)
            wizardStatusDot?.setBackgroundResource(
                if (detected) R.drawable.status_connected else R.drawable.status_disconnected
            )
            wizardNextButton?.isEnabled = detected
            wizardNextButton?.alpha = if (detected) 1.0f else 0.5f

            // Hide action button once step is complete
            if (detected && step.actionLabel != null) {
                wizardActionButton?.visibility = View.GONE
            }
        }
    }

    // ============================================================
    // Permissions
    // ============================================================

    private fun checkPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            initializeBluetooth()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // ============================================================
    // Bluetooth initialization
    // ============================================================

    @SuppressLint("MissingPermission")
    private fun initializeBluetooth() {
        val adapter = hidService.bluetoothAdapter
        if (adapter == null) {
            Toast.makeText(this, "Bluetooth not available", Toast.LENGTH_LONG).show()
            return
        }
        if (!adapter.isEnabled) {
            val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableIntent)
            return
        }

        val success = hidService.initialize()
        if (success) {
            statusText.text = "Initializing HID Keyboard..."
        } else {
            statusText.text = "Failed to initialize HID"
            showErrorDialog(
                "HID Initialization Failed",
                "Could not initialize Bluetooth HID.\n\n" +
                        "Your device may not support the Bluetooth HID Device profile.\n\n" +
                        "Try: Settings ⚙️ → Re-initialize Bluetooth HID"
            )
        }

        updatePairedDevicesList()

        // Auto-show wizard if not connected
        if (!hidService.isConnected) {
            setupHelpButton.postDelayed({ showSetupWizard() }, 500)
        }
    }

    // ============================================================
    // Device selection (called from wizard)
    // ============================================================

    @SuppressLint("MissingPermission")
    private fun showDeviceSelectionDialog() {
        val pairedDevices = hidService.getPairedDevices().toList()

        if (pairedDevices.isEmpty()) {
            showErrorDialog(
                "No Paired Devices",
                "No devices are paired.\n\n" +
                        "Go back to Step 3 (Make Discoverable) and Step 4 (Pair from PC) first."
            )
            return
        }

        val deviceNames = pairedDevices.map { device ->
            val typeStr = when (device.type) {
                BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic BT"
                BluetoothDevice.DEVICE_TYPE_LE -> "⚠️ BLE only"
                BluetoothDevice.DEVICE_TYPE_DUAL -> "Classic + BLE"
                else -> "Unknown"
            }
            "${device.name ?: "Unknown"}\n${device.address} ($typeStr)"
        }.toTypedArray()

        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("Select your PC")
            .setItems(deviceNames) { _, which ->
                val selected = pairedDevices[which]
                Log.d(TAG, "Selected: ${selected.name} (${selected.address})")
                statusText.text = "Connecting to ${selected.name ?: selected.address}..."
                deviceListLabel.text = "Attempting connection..."

                val result = hidService.connectToDevice(selected)
                if (!result.accepted) {
                    showErrorDialog("Connection Failed", result.message)
                } else {
                    Toast.makeText(this, "Connecting...", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun updatePairedDevicesList() {
        val devices = hidService.getPairedDevices()
        deviceListLabel.text = if (devices.isEmpty()) {
            "No paired devices"
        } else {
            "Paired: ${devices.joinToString(", ") { it.name ?: it.address }}"
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestDiscoverableMode() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_DURATION)
        }
        discoverableLauncher.launch(intent)
    }

    private fun showErrorDialog(title: String, message: String) {
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("⚠️  $title")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .setNeutralButton("Diagnostics") { _, _ -> showDiagnostics() }
            .show()
    }
}
