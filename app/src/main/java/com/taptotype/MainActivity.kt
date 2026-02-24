package com.taptotype

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.*
import android.widget.ArrayAdapter
import org.json.JSONArray
import org.json.JSONObject
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
        private const val PREF_SHIFT_ENTER = "enter_is_shift"
        private const val PREF_SAVED_DEVICES = "saved_devices"
    }

    private lateinit var hidService: BluetoothHidService

    // UI elements
    private lateinit var statusIndicator: View
    private lateinit var statusText: TextView
    private lateinit var inputField: EditText
    private lateinit var modeSpinner: Spinner
    private lateinit var sendButton: ImageButton
    private lateinit var clearButton: Button
    private lateinit var addNewDeviceButton: Button
    private lateinit var savedDevicesSection: LinearLayout
    private lateinit var savedDevicesContainer: LinearLayout
    private lateinit var disconnectButton: Button
    private lateinit var settingsButton: ImageButton
    private lateinit var deviceListLabel: TextView

    private var isLiveMode: Boolean = true
    private var previousText: String = ""
    private var wizardDialog: AlertDialog? = null
    private var hasPromptedSave = false  // prevent double save-prompt
    private var ignoreTextChanges = false // suppress TextWatcher during programmatic clears

    // Wizard UI refs (held for refreshing from callbacks)
    private var wizardStatusDot: View? = null
    private var wizardStatusLabel: TextView? = null
    private var wizardNextButton: Button? = null
    private var wizardActionButton: Button? = null
    private var wizardCurrentStep: Int = 0
    private var wizardSteps: List<WizardStep>? = null

    // Saved device model
    data class SavedDevice(val name: String, val macAddress: String, val btName: String)

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
        modeSpinner = findViewById(R.id.modeSpinner)
        sendButton = findViewById(R.id.sendButton)
        clearButton = findViewById(R.id.clearButton)
        addNewDeviceButton = findViewById(R.id.addNewDeviceButton)
        savedDevicesSection = findViewById(R.id.savedDevicesSection)
        savedDevicesContainer = findViewById(R.id.savedDevicesContainer)
        disconnectButton = findViewById(R.id.disconnectButton)
        settingsButton = findViewById(R.id.settingsButton)
        deviceListLabel = findViewById(R.id.deviceListLabel)

        hidService = BluetoothHidService(this)
        hidService.useShiftEnter = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(PREF_SHIFT_ENTER, false)
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
                // Prompt to save device after first successful connection
                if (connected && !hasPromptedSave) {
                    hasPromptedSave = true
                    promptSaveDevice()
                }
            }
        }

        hidService.onDetailedStatus = { message ->
            runOnUiThread {
                Log.d(TAG, "[Status] $message")
                deviceListLabel.text = message
            }
        }

        // Mode spinner setup
        val modeOptions = arrayOf("⌨️  Live Typing", "📤  Type & Send")
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modeOptions)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        modeSpinner.adapter = spinnerAdapter
        modeSpinner.setSelection(0) // Default: Live Typing
        modeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                isLiveMode = (position == 0)
                updateModeUI(isLiveMode)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        sendButton.setOnClickListener {
            val text = inputField.text.toString()
            if (text.isNotEmpty()) {
                if (hidService.isConnected) {
                    hidService.sendString(text)
                    ignoreTextChanges = true
                    inputField.text.clear()
                    previousText = ""
                    ignoreTextChanges = false
                    Toast.makeText(this, "Sent ✅", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Not connected to any device", Toast.LENGTH_SHORT).show()
                }
            }
        }

        clearButton.setOnClickListener {
            ignoreTextChanges = true
            inputField.text.clear()
            previousText = ""
            ignoreTextChanges = false
        }

        addNewDeviceButton.setOnClickListener {
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
                if (ignoreTextChanges) return
                if (!hidService.isConnected) {
                    previousText = s?.toString() ?: ""
                    return
                }

                val currentText = s?.toString() ?: ""

                // Backspace works in BOTH modes
                if (currentText.length < previousText.length) {
                    val deletedCount = previousText.length - currentText.length
                    repeat(deletedCount) { hidService.sendBackspace() }
                } else if (isLiveMode && currentText.length > previousText.length) {
                    // Live mode: send new characters immediately
                    val newChars = currentText.substring(previousText.length)
                    for (char in newChars) { hidService.sendKeyPress(char) }
                }

                previousText = currentText
            }
        })

        // Enter key works in both modes
        inputField.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                if (hidService.isConnected) hidService.sendEnter()
                false
            } else false
        }

        // Immediately sync UI with current service state
        // (handles Activity recreation while already connected)
        val connDevice = hidService.getConnectedDevice()
        @SuppressLint("MissingPermission")
        val connName = connDevice?.name ?: connDevice?.address ?: ""
        val currentStatus = when {
            hidService.isConnected -> "Connected as Keyboard to $connName"
            hidService.isRegistered -> "Ready — Tap 'Connect to Device'"
            else -> "Not Connected"
        }
        updateConnectionStatus(hidService.isConnected, currentStatus)
    }

    @SuppressLint("MissingPermission")
    private fun updateModeUI(isLive: Boolean) {
        if (isLive) {
            sendButton.visibility = View.GONE
            inputField.hint = "Start typing — keystrokes sent live..."
        } else {
            sendButton.visibility = View.VISIBLE
            inputField.hint = "Type your message, then tap Send..."
        }
    }

    private fun updateConnectionStatus(connected: Boolean, status: String) {
        statusText.text = status
        if (connected) {
            statusIndicator.setBackgroundResource(R.drawable.status_connected)
            savedDevicesSection.visibility = View.GONE
            disconnectButton.visibility = View.VISIBLE
            wizardDialog?.dismiss()
        } else {
            statusIndicator.setBackgroundResource(R.drawable.status_disconnected)
            savedDevicesSection.visibility = View.VISIBLE
            disconnectButton.visibility = View.GONE
            hasPromptedSave = false
            refreshSavedDevicesUI()
        }
    }

    // ============================================================
    // Settings
    // ============================================================

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val currentTheme = prefs.getInt(PREF_THEME_MODE, 0)

        val enterLabel = if (hidService.useShiftEnter) "Shift+Enter (line break)" else "Enter (submit)"

        val items = mutableListOf<String>()
        items.add("🎨  Theme: ${when(currentTheme) { 0 -> "Light ☀️"; 1 -> "Dark 🌙"; else -> "System 🔄" }}")
        items.add("↵  Enter key: $enterLabel")
        if (hidService.isConnected) items.add("⚡  Disconnect from PC")
        if (!hidService.isRegistered) items.add("🔁  Retry HID Registration")
        items.add("🔄  Re-initialize Bluetooth")
        items.add("🔍  Show Diagnostics")
        items.add("📋  View Logs")

        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("⚙️  Settings")
            .setItems(items.toTypedArray()) { _, which ->
                var idx = 1
                when (which) {
                    0 -> showThemeSelector()
                    else -> {
                        if (which == idx) { showEnterKeySelector(); return@setItems }
                        idx++
                        if (hidService.isConnected && which == idx) { confirmDisconnect(); return@setItems }
                        if (hidService.isConnected) idx++
                        if (!hidService.isRegistered && which == idx) {
                            hidService.retryRegistration()
                            Toast.makeText(this, "Retrying HID registration...", Toast.LENGTH_SHORT).show()
                            return@setItems
                        }
                        if (!hidService.isRegistered) idx++
                        if (which == idx) { reinitializeBluetooth(); return@setItems }
                        idx++
                        if (which == idx) { showDiagnostics(); return@setItems }
                        idx++
                        if (which == idx) { showLogs(); return@setItems }
                    }
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

    private fun showEnterKeySelector() {
        val currentChoice = if (hidService.useShiftEnter) 1 else 0
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("Enter Key Behavior")
            .setSingleChoiceItems(
                arrayOf(
                    "↵  Enter (submit / confirm)",
                    "⇧↵  Shift+Enter (line break)"
                ),
                currentChoice
            ) { dialog, which ->
                val useShift = (which == 1)
                hidService.useShiftEnter = useShift
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putBoolean(PREF_SHIFT_ENTER, useShift).apply()
                Toast.makeText(
                    this,
                    if (useShift) "Enter → Shift+Enter (line break)" else "Enter → Enter (submit)",
                    Toast.LENGTH_SHORT
                ).show()
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

    private fun showLogs() {
        val logText = hidService.getLogText()

        val scrollView = ScrollView(this)
        val tv = TextView(this).apply {
            text = logText
            setPadding(48, 32, 48, 32)
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        scrollView.addView(tv)
        // Scroll to bottom
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }

        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("📋  Connection Logs")
            .setView(scrollView)
            .setPositiveButton("OK", null)
            .setNeutralButton("Copy All") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("TapToType Logs", logText))
                Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Clear Logs") { _, _ ->
                hidService.clearLog()
                Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
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
                description = "⚠️ IMPORTANT: The app must be open during pairing!\n\n" +
                        "On your Windows PC:\n" +
                        "1. Remove \"Pixel 10\" if already paired\n" +
                        "2. Go to Settings → Bluetooth & Devices\n" +
                        "3. Add device → Bluetooth\n" +
                        "4. Select your phone and accept on BOTH devices",
                hint = "If your phone shows under \"Other devices\" on the PC instead of " +
                        "\"Mouse, keyboard, & pen\", you need to REMOVE it and re-pair " +
                        "while this app is open.\n\n" +
                        "The phone must advertise as a keyboard during pairing!",
                canAutoDetect = true,
                detectCheck = { hidService.getPairedDevices().isNotEmpty() },
                detectLabel = { if (it) "✅ Paired device(s) found" else "⏳ Waiting — pair from your PC" },
                actionLabel = "Open Bluetooth Settings (to unpair)",
                actionCallback = {
                    try {
                        val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this, "Open Settings → Bluetooth manually", Toast.LENGTH_SHORT).show()
                    }
                }
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
        refreshSavedDevicesUI()
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

    // ============================================================
    // Saved Devices
    // ============================================================

    private fun getSavedDevices(): MutableList<SavedDevice> {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val json = prefs.getString(PREF_SAVED_DEVICES, "[]") ?: "[]"
        val list = mutableListOf<SavedDevice>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(SavedDevice(
                    name = obj.getString("name"),
                    macAddress = obj.getString("mac"),
                    btName = obj.optString("btName", "")
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading saved devices: ${e.message}")
        }
        return list
    }

    private fun saveSavedDevices(devices: List<SavedDevice>) {
        val arr = JSONArray()
        for (d in devices) {
            arr.put(JSONObject().apply {
                put("name", d.name)
                put("mac", d.macAddress)
                put("btName", d.btName)
            })
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit().putString(PREF_SAVED_DEVICES, arr.toString()).apply()
    }

    private fun isDeviceSaved(macAddress: String): Boolean {
        return getSavedDevices().any { it.macAddress == macAddress }
    }

    @SuppressLint("MissingPermission")
    private fun promptSaveDevice() {
        val device = hidService.getConnectedDevice() ?: return
        val mac = device.address
        val btName = device.name ?: "Unknown"

        // Already saved? Don't prompt again
        if (isDeviceSaved(mac)) return

        val input = EditText(this).apply {
            hint = "e.g. My Desktop, Work PC"
            setText(btName)
            selectAll()
            setPadding(60, 40, 60, 40)
            textSize = 16f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }

        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("✅ Connected! Save this device?")
            .setMessage("Give this PC a name for quick reconnecting:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val customName = input.text.toString().trim().ifEmpty { btName }
                val devices = getSavedDevices()
                devices.add(SavedDevice(customName, mac, btName))
                saveSavedDevices(devices)
                Toast.makeText(this, "Saved \"$customName\" ✅", Toast.LENGTH_SHORT).show()
                refreshSavedDevicesUI()
            }
            .setNegativeButton("Skip", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun refreshSavedDevicesUI() {
        savedDevicesContainer.removeAllViews()
        val saved = getSavedDevices()

        if (saved.isEmpty()) {
            // Show a hint when no devices saved
            val hint = TextView(this).apply {
                text = "No saved devices yet.\nTap \"Set Up New Device\" to pair your PC."
                textSize = 14f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                setTextColor(resources.getColor(R.color.text_secondary, theme))
                gravity = android.view.Gravity.CENTER
                setPadding(0, 24, 0, 24)
            }
            savedDevicesContainer.addView(hint)
            return
        }

        for (device in saved) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.card_background)
                setPadding(40, 32, 40, 32)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.bottomMargin = 16
                layoutParams = params
            }

            // Device info column
            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameView = TextView(this).apply {
                text = "💻  ${device.name}"
                textSize = 16f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(resources.getColor(R.color.text_primary, theme))
            }

            val addrView = TextView(this).apply {
                text = device.macAddress
                textSize = 12f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                setTextColor(resources.getColor(R.color.text_tertiary, theme))
            }

            infoCol.addView(nameView)
            infoCol.addView(addrView)

            // Connect button
            val connectBtn = Button(this).apply {
                text = "Connect"
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setBackgroundResource(R.drawable.button_primary_bg)
                setTextColor(android.graphics.Color.WHITE)
                setPadding(32, 16, 32, 16)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.marginStart = 16
                layoutParams = params
            }

            connectBtn.setOnClickListener {
                connectToSavedDevice(device)
            }

            // Long-press for rename/remove
            card.setOnLongClickListener {
                showSavedDeviceOptions(device)
                true
            }

            card.addView(infoCol)
            card.addView(connectBtn)
            savedDevicesContainer.addView(card)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToSavedDevice(device: SavedDevice) {
        // Find the paired BT device by MAC
        val btDevice = hidService.getPairedDevices().find { it.address == device.macAddress }
        if (btDevice == null) {
            AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("⚠️ Device Not Found")
                .setMessage("\"${device.name}\" (${device.macAddress}) is not currently paired.\n\n" +
                        "You may need to re-pair it from your PC's Bluetooth settings.")
                .setPositiveButton("Open Setup Wizard") { _, _ -> showSetupWizard() }
                .setNeutralButton("Remove from Saved") { _, _ ->
                    val devices = getSavedDevices()
                    devices.removeAll { it.macAddress == device.macAddress }
                    saveSavedDevices(devices)
                    refreshSavedDevicesUI()
                    Toast.makeText(this, "Removed \"${device.name}\"", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        statusText.text = "Connecting to ${device.name}..."
        deviceListLabel.text = "Attempting connection..."

        // Ensure HID is registered first
        if (!hidService.isRegistered) {
            Toast.makeText(this, "Initializing HID keyboard...", Toast.LENGTH_SHORT).show()
            if (!hidService.isRegistered) {
                hidService.initialize()
            }
        }

        val result = hidService.connectToDevice(btDevice)
        if (!result.accepted) {
            showErrorDialog("Connection Failed", result.message)
        } else {
            Toast.makeText(this, "Connecting to ${device.name}...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSavedDeviceOptions(device: SavedDevice) {
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle(device.name)
            .setItems(arrayOf("✏️ Rename", "🗑️ Remove")) { _, which ->
                when (which) {
                    0 -> renameSavedDevice(device)
                    1 -> removeSavedDevice(device)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renameSavedDevice(device: SavedDevice) {
        val input = EditText(this).apply {
            setText(device.name)
            selectAll()
            setPadding(60, 40, 60, 40)
            textSize = 16f
        }

        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("Rename Device")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim().ifEmpty { device.name }
                val devices = getSavedDevices()
                val idx = devices.indexOfFirst { it.macAddress == device.macAddress }
                if (idx >= 0) {
                    devices[idx] = device.copy(name = newName)
                    saveSavedDevices(devices)
                    refreshSavedDevicesUI()
                    Toast.makeText(this, "Renamed to \"$newName\"", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeSavedDevice(device: SavedDevice) {
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("Remove \"${device.name}\"?")
            .setMessage("This only removes it from your saved list. The Bluetooth pairing is not affected.")
            .setPositiveButton("Remove") { _, _ ->
                val devices = getSavedDevices()
                devices.removeAll { it.macAddress == device.macAddress }
                saveSavedDevices(devices)
                refreshSavedDevicesUI()
                Toast.makeText(this, "Removed \"${device.name}\"", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
