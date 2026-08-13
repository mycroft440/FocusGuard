package com.focusguard.focusmode

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telecom.TelecomManager

/** Discovers launchable apps and the phone/SMS packages that must remain available. */
object FocusModeAppCatalog {
    private val KNOWN_PHONE_PACKAGES = setOf(
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.phone",
        "com.android.server.telecom",
        "com.samsung.android.dialer",
        "com.samsung.android.incallui",
        "com.miui.dialer",
        "com.coloros.dialer",
        "com.oplus.dialer"
    )
    private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

    fun loadLaunchableApps(context: Context): List<FocusModeSelectableApp> {
        val appContext = context.applicationContext
        val pm = appContext.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .asSequence()
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                if (packageName == appContext.packageName) return@mapNotNull null
                FocusModeSelectableApp(
                    packageName = packageName,
                    appName = info.loadLabel(pm).toString().ifBlank { packageName }
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.appName.lowercase() }
            .toList()
    }

    fun mandatoryPackages(context: Context): Set<String> {
        val appContext = context.applicationContext
        val discovered = buildSet {
            val defaultDialer = runCatching {
                appContext.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
            }.getOrNull()
            if (!defaultDialer.isNullOrBlank()) add(defaultDialer)
            val defaultSms = runCatching {
                Telephony.Sms.getDefaultSmsPackage(appContext)
            }.getOrNull()
            if (!defaultSms.isNullOrBlank()) add(defaultSms)
            addAll(KNOWN_PHONE_PACKAGES.filter { isInstalled(appContext, it) })
        }
        return discovered + appContext.packageName
    }

    fun defaultDraftPackages(context: Context, installedPackages: Set<String>): Set<String> =
        WHATSAPP_PACKAGES.intersect(installedPackages)

    fun phoneIntent(): Intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))

    fun smsIntent(): Intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))

    @Suppress("DEPRECATION")
    private fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
    }.isSuccess
}
