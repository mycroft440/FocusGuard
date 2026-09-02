package com.focusguard.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.core.view.doOnPreDraw
import com.focusguard.R
import com.focusguard.security.AuthenticatedRemovalWindow
import com.focusguard.security.CurtainDestinationReadyCoordinator
import com.focusguard.security.ProtectedSettingsResetWindow
import com.focusguard.security.SafeSurfaceReadinessPolicy
import com.focusguard.security.SelfProtectionStateStore
import com.focusguard.service.BlockingAccessibilityService

/**
 * Gate between active self-protection and Android-owned removal/settings surfaces.
 *
 * There is intentionally no credential field here. The master password is accepted
 * only by RemoveAllBlocksActivity. While protection is active these system surfaces
 * stay closed; after all blocks are removed they are available without a password.
 */
class MasterRemovalActivity : ComponentActivity() {

    enum class Target { APP_INFO, DEVICE_ADMIN, ACCESSIBILITY, UNINSTALL }

    private var dialogShown = false
    private var dialogDrawn = false
    private var activityResumed = false
    private var pendingCurtainGeneration = 0L
    private var freshFrameGeneration = 0L
    private var protectionDialog: AlertDialog? = null
    private val dialogWindowFocusListener = ViewTreeObserver.OnWindowFocusChangeListener {
        if (it) acknowledgePendingSurfaceIfPresented()
    }

    private val target: Target
        get() = runCatching { Target.valueOf(intent.getStringExtra(EXTRA_TARGET).orEmpty()) }
            .getOrDefault(Target.APP_INFO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) { cancelAttempt() }
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
        acknowledgePendingSurfaceIfPresented()
    }

    override fun onPause() {
        activityResumed = false
        super.onPause()
    }

    override fun onDestroy() {
        protectionDialog?.window?.decorView?.viewTreeObserver?.let { observer ->
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
            notifyWhenSurfaceIsDrawn(generation)
            return
        }

        if (shouldResetSettingsTaskBeforeCredential(target) &&
            !sourceIntent.getBooleanExtra(EXTRA_SETTINGS_TASK_RESET_DONE, false)
        ) {
            resetProtectedSettingsTaskAndReturnToGate(generation)
            return
        }

        if (SelfProtectionStateStore.isArmed(applicationContext)) {
            showProtectionDialog(generation)
        } else {
            openAuthorizedSurface()
        }
    }

    private fun resetProtectedSettingsTaskAndReturnToGate(curtainGeneration: Long) {
        ProtectedSettingsResetWindow.open(curtainGeneration)
        runCatching {
            startActivity(createSettingsTaskResetIntent())
            startActivity(createGateReturnIntent(this, target, curtainGeneration))
        }.onFailure {
            ProtectedSettingsResetWindow.close(curtainGeneration)
            if (SelfProtectionStateStore.isArmed(applicationContext)) {
                showProtectionDialog(curtainGeneration)
            } else {
                openAuthorizedSurface()
            }
        }
    }

    private fun showProtectionDialog(curtainGeneration: Long) {
        if (isFinishing || isDestroyed || dialogShown) {
            notifyWhenSurfaceIsDrawn(curtainGeneration)
            return
        }
        dialogShown = true
        val message = if (target == Target.UNINSTALL) {
            R.string.protected_uninstall_blocked_description
        } else {
            R.string.protected_settings_blocked_description
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.protected_settings_blocked_title)
            .setMessage(message)
            .setPositiveButton(R.string.action_ok) { _, _ -> cancelAttempt() }
            .setOnCancelListener { cancelAttempt() }
            .create()
        protectionDialog = dialog
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener {
            dialog.window?.decorView?.viewTreeObserver
                ?.addOnWindowFocusChangeListener(dialogWindowFocusListener)
            notifyWhenSurfaceIsDrawn(curtainGeneration)
        }
        dialog.show()
    }

    private fun notifyWhenSurfaceIsDrawn(curtainGeneration: Long) {
        if (curtainGeneration <= 0L) return
        pendingCurtainGeneration = curtainGeneration
        freshFrameGeneration = 0L
        if (acknowledgePendingSurfaceIfPresented()) return
        val decor = protectionDialog?.window?.decorView ?: return
        decor.doOnPreDraw {
            dialogDrawn = true
            if (pendingCurtainGeneration == curtainGeneration &&
                activityResumed && decor.isShown
            ) {
                freshFrameGeneration = curtainGeneration
            }
            acknowledgePendingSurfaceIfPresented()
        }
        decor.invalidate()
    }

    private fun acknowledgePendingSurfaceIfPresented(): Boolean {
        val generation = pendingCurtainGeneration
        if (generation <= 0L) return false
        val decor = protectionDialog?.window?.decorView ?: return false
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
        ProtectedSettingsResetWindow.close(generation)
        CurtainDestinationReadyCoordinator.notifyReady(generation)
        return true
    }

    private fun cancelAttempt() {
        ProtectedSettingsResetWindow.close(pendingCurtainGeneration)
        runCatching { startActivity(createHomeIntent()) }
        finish()
    }

    private fun openAuthorizedSurface() {
        ProtectedSettingsResetWindow.close(pendingCurtainGeneration)
        AuthenticatedRemovalWindow.open(applicationContext)
        if (!openRequestedAndroidSurface()) {
            AuthenticatedRemovalWindow.close(applicationContext)
            cancelAttempt()
            return
        }
        finish()
    }

    private fun openRequestedAndroidSurface(): Boolean = runCatching {
        when (target) {
            Target.APP_INFO -> startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
            Target.DEVICE_ADMIN -> startActivity(Intent(Settings.ACTION_DEVICE_SECURITY_SETTINGS))
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
        ): Intent = Intent(context, MasterRemovalActivity::class.java).apply {
            putExtra(EXTRA_TARGET, target.name)
            putExtra(
                BlockingAccessibilityService.EXTRA_CURTAIN_GENERATION,
                curtainGeneration
            )
        }

        /** Kept for the interception tests; the gate no longer asks a credential. */
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
