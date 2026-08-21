package com.focusguard.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.security.DeactivationCredentialManager
import com.focusguard.ui.compose.screens.DeactivationCredentialDialog
import com.focusguard.ui.compose.theme.FocusGuardTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Destino único para criar ou alterar a senha mestra.
 *
 * Configurações, Proteger apps e Limites de uso abrem esta mesma Activity, de
 * modo que nunca existam formulários ou credenciais concorrentes.
 */
@AndroidEntryPoint
class MasterPasswordActivity : ComponentActivity() {
    @Inject lateinit var blockingManager: BlockingSessionManager
    @Inject lateinit var deviceOwnerManager: DeviceOwnerManager
    @Inject lateinit var credentialManager: DeactivationCredentialManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FocusGuardTheme {
                val blockingActive by blockingManager.isBlockingActiveFlow.collectAsStateWithLifecycle(
                    initialValue = true
                )
                val managementLocked = blockingActive ||
                    deviceOwnerManager.isArmoredProtectionArmed()

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
                ) {
                    DeactivationCredentialDialog(
                        manager = credentialManager,
                        managementLocked = managementLocked,
                        onDismiss = { finish() },
                        onCredentialChanged = {
                            setResult(Activity.RESULT_OK)
                        }
                    )
                }
            }
        }
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, MasterPasswordActivity::class.java)
    }
}
