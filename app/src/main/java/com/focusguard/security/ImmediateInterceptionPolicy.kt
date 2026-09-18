package com.focusguard.security

import java.text.Normalizer
import java.util.Locale

/**
 * Allocation-light decisions used before Accessibility touches a node tree.
 *
 * App-launch blocking may still be decided immediately from an exact launcher
 * identity. System-settings self-protection is intentionally different: an
 * immediate classifier never blocks a Settings/installer click on its own,
 * because doing so would arm the service-wide transition window before the
 * FocusGuard target had been validated by the full policy.
 */
object ImmediateInterceptionPolicy {

    data class LauncherLabelEntry(
        val label: CharSequence?,
        val packageName: String,
        val componentName: String
    )

    internal data class LauncherIdentity(
        val packageName: String,
        val componentNames: Set<String>
    )

    class LauncherLabelIndex internal constructor(
        private val uniqueIdentityByLabel: Map<String, LauncherIdentity>,
        private val allKnownLabels: Set<String>
    ) {
        fun matchBlockedPackage(
            values: Iterable<CharSequence?>,
            blockedPackages: Set<String>,
            additionalBlockedPackages: Set<String> = emptySet()
        ): String? {
            if ((blockedPackages.isEmpty() && additionalBlockedPackages.isEmpty()) ||
                uniqueIdentityByLabel.isEmpty()
            ) return null
            for (value in values) {
                val raw = value?.toString().orEmpty().trim()
                val rendered = normalize(raw)
                if (rendered.isBlank()) continue
                if (rendered in allKnownLabels) {
                    val exact = uniqueIdentityByLabel[rendered]?.packageName
                    return exact?.takeIf {
                        (it in blockedPackages || it in additionalBlockedPackages) &&
                            !PasswordTargetAccessGrant.isPackageGranted(it)
                    }
                }

                val lastDigitIndex = raw.indexOfLast(Char::isDigit)
                for (boundary in raw.indices.reversed()) {
                    if (raw[boundary] !in SAFE_LABEL_SUFFIXES || boundary <= 0) continue
                    if (lastDigitIndex <= boundary) continue
                    var suffixStart = boundary + 1
                    while (suffixStart < raw.length && raw[suffixStart].isWhitespace()) {
                        suffixStart += 1
                    }
                    if (suffixStart >= raw.length || !raw[suffixStart].isDigit()) continue
                    val candidate = normalize(raw.substring(0, boundary))
                    if (candidate in allKnownLabels) {
                        val packageName = uniqueIdentityByLabel[candidate]?.packageName
                        return packageName?.takeIf {
                            (it in blockedPackages || it in additionalBlockedPackages) &&
                                !PasswordTargetAccessGrant.isPackageGranted(it)
                        }
                    }
                }
            }
            return null
        }

        internal fun packageForExactLabel(label: String): String? =
            uniqueIdentityByLabel[normalize(label)]?.packageName

        internal val size: Int
            get() = uniqueIdentityByLabel.size
    }

    enum class DirectDecision { PROTECT, NEED_TREE, IGNORE }

    enum class SettingsSurface { APP_INFO, DEVICE_ADMIN, ACCESSIBILITY, UNINSTALL }

    data class SettingsClickDecision(
        val decision: DirectDecision,
        val surface: SettingsSurface? = null
    )

    fun buildLauncherLabelIndex(entries: Iterable<LauncherLabelEntry>): LauncherLabelIndex {
        val componentsByPackageByLabel =
            linkedMapOf<String, MutableMap<String, MutableSet<String>>>()
        entries.forEach { entry ->
            if (entry.packageName.isBlank() || entry.componentName.isBlank()) return@forEach
            val normalized = normalize(entry.label?.toString().orEmpty())
            if (normalized.isBlank()) return@forEach
            componentsByPackageByLabel
                .getOrPut(normalized) { linkedMapOf() }
                .getOrPut(entry.packageName) { linkedSetOf() }
                .add(entry.componentName)
        }
        val globallyUnique = componentsByPackageByLabel.mapNotNull { (label, packages) ->
            packages.entries.singleOrNull()?.let { (packageName, components) ->
                label to LauncherIdentity(packageName, components.toSet())
            }
        }.toMap()
        return LauncherLabelIndex(
            uniqueIdentityByLabel = globallyUnique,
            allKnownLabels = componentsByPackageByLabel.keys.toSet()
        )
    }

