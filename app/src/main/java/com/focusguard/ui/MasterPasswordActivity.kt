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
import androidx.compose.ui.Modifier
import com.focusguard.ui.compose.screens.DeactivationCredentialDialog
import com.focusguard.ui.compose.theme.FocusGuardTheme

/**
 * Creates or changes the master password used by "Remove all blocks".
 *
 * It is deliberately independent from target credentials. Creating an app/site
 * PASSWORD block never opens this Activity, and this credential is never offered
 * on a blocked-target screen.
 */
class MasterPasswordActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FocusGuardTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
                ) {
                    DeactivationCredentialDialog(
                        // The master credential is itself the authenticated escape
                        // mechanism for the explicit remove-all action. It must be
                        // possible to configure it even if a block already exists.
                        managementLocked = false,
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
