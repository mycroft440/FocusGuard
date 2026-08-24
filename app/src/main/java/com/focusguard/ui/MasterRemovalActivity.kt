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
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import com.focusguard.R
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.focusmode.FocusModeManager
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.security.AuthenticatedRemovalWindow
import com.focusguard.security.CurtainDestinationReadyCoordinator
import com.focusguard.security.DeactivationCredentialManager
import com.focusguard.security.ProtectedSettingsResetWindow
import com.focusguard.security.SafeSurfaceReadinessPolicy
import com.focusguard.service.BlockingAccessibilityService
import kotlinx.coroutines.CancellationException
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
    private var dialogShown = false
    private var dialogDrawn = false
    private var activityResumed = false
    private var pendingCurtainGeneration = 0L
    private var freshFrameGeneration = 0L
    private var credentialDialog: AlertDialog? = null
    private val dialogWindowFocusListener = ViewTreeObserver.OnWindowFocusChangeListener {
        if (it) acknowledgePendingCredentialIfPresented()
    }

    private val target: Target
        get() = runCatching { Target.valueOf(intent.getStringExtra(EXTRA_TARGET).orEmpty()) }
            .getOrDefault(Target.APP_INFO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        credentialManager = DeactivationCredentialManager(applicationContext)
        sessionManager = BlockingSessionManager.getInstance(applicationContext)
        deviceOwnerManager = DeviceOwnerManager.getInstance(applicationContext)
        focusModeManager = FocusModeManager.getInstance(applicationContext)

        onBackPressedDispatcher.addCallback(this) {
            if (!working) cancelRemovalAttempt()
        }

        handleGateIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleGateIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        acknowledgePendingCredentialIfPresented()
    }

    override fun onPause() {
        activityResumed = false
        super.onPause()
    }

    override fun onDestroy() {
        credentialDialog?.window?.decorView?.viewTreeObserver?.let { observer ->
            if (observer.isAlive) {
                observer.removeOnWindowFocusChangeListener(dialogWindowFocusListener)
            }
        }
        super.onDestroy()
    }

    private fun handleGateIntent(sourceIntent: Intent) {
        val generation = curtainGeneration(sourceIntent)
        pendingCurtainGeneration = generation
        freshFrameGeneration = 0L
        if (dialogShown) {
            notifyWhenCredentialIsDrawn(generation)
            return
        }
        if (shouldResetSettingsTaskBeforeCredential(target) &&
            !sourceIntent.getBooleanExtra(EXTRA_SETTINGS_TASK_RESET_DONE, false)
        ) {
            resetProtectedSettingsTaskAndReturnToGate(generation)
        } else {
            showCredentialDialogOnce(generation)
        }
    }

    /**
     * Android does not let a normal app force-stop the system Settings process.
     * The strongest safe equivalent is to clear the Settings task and recreate it
     * at its root, then immediately bring this private credential task back.
     * Cancelling the gate goes to HOME, so the protected deep screen is not left
     * ready in Recents and the next attempt must navigate Settings again.
     */
    private fun resetProtectedSettingsTaskAndReturnToGate(curtainGeneration: Long) {
        ProtectedSettingsResetWindow.open(curtainGeneration)
        runCatching {
            startActivity(createSettingsTaskResetIntent())
            startActivity(createGateReturnIntent(this, target, curtainGeneration))
        }.onFailure {
            ProtectedSettingsResetWindow.close(curtainGeneration)
            // Failing to reset the task must never remove the credential gate.
            showCredentialDialogOnce(curtainGeneration)
        }
    }

    private fun showCredentialDialogOnce(curtainGeneration: Long) {
        if (isFinishing || isDestroyed) return
        if (dialogShown) {
            notifyWhenCredentialIsDrawn(curtainGeneration)
            return
        }
        dialogShown = true
        showCredentialDialog(curtainGeneration)
    }

    private fun showCredentialDialog(curtainGeneration: Long) {
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
            .setNegativeButton(R.string.cancel) { _, _ -> cancelRemovalAttempt() }
            .setPositiveButton(R.string.master_removal_confirm, null)
            .setOnCancelListener { cancelRemovalAttempt() }
            .create()
        credentialDialog = dialog

        dialog.setOnShowListener {
            dialog.window?.decorView?.viewTreeObserver
                ?.addOnWindowFocusChangeListener(dialogWindowFocusListener)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                authorizeAndRelease(dialog)
            }
            passwordField.requestFocus()
            notifyWhenCredentialIsDrawn(curtainGeneration)
        }
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
    }

    private fun notifyWhenCredentialIsDrawn(curtainGeneration: Long) {
        if (curtainGeneration <= 0L) return
        pendingCurtainGeneration = curtainGeneration
        freshFrameGeneration = 0L
        if (acknowledgePendingCredentialIfPresented()) return
        val decor = credentialDialog?.window?.decorView ?: return
        decor.doOnPreDraw {
            dialogDrawn = true
            if (pendingCurtainGeneration == curtainGeneration &&
                activityResumed && decor.isShown
            ) {
                freshFrameGeneration = curtainGeneration
            }
            acknowledgePendingCredentialIfPresented()
        }
        decor.invalidate()
    }

    private fun acknowledgePendingCredentialIfPresented(): Boolean {
        val generation = pendingCurtainGeneration
        if (generation <= 0L) return false
        val decor = credentialDialog?.window?.decorView ?: return false
        val ready = SafeSurfaceReadinessPolicy.decide(
            alreadyDrawn = dialogDrawn,
            freshFrameAfterRequest = freshFrameGeneration == generation,
            lifecycleResumed = activityResumed,
            decorShown = decor.isShown,
            windowFocused = decor.hasWindowFocus()
        ) == SafeSurfaceReadinessPolicy.Decision.ACK_NOW
        if (!ready) return false
        pendingCurtainGeneration = 0L
        freshFrameGeneration = 0L
        notifyCredentialReady(generation)
        return true
    }

    private fun notifyCredentialReady(curtainGeneration: Long) {
        if (curtainGeneration <= 0L) return
        // The internal Settings reset is finished as soon as this safe credential
        // surface is really on screen. Keeping the reset exemption alive for its
        // old 3-second timeout let a second real Settings click pass through.
        ProtectedSettingsResetWindow.close(curtainGeneration)
        CurtainDestinationReadyCoordinator.notifyReady(curtainGeneration)
    }

    private fun cancelRemovalAttempt() {
        // If cancellation races the first drawn credential frame, revoke the reset
        // exemption before HOME so no user click can inherit it.
        ProtectedSettingsResetWindow.close(pendingCurtainGeneration)
        runCatching { startActivity(createHomeIntent()) }
        finish()
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
            } catch (cancelled: CancellationException) {
                throw cancelled
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
        private const val EXTRA_SETTINGS_TASK_RESET_DONE = "SETTINGS_TASK_RESET_DONE"

        fun createIntent(
            context: Context,
            target: Target,
            curtainGeneration: Long = 0L
        ): Intent =
            Intent(context, MasterRemovalActivity::class.java).apply {
                putExtra(EXTRA_TARGET, target.name)
                putExtra(
                    BlockingAccessibilityService.EXTRA_CURTAIN_GENERATION,
                    curtainGeneration
                )
            }

        internal fun shouldResetSettingsTaskBeforeCredential(target: Target): Boolean =
            target == Target.APP_INFO ||
                target == Target.DEVICE_ADMIN ||
                target == Target.ACCESSIBILITY

        internal fun createSettingsTaskResetIntent(): Intent =
            Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }

        internal fun createHomeIntent(): Intent =
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }

        private fun createGateReturnIntent(
            context: Context,
            target: Target,
            curtainGeneration: Long
        ): Intent = createIntent(context, target, curtainGeneration).apply {
                putExtra(EXTRA_SETTINGS_TASK_RESET_DONE, true)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }

        private fun curtainGeneration(intent: Intent): Long = intent.getLongExtra(
            BlockingAccessibilityService.EXTRA_CURTAIN_GENERATION,
            0L
        )
    }
}