    fun shouldHandleLauncherClick(
        isViewClickedEvent: Boolean,
        eventPackageName: String,
        defaultLauncherPackage: String?
    ): Boolean = isViewClickedEvent &&
        !defaultLauncherPackage.isNullOrBlank() &&
        eventPackageName == defaultLauncherPackage

    fun isLikelyLauncherAppIconClass(className: String): Boolean {
        if (className.isBlank()) return false
        if (className == "android.widget.TextView") return true
        if (NON_APP_LAUNCHER_CLASS_MARKERS.any {
                className.contains(it, ignoreCase = true)
            }
        ) return false
        return APP_ICON_CLASS_MARKERS.any { className.contains(it, ignoreCase = true) }
    }

    fun isBlockedTargetWindow(
        foregroundPackageName: String,
        blockedPackages: Set<String>
    ): Boolean = foregroundPackageName.isNotBlank() &&
        foregroundPackageName in blockedPackages &&
        !PasswordTargetAccessGrant.isPackageGranted(foregroundPackageName) &&
        !PasswordTargetAccessGrant.shouldSuppressPostExitWindow(foregroundPackageName)

    fun classifySettingsClick(
        packageName: String,
        className: String,
        values: Iterable<CharSequence?>
    ): SettingsClickDecision {
        if (packageName !in SettingsInterceptionPolicy.interceptionPackages) {
            return SettingsClickDecision(DirectDecision.IGNORE)
        }

        val accessibility = AccessibilitySettingsPolicy.classifyText(values)
        val managed = ManagedSelfProtectionPolicy.classifyText(values)
        val classLooksLikeDeviceAdmin =
            ManagedSelfProtectionPolicy.classLooksLikeDeviceAdminSurface(className)
        val classTargetsAccessibilityToggle =
            AccessibilitySettingsPolicy.classTargetsAccessibilityServiceToggle(className)
        val classTargetsAccessibilityList =
            AccessibilitySettingsPolicy.classTargetsAccessibilityList(className)
        val classTargetsAppDetails =
            ManagedSelfProtectionPolicy.classTargetsAppDetails(className)
        val classTargetsUninstall =
            ManagedSelfProtectionPolicy.classTargetsUninstall(className)
        val classTargetsEssentialSpecialAccess =
            ManagedSelfProtectionPolicy.classTargetsEssentialSpecialAccess(className)
        val isGenericSubSettings = className.contains("SubSettings", ignoreCase = true)

        if (packageName in SettingsInterceptionPolicy.systemUiPackages) {
            return when {
                managed.focusGuard &&
                    (accessibility.accessibilityDisclosure || managed.deviceAdmin) ->
                    SettingsClickDecision(DirectDecision.NEED_TREE)
                managed.focusGuard || accessibility.accessibilityDisclosure || managed.deviceAdmin ->
                    SettingsClickDecision(DirectDecision.NEED_TREE)
                values.all { it?.toString().isNullOrBlank() } ->
                    SettingsClickDecision(DirectDecision.NEED_TREE)
                else -> SettingsClickDecision(DirectDecision.IGNORE)
            }
        }

        // Even when the direct event already names FocusGuard, defer to the full
        // policy so the service does not arm its package-wide transition guard.
        if (managed.focusGuard) {
            val surface = when {
                managed.appInfoGateway || classTargetsAppDetails -> SettingsSurface.APP_INFO
                classTargetsUninstall || managed.destructiveControl -> SettingsSurface.UNINSTALL
                classLooksLikeDeviceAdmin || managed.deviceAdmin -> SettingsSurface.DEVICE_ADMIN
                classTargetsAccessibilityToggle ||
                    classTargetsAccessibilityList ||
                    accessibility.accessibility ||
                    accessibility.accessibilityDisclosure -> SettingsSurface.ACCESSIBILITY
                else -> SettingsSurface.APP_INFO
            }
            return SettingsClickDecision(DirectDecision.NEED_TREE, surface)
        }

        // Generic management gateways must remain available. NEED_TREE means
        // "let the target-aware full policy inspect this event", not "block it".
        if (classLooksLikeDeviceAdmin ||
            managed.deviceAdmin ||
            classTargetsAccessibilityToggle ||
            classTargetsAccessibilityList ||
            classTargetsAppDetails ||
            classTargetsUninstall ||
            classTargetsEssentialSpecialAccess ||
            isGenericSubSettings ||
            packageName in SettingsInterceptionPolicy.packageInstallerPackages ||
            accessibility.installedAccessibilityApps ||
            accessibility.accessibility ||
            accessibility.accessibilityDisclosure ||
            managed.destructiveControl ||
            managed.appInfoGateway
        ) {
            return SettingsClickDecision(DirectDecision.NEED_TREE)
        }

        return if (values.all { it?.toString().isNullOrBlank() }) {
            SettingsClickDecision(DirectDecision.NEED_TREE)
        } else {
            SettingsClickDecision(DirectDecision.IGNORE)
        }
    }

