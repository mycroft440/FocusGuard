package com.focusguard.admin

import android.annotation.TargetApi
import android.app.admin.DevicePolicyIdentifiers
import android.app.admin.PolicyUpdateReceiver
import android.app.admin.PolicyUpdateResult
import android.app.admin.TargetUser
import android.content.Context
import android.os.Build
import android.os.Bundle
import com.focusguard.security.TimedBlockProtectionController
import com.focusguard.utils.FocusGuardLogger

/**
 * Android 14+ acknowledgement channel for the two package policies owned by protected TIME.
 *
 * Read-back from DevicePolicyManager remains authoritative. These callbacks add a second signal
 * explaining conflicts/failures reported by the policy engine itself.
 */
@TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class FocusGuardPolicyUpdateReceiver : PolicyUpdateReceiver() {

    override fun onPolicySetResult(
        context: Context,
        policyIdentifier: String,
        additionalPolicyParams: Bundle,
        targetUser: TargetUser,
        policyUpdateResult: PolicyUpdateResult
    ) {
        record(
            context = context,
            policyIdentifier = policyIdentifier,
            additionalPolicyParams = additionalPolicyParams,
            result = policyUpdateResult,
            changed = false
        )
    }

    override fun onPolicyChanged(
        context: Context,
        policyIdentifier: String,
        additionalPolicyParams: Bundle,
        targetUser: TargetUser,
        policyUpdateResult: PolicyUpdateResult
    ) {
        record(
            context = context,
            policyIdentifier = policyIdentifier,
            additionalPolicyParams = additionalPolicyParams,
            result = policyUpdateResult,
            changed = true
        )
    }

    private fun record(
        context: Context,
        policyIdentifier: String,
        additionalPolicyParams: Bundle,
        result: PolicyUpdateResult,
        changed: Boolean
    ) {
        if (policyIdentifier != DevicePolicyIdentifiers.PACKAGE_UNINSTALL_BLOCKED_POLICY &&
            policyIdentifier != DevicePolicyIdentifiers.USER_CONTROL_DISABLED_PACKAGES_POLICY
        ) return

        val packageName = additionalPolicyParams.getString(EXTRA_PACKAGE_NAME)
        TimedBlockProtectionController.getInstance(context).recordPolicyUpdate(
            policyIdentifier = policyIdentifier,
            packageName = packageName,
            resultCode = result.resultCode,
            changed = changed
        )
        if (result.resultCode != PolicyUpdateResult.RESULT_POLICY_SET &&
            result.resultCode != PolicyUpdateResult.RESULT_POLICY_CLEARED
        ) {
            FocusGuardLogger.log(
                "TimedBlockPolicy",
                "Android reportou resultado ${result.resultCode} para $policyIdentifier"
            )
        }
    }
}
