package com.focusguard.security

import java.util.concurrent.ConcurrentHashMap

/**
 * Ephemeral authorization for opening a target protected by a PASSWORD session.
 *
 * A successful target credential must not delete the block. The grant lives only
 * in this process and is revoked as soon as the protected app leaves foreground;
 * process death also fails closed and requires the target credential again.
 */
object PasswordTargetAccessGrant {
    private val grantedPackages = ConcurrentHashMap.newKeySet<String>()

    fun grantPackage(packageName: String) {
        packageName.takeIf(String::isNotBlank)?.let(grantedPackages::add)
    }

    fun isPackageGranted(packageName: String): Boolean =
        packageName.isNotBlank() && packageName in grantedPackages

    fun revokePackage(packageName: String?) {
        packageName?.takeIf(String::isNotBlank)?.let(grantedPackages::remove)
    }

    fun clear() {
        grantedPackages.clear()
    }
}