    /**
     * Launcher App Info is no longer blocked on the launcher click itself. The
     * destination App Info event is handled by the target-aware full policy.
     */
    fun classifyLauncherAppInfoClick(
        values: Iterable<CharSequence?>
    ): DirectDecision {
        val managed = ManagedSelfProtectionPolicy.classifyText(values)
        return when {
            managed.appInfoGateway -> DirectDecision.NEED_TREE
            else -> DirectDecision.IGNORE
        }
    }

    fun classifySystemUiClickWithContext(
        className: String,
        directValues: Iterable<CharSequence?>,
        contextualValues: () -> Iterable<CharSequence?>
    ): SettingsClickDecision {
        val directSnapshot = directValues.toList()
        val direct = classifySettingsClick(
            packageName = SYSTEM_UI_PACKAGE,
            className = className,
            values = directSnapshot
        )
        if (direct.decision != DirectDecision.NEED_TREE) return direct

        return classifySettingsClick(
            packageName = SYSTEM_UI_PACKAGE,
            className = className,
            values = buildList {
                addAll(directSnapshot)
                addAll(contextualValues())
            }
        )
    }

    fun requiresFullPolicyForAuthorizedAdmin(
        deviceAdminActivationAuthorized: Boolean,
        className: String,
        directSurface: SettingsSurface?
    ): Boolean = deviceAdminActivationAuthorized &&
        (directSurface == SettingsSurface.DEVICE_ADMIN ||
            ManagedSelfProtectionPolicy.classLooksLikeDeviceAdminSurface(className) ||
            className.contains("SubSettings", ignoreCase = true))

    private fun normalize(value: String): String {
        if (value.isBlank()) return ""
        val lowercase = value.trim().lowercase(Locale.ROOT)
        val withoutMarks = if (lowercase.all { it.code < 128 }) {
            lowercase
        } else {
            Normalizer.normalize(lowercase, Normalizer.Form.NFD)
                .replace(COMBINING_MARKS_REGEX, "")
        }
        return withoutMarks.replace(WHITESPACE_REGEX, " ")
    }

    private val COMBINING_MARKS_REGEX = "\\p{M}+".toRegex()
    private val WHITESPACE_REGEX = "\\s+".toRegex()
    private val SAFE_LABEL_SUFFIXES = setOf(',', '\n', '•', '·', '(')
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    private val APP_ICON_CLASS_MARKERS = setOf(
        "BubbleTextView",
        "IconTextView",
        "AppIcon",
        "IconView",
        "PagedViewIcon"
    )
    private val NON_APP_LAUNCHER_CLASS_MARKERS = setOf(
        "Folder",
        "Widget",
        "Shortcut",
        "DeepShortcut"
    )
}
