package com.focusguard.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.focusguard.R
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.focusmode.FocusModeManager
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.security.AuthenticatedRemovalWindow
import com.focusguard.security.DeactivationCredentialManager
import com.focusguard.service.BlockingAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MasterRemovalActivity : ComponentActivity() {

    enum class Target { APP_INFO, DEVICE_ADMIN, ACCESSIBILITY, UNINSTALL }

    private lateinit var credentialManager: DeactivationCredentialManager
    private lateinit var sessionManager: BlockingSessionManager
    private lateinit var deviceOwnerManager: DeviceOwnerManager
    private lateinit var focusModeManager: FocusModeManager
    private lateinit var passwordField: EditText
    private lateinit var errorText: TextView
    private var working = false

    private val target: Target by lazy {
        runCatching { Target.valueOf(intent.getStringExtra(EXTRA_TARGET).orEmpty()) }
            .getOrDefault(Target.APP_INFO)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        credentialManager = DeactivationCredentialManager(applicationContext)
        sessionManager = BlockingSessionManager.getInstance(applicationContext)
        deviceOwnerManager = DeviceOwnerManager.getInstance(applicationContext)
        focusModeManager = FocusModeManager.getInstance(applicationContext)
        showCredentialDialog()
    }

    private fun showCredentialDialog() {
        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (6 * density).toInt(), (24 * density).toInt(), 0)
        }
        passwordField = EditText(this).apply {
            hint = getString(R.string.master_removal_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        errorText = TextView(this).apply {
            setTextColor(getColor(android.R.color.holo_red_dark))
            visibility = View.GONE
        }
        container.addView(passwordField)
        container.addView(errorText)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.master_removal_title)
            .setMessage(R.string.master_removal_description)
            .setView(container)
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setPositiveButton(R.string.master_removal_confirm, null)
            .setOnCancelListener { finish() }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                authorizeAndRelease(dialog)
            }
            passwordField.requestFocus()
        }
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
    }

    private fun authorizeAndRelease(dialog: AlertDialog) {
        if (working) return
        val credential = passwordField.text?.toString().orEmpty()
        if (credential.isBlank()) return
        working = true
        passwordField.isEnabled = false
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        errorText.visibility = View.GONE

        lifecycleScope.launch {
            val verified = withContext(Dispatchers.Default) {
                when (credentialManager.verify(credential)) {
                    DeactivationCredentialManager.VerificationResult.PASSWORD_ACCEPTED,
                    DeactivationCredentialManager.VerificationResult.RECOVERY_ACCEPTED -> true
                    DeactivationCredentialManager.VerificationResult.REJECTED -> false
                    DeactivationCredentialManager.VerificationResult.NOT_CONFIGURED -> null
                }
            }
            if (verified == null) {
                showError(getString(R.string.master_credential_not_configured), dialog)
                return@launch
            }
            if (!verified) {
                showError(getString(R.string.master_removal_wrong_password), dialog)
                return@launch
            }

            if (!sessionManager.removeAllBlocksForDevelopmentExit()) {
                showError(getString(R.string.master_removal_release_failed), dialog)
                return@launch
            }

            AuthenticatedRemovalWindow.open(applicationContext)
            if (!deviceOwnerManager.releaseRemovalProtectionForDevelopmentExit()) {
                AuthenticatedRemovalWindow.close(applicationContext)
                showError(getString(R.string.master_removal_release_failed), dialog)
                return@launch
            }

            try {
                // Keep Focus Mode state available until Device Owner has used it to
                // unsuspend packages and clear kiosk policies. Only then erase the
                // persisted session, cancel its alarm/service and refresh its UI.
                focusModeManager.forceStopForDevelopmentExit()
            } catch (error: Exception) {
                AuthenticatedRemovalWindow.close(applicationContext)
                showError(getString(R.string.master_removal_release_failed), dialog)
                return@launch
            }

            sendBroadcast(
                BlockingAccessibilityService.createDevelopmentRelinquishIntent(applicationContext)
            )

            if (!openRequestedAndroidSurface()) {
                showError(getString(R.string.master_removal_open_failed), dialog)
                return@launch
            }
            dialog.dismiss()
            finish()
        }
    }

    private fun showError(message: String, dialog: AlertDialog) {
        working = false
        passwordField.isEnabled = true
        passwordField.text?.clear()
        errorText.text = message
        errorText.visibility = View.VISIBLE
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
        passwordField.requestFocus()
    }

    private fun openRequestedAndroidSurface(): Boolean = runCatching {
        when (target) {
            Target.APP_INFO -> startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
            Target.DEVICE_ADMIN -> {
                if (!deviceOwnerManager.openDeviceAdminSettings(this)) {
                    error("Device Admin settings unavailable")
                }
            }
            Target.ACCESSIBILITY -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Target.UNINSTALL -> startActivity(
                Intent(Intent.ACTION_DELETE).apply { data = Uri.parse("package:$packageName") }
            )
        }
        true
    }.getOrDefault(false)

    companion object {
        private const val EXTRA_TARGET = "MASTER_REMOVAL_TARGET"

        fun createIntent(context: Context, target: Target): Intent =
            Intent(context, MasterRemovalActivity::class.java).apply {
                putExtra(EXTRA_TARGET, target.name)
            }
    }
}
