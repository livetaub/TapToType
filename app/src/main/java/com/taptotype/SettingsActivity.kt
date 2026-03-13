package com.taptotype

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "TapToTypePrefs"
        const val PREF_THEME_MODE = "theme_mode"
        const val PREF_SHIFT_ENTER = "enter_is_shift"
        const val PREF_DEFAULT_MODE = "default_mode" // 0 = live, 1 = type & send
        const val PREF_COMPOSE_SEND_MODE = "compose_send_mode" // 0 = type, 1 = paste
        const val PREF_KEYSTROKE_DELAY = "keystroke_delay_ms" // 0, 5, 10, ... 50
        const val PREF_PASTE_DELAY = "paste_delay_ms" // delay before Ctrl+V in paste mode
    }

    private lateinit var hidService: BluetoothHidService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        hidService = BluetoothHidService.getInstance(this)

        // Back button
        findViewById<android.widget.ImageButton>(R.id.backButton).setOnClickListener {
            finish()
        }

        // Version text
        try {
            val version = packageManager.getPackageInfo(packageName, 0).versionName
            findViewById<TextView>(R.id.settingVersion).text = "Version $version"
        } catch (_: Exception) {}

        refreshValues()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        refreshValues()
    }

    private fun refreshValues() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // Theme
        val themeMode = prefs.getInt(PREF_THEME_MODE, 0)
        findViewById<TextView>(R.id.settingThemeValue).text = when (themeMode) {
            0 -> "Light"
            1 -> "Dark"
            else -> "Follow system"
        }

        // Default mode
        val defaultMode = prefs.getInt(PREF_DEFAULT_MODE, 0)
        findViewById<TextView>(R.id.settingModeValue).text = when (defaultMode) {
            0 -> "Live"
            else -> "Compose"
        }

        // Enter key
        val useShiftEnter = prefs.getBoolean(PREF_SHIFT_ENTER, true)
        findViewById<TextView>(R.id.settingEnterValue).text =
            if (useShiftEnter) "Line break (Shift+Enter)" else "Submit (Enter)"

        // Compose send method
        val composeSendMode = prefs.getInt(PREF_COMPOSE_SEND_MODE, 0)
        findViewById<TextView>(R.id.settingComposeSendValue).text = when (composeSendMode) {
            1 -> "Paste (instant via Ctrl+V)"
            else -> "Type (keystroke by keystroke)"
        }

        // Keystroke delay
        val keystrokeDelay = prefs.getLong(PREF_KEYSTROKE_DELAY, 0L)
        findViewById<TextView>(R.id.settingKeystrokeDelayValue).text = when {
            keystrokeDelay == 0L -> "0 ms (fastest)"
            else -> "$keystrokeDelay ms"
        }

        // Paste delay
        val pasteDelay = prefs.getLong(PREF_PASTE_DELAY, 2000L)
        findViewById<TextView>(R.id.settingPasteDelayValue).text = "${pasteDelay / 1000.0} s"
    }

    private fun setupClickListeners() {
        // Theme
        findViewById<LinearLayout>(R.id.settingTheme).setOnClickListener {
            showThemeSelector()
        }

        // Default mode
        findViewById<LinearLayout>(R.id.settingMode).setOnClickListener {
            showModeSelector()
        }

        // Enter key
        findViewById<LinearLayout>(R.id.settingEnterKey).setOnClickListener {
            showEnterKeySelector()
        }

        // Compose send method
        findViewById<LinearLayout>(R.id.settingComposeSend).setOnClickListener {
            showComposeSendSelector()
        }

        // Keystroke delay
        findViewById<LinearLayout>(R.id.settingKeystrokeDelay).setOnClickListener {
            showKeystrokeDelaySelector()
        }

        // Paste delay
        findViewById<LinearLayout>(R.id.settingPasteDelay).setOnClickListener {
            showPasteDelaySelector()
        }

        // Re-init BT
        findViewById<LinearLayout>(R.id.settingReinitBt).setOnClickListener {
            hidService.cleanup()
            hidService.initialize()
            Toast.makeText(this, "Bluetooth re-initialized", Toast.LENGTH_SHORT).show()
        }

        // Diagnostics
        findViewById<LinearLayout>(R.id.settingDiagnostics).setOnClickListener {
            showDiagnostics()
        }

        // Logs
        findViewById<LinearLayout>(R.id.settingLogs).setOnClickListener {
            showLogs()
        }

        // Contact Support
        findViewById<LinearLayout>(R.id.settingContact).setOnClickListener {
            showContactForm()
        }

        // Share this app
        findViewById<LinearLayout>(R.id.settingShare).setOnClickListener {
            val playStoreUrl = "https://play.google.com/store/apps/details?id=$packageName"
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "TapToType — Bluetooth Keyboard")
                putExtra(Intent.EXTRA_TEXT, "Turn your phone into a Bluetooth keyboard for your PC!\n\n$playStoreUrl")
            }
            startActivity(Intent.createChooser(shareIntent, "Share TapToType"))
        }
    }

    private fun showThemeSelector() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val current = prefs.getInt(PREF_THEME_MODE, 0)
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("Theme")
            .setSingleChoiceItems(arrayOf("Light", "Dark", "Follow system"), current) { dialog, which ->
                prefs.edit().putInt(PREF_THEME_MODE, which).apply()
                val mode = when (which) {
                    0 -> AppCompatDelegate.MODE_NIGHT_NO
                    1 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                AppCompatDelegate.setDefaultNightMode(mode)
                dialog.dismiss()
                refreshValues()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showModeSelector() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val current = prefs.getInt(PREF_DEFAULT_MODE, 0)
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("Default typing mode")
            .setSingleChoiceItems(arrayOf("Live", "Compose"), current) { dialog, which ->
                prefs.edit().putInt(PREF_DEFAULT_MODE, which).apply()
                dialog.dismiss()
                refreshValues()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEnterKeySelector() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val currentChoice = if (prefs.getBoolean(PREF_SHIFT_ENTER, true)) 1 else 0
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("Enter key behavior")
            .setSingleChoiceItems(
                arrayOf("Submit (Enter)", "Line break (Shift+Enter)"),
                currentChoice
            ) { dialog, which ->
                val useShift = (which == 1)
                hidService.useShiftEnter = useShift
                prefs.edit().putBoolean(PREF_SHIFT_ENTER, useShift).apply()
                dialog.dismiss()
                refreshValues()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showComposeSendSelector() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val current = prefs.getInt(PREF_COMPOSE_SEND_MODE, 0)
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("Compose send method")
            .setSingleChoiceItems(
                arrayOf(
                    "Type (keystroke by keystroke)",
                    "Paste (instant via Ctrl+V)"
                ),
                current
            ) { dialog, which ->
                hidService.composeSendMode = which
                prefs.edit().putInt(PREF_COMPOSE_SEND_MODE, which).apply()
                dialog.dismiss()
                refreshValues()

                if (which == 1) {
                    // Show helpful info about paste mode requirements
                    AlertDialog.Builder(this, R.style.DialogTheme)
                        .setTitle("\uD83D\uDCCB  Paste mode")
                        .setMessage(
                            "Paste mode copies the text to your phone's clipboard, then " +
                            "sends Ctrl+V to your PC.\n\n" +
                            "For this to work, you need clipboard sync between your phone and PC. " +
                            "The easiest way:\n\n" +
                            "\u2022 Windows: Use \"Phone Link\" (built into Windows 10/11)\n" +
                            "\u2022 Or enable Cloud Clipboard in Windows Settings → System → Clipboard\n\n" +
                            "Once set up, your message will appear instantly on the PC!"
                        )
                        .setPositiveButton("Got it", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showKeystrokeDelaySelector() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val currentDelay = prefs.getLong(PREF_KEYSTROKE_DELAY, 0L)

        // Options: 0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50
        val delayValues = (0..50 step 5).map { it.toLong() }
        val labels = delayValues.map { ms ->
            when (ms) {
                0L -> "0 ms (fastest)"
                50L -> "50 ms (most reliable)"
                else -> "$ms ms"
            }
        }.toTypedArray()

        val currentIndex = delayValues.indexOf(currentDelay).coerceAtLeast(0)

        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("Keystroke delay")
            .setMessage("Extra delay between keystrokes in Compose → Type mode.\nIncrease if characters are dropped.")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                val newDelay = delayValues[which]
                hidService.keystrokeDelayMs = newDelay
                prefs.edit().putLong(PREF_KEYSTROKE_DELAY, newDelay).apply()
                dialog.dismiss()
                refreshValues()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPasteDelaySelector() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val currentDelay = prefs.getLong(PREF_PASTE_DELAY, 2000L)

        // Options: 500, 1000, 1500, 2000, 2500, 3000, 3500, 4000, 4500, 5000
        val delayValues = (500..5000 step 500).map { it.toLong() }
        val labels = delayValues.map { ms ->
            val sec = ms / 1000.0
            when (ms) {
                2000L -> "$sec s (recommended)"
                else -> "$sec s"
            }
        }.toTypedArray()

        val currentIndex = delayValues.indexOf(currentDelay).coerceAtLeast(0)

        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("Paste delay")
            .setMessage("Wait time for clipboard sync (e.g. Phone Link) before sending Ctrl+V.\nIncrease if wrong text is pasted.")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                val newDelay = delayValues[which]
                hidService.pasteDelayMs = newDelay
                prefs.edit().putLong(PREF_PASTE_DELAY, newDelay).apply()
                dialog.dismiss()
                refreshValues()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDiagnostics() {
        val info = hidService.getDiagnosticInfo()
        val tv = TextView(this).apply {
            text = info
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(48, 32, 48, 32)
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod.getInstance()
        }
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("Diagnostics")
            .setView(tv)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showLogs() {
        val logText = hidService.getLogText()
        val tv = TextView(this).apply {
            text = if (logText.isBlank()) "(No log entries)" else logText
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(32, 24, 32, 24)
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod.getInstance()
        }
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("Bluetooth Log")
            .setView(tv)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear") { _, _ ->
                hidService.clearLog()
                Toast.makeText(this, "Log cleared", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showContactForm() {
        val view = layoutInflater.inflate(R.layout.dialog_contact, null)

        val nameField = view.findViewById<EditText>(R.id.contactName)
        val emailField = view.findViewById<EditText>(R.id.contactEmail)
        val categorySpinner = view.findViewById<Spinner>(R.id.contactCategory)
        val messageField = view.findViewById<EditText>(R.id.contactMessage)

        // First item is a non-selectable hint
        val categories = arrayOf(
            "Select a reason…",
            "🐛 Bug report",
            "💡 Feature request",
            "💬 Feedback",
            "📎 Other"
        )
        val adapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_item, categories
        ) {
            override fun isEnabled(position: Int) = position != 0

            override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val v = super.getDropDownView(position, convertView, parent) as TextView
                if (position == 0) v.setTextColor(resources.getColor(R.color.text_tertiary, theme))
                return v
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        categorySpinner.adapter = adapter
        categorySpinner.setSelection(0)

        val dialog = AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("Contact Support")
            .setView(view)
            .setPositiveButton("Send", null) // null — we override below
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

        // Override the Send button so the dialog doesn't auto-close on error
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = nameField.text.toString().trim()
            val email = emailField.text.toString().trim()
            val message = messageField.text.toString().trim()
            val categoryPos = categorySpinner.selectedItemPosition

            // Validate all fields
            if (name.isEmpty() || email.isEmpty() || message.isEmpty() || categoryPos == 0) {
                Toast.makeText(this, "All fields are required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Validate email format
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                Toast.makeText(this, "Please enter a valid email address", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Send the raw category value (with emoji) so it matches the Google Form dropdown
            val category = categorySpinner.selectedItem.toString()

            Toast.makeText(this, "Sending…", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            sendGoogleForm(name, email, category, message)
        }
    }

    private fun sendGoogleForm(name: String, email: String, category: String, message: String) {
        // ── Google Form setup ──────────────────────────────────────
        // 1. Create a Google Form with 4 questions:
        //    Name (short answer), Email (short answer),
        //    Category (dropdown), Message (paragraph)
        // 2. Click the 3-dot menu → "Get pre-filled link"
        // 3. Fill dummy values, click "Get link", then inspect the URL.
        //    It looks like: https://docs.google.com/forms/d/e/FORM_ID/viewform?entry.111=...&entry.222=...
        // 4. Copy the FORM_ID and each entry.XXXX number below:
        val formId    = "1FAIpQLSf7R9wpteErhrKfBjQRn0getOySw5yiVPr5QLi7AAk6GNVRAA"
        val entryName     = "entry.232503957"
        val entryEmail    = "entry.1928030745"
        val entryCategory = "entry.646669393"
        val entryMessage  = "entry.1200501605"
        // ────────────────────────────────────────────────────────────

        val url = "https://docs.google.com/forms/d/e/$formId/formResponse"

        Thread {
            try {
                val postData = listOf(
                    "$entryName=${java.net.URLEncoder.encode(name, "UTF-8")}",
                    "$entryEmail=${java.net.URLEncoder.encode(email, "UTF-8")}",
                    "$entryCategory=${java.net.URLEncoder.encode(category, "UTF-8")}",
                    "$entryMessage=${java.net.URLEncoder.encode(message, "UTF-8")}"
                ).joinToString("&")

                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.doOutput = true
                conn.instanceFollowRedirects = false

                conn.outputStream.use { it.write(postData.toByteArray()) }

                val code = conn.responseCode
                runOnUiThread {
                    // Google Forms returns 200 or 302 on success
                    if (code in 200..399) {
                        Toast.makeText(this, "Message sent ✅ Thank you!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Failed to send (error $code). Please try again.", Toast.LENGTH_LONG).show()
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Network error — check your connection and try again.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
