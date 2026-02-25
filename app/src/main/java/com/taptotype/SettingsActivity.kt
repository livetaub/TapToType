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
            0 -> "Live Typing"
            else -> "Type & Send"
        }

        // Enter key
        val useShiftEnter = prefs.getBoolean(PREF_SHIFT_ENTER, true)
        findViewById<TextView>(R.id.settingEnterValue).text =
            if (useShiftEnter) "Line break (Shift+Enter)" else "Submit (Enter)"
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
            .setTitle("Default mode")
            .setSingleChoiceItems(arrayOf("Live Typing", "Type & Send"), current) { dialog, which ->
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

        val categories = arrayOf(
            "🐛  Bug report",
            "💡  Feature request",
            "💬  Feedback",
            "📎  Other"
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, categories)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        categorySpinner.adapter = adapter

        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("Contact Support")
            .setView(view)
            .setPositiveButton("Send") { _, _ ->
                val name = nameField.text.toString().trim()
                val email = emailField.text.toString().trim()
                val category = categorySpinner.selectedItem.toString()
                    .replace(Regex("^[^a-zA-Z]+"), "").trim()
                val message = messageField.text.toString().trim()

                if (name.isEmpty() || email.isEmpty() || message.isEmpty()) {
                    Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                Toast.makeText(this, "Sending…", Toast.LENGTH_SHORT).show()
                sendGoogleForm(name, email, category, message)
            }
            .setNegativeButton("Cancel", null)
            .show()
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
