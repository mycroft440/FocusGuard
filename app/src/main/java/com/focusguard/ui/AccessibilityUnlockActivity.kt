package com.focusguard.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.focusguard.MainActivity
import com.focusguard.R
import com.focusguard.security.AccessibilityProtectionGate
import com.focusguard.security.AuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Password checkpoint opened automatically when the Accessibility service detects
 * an attempt to enter Android Accessibility settings.
 *
 * It deliberately accepts the same app passwords used by blocked-app unlocks and
 * never creates or stores a separate maintenance credential.
 */
class AccessibilityUnlockActivity : AppCompatActivity() {

    private val authManager by lazy { AuthManager(this) }
    private var activeDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(FrameLayout(this))

        if (AccessibilityProtectionGate.isTemporarilyUnlocked(this)) {
            openAccessibilitySettings()
            return
        }

        lifecycleScope.launch {
            val hasPassword = withContext(Dispatchers.IO) {
                authManager.hasPasswordSet()
            }
            if (isFinishing || isDestroyed) return@launch
            if (hasPassword) {
                showPasswordDialog()
            } else {
                showPasswordRequiredDialog()
            }
        }
    }

    override fun onDestroy() {
        activeDialog?.dismiss()
        activeDialog = null
        super.onDestroy()
    }

    private fun showPasswordDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.accessibility_unlock_password_label)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        val horizontalPadding = (24 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.accessibility_unlock_dialog_title)
            .setMessage(R.string.accessibility_unlock_dialog_description)
            .setView(container)
            .setPositiveButton(R.string.accessibility_unlock_action, null)
            .setNegativeButton(R.string.accessibility_unlock_cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .create()

        dialog.setOnShowListener {
            val confirmButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            confirmButton.setOnClickListener {
                val password = input.text?.toString().orEmpty()
                if (password.isBlank()) {
                    input.error = getString(R.string.accessibility_unlock_password_empty)
                    return@setOnClickListener
                }

                input.error = null
                input.isEnabled = false
                confirmButton.isEnabled = false
                lifecycleScope.launch {
                    val accepted = withContext(Dispatchers.IO) {
                        authManager.verifyPassword(password)
                    }
                    if (isFinishing || isDestroyed) return@launch
                    if (accepted) {
                        AccessibilityProtectionGate.requestTemporaryUnlock(this@AccessibilityUnlockActivity)
                        dialog.dismiss()
                        openAccessibilitySettings()
                    } else {
                        input.isEnabled = true
                        confirmButton.isEnabled = true
                        input.error = getString(R.string.accessibility_unlock_wrong_password)
                        input.selectAll()
                        input.requestFocus()
                    }
                }
            }
            input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }

        activeDialog = dialog
        dialog.show()
    }

    private fun showPasswordRequiredDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.accessibility_unlock_password_required_title)
            .setMessage(R.string.accessibility_unlock_password_required)
            .setPositiveButton(R.string.accessibility_unlock_open_focusguard) { _, _ ->
                startActivity(
                    Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                )
                finish()
            }
            .setNegativeButton(R.string.accessibility_unlock_cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .create()
        activeDialog = dialog
        dialog.show()
    }

    private fun openAccessibilitySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.onFailure {
            Toast.makeText(
                this,
                getString(R.string.accessibility_unlock_open_failed),
                Toast.LENGTH_LONG
            ).show()
        }
        finish()
    }
}
