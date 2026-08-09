package com.focusguard.security

import com.focusguard.security.SettingsInterceptionPolicy.Decision
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Covers the composition of interception signals — the decision tree that was
 * previously inlined in `BlockingAccessibilityService.handleSettingsInterception`
 * and had no direct test.
 */
class SettingsInterceptionPolicyTest {

    // ---------------------------------------------------------------- helpers

    private fun signals(
        packageName: String = SETTINGS,
        isViewClickedEvent: Boolean = false,
        isWindowTransitionEvent: Boolean = false,
        guardArmed: Boolean = false,
        classTargetsAccessibilityServiceToggle: Boolean = false,
        classTargetsAccessibilityList: Boolean = false,
        classTargetsDeviceAdmin: Boolean = false,
        classTargetsAppDetails: Boolean = false,
        classTargetsUninstall: Boolean = false,
        classTargetsEssentialSpecialAccess: Boolean = false,
        isGenericSubSettings: Boolean = false,
        textMentionsAccessibility: Boolean = false,
        textMentionsInstalledAccessibilityApps: Boolean = false,
        textMentionsDeviceAdmin: Boolean = false,
        textMentionsFocusGuard: Boolean = false,
        textMentionsDestructiveControl: Boolean = false,
        textMentionsEssentialSpecialAccess: Boolean = false
    ) = SettingsInterceptionPolicy.EventSignals(
        packageName = packageName,
        isViewClickedEvent = isViewClickedEvent,
        isWindowTransitionEvent = isWindowTransitionEvent,
        guardArmed = guardArmed,
        classTargetsAccessibilityServiceToggle = classTargetsAccessibilityServiceToggle,
        classTargetsAccessibilityList = classTargetsAccessibilityList,
        classTargetsDeviceAdmin = classTargetsDeviceAdmin,
        classTargetsAppDetails = classTargetsAppDetails,
        classTargetsUninstall = classTargetsUninstall,
        classTargetsEssentialSpecialAccess = classTargetsEssentialSpecialAccess,
        isGenericSubSettings = isGenericSubSettings,
        textMentionsAccessibility = textMentionsAccessibility,
        textMentionsInstalledAccessibilityApps = textMentionsInstalledAccessibilityApps,
        textMentionsDeviceAdmin = textMentionsDeviceAdmin,
        textMentionsFocusGuard = textMentionsFocusGuard,
        textMentionsDestructiveControl = textMentionsDestructiveControl,
        textMentionsEssentialSpecialAccess = textMentionsEssentialSpecialAccess
    )

    /** Records which node-tree reads actually happened, to assert laziness. */
    private class RecordingRoots(
        private val accessibility: Boolean = false,
        private val deviceAdmin: Boolean = false,
        private val focusGuard: Boolean = false,
        private val destructive: Boolean = false,
        private val essential: Boolean = false
    ) {
        val reads = mutableListOf<String>()

        fun asRootSignals() = SettingsInterceptionPolicy.RootSignals(
            mentionsAccessibility = { reads += "accessibility"; accessibility },
            mentionsDeviceAdmin = { reads += "deviceAdmin"; deviceAdmin },
            mentionsFocusGuard = { reads += "focusGuard"; focusGuard },
            mentionsDestructiveControl = { reads += "destructive"; destructive },
            mentionsEssentialSpecialAccess = { reads += "essential"; essential }
        )
    }

    private fun decide(
        signals: SettingsInterceptionPolicy.EventSignals,
        engaged: Boolean = true,
        strictPomodoro: Boolean = false,
        roots: RecordingRoots = RecordingRoots()
    ) = SettingsInterceptionPolicy.decide(
        signals = signals,
        selfProtectionEngaged = engaged,
        strictPomodoroActive = strictPomodoro,
        rootSignals = roots.asRootSignals()
    )

    // ------------------------------------------------------------- gate: scope

    @Test
    fun `ignores packages outside the protected system set`() {
        val decision = decide(
            signals(
                packageName = "com.example.browser",
                classTargetsAccessibilityServiceToggle = true,
                textMentionsFocusGuard = true
            )
        )

        assertThat(decision).isEqualTo(Decision.IGNORE)
    }

    @Test
    fun `every known settings and installer package is in scope`() {
        val inScope = SettingsInterceptionPolicy.protectedSystemPackages

        assertThat(inScope).containsAtLeast(
            "com.android.settings",
            "com.miui.securitycenter",
            "com.huawei.systemmanager",
            "com.samsung.android.sm",
            "com.android.packageinstaller",
            "com.miui.packageinstaller"
        )
    }

    // ---------------------------------------------- gate: self-protection

    @Test
    fun `ignores everything when self-protection is not engaged`() {
        // Nothing is being protected right now, so the app must stay removable —
        // it never traps a user who has no block running.
        val decision = decide(
            signals(
                classTargetsAccessibilityServiceToggle = true,
                textMentionsFocusGuard = true
            ),
            engaged = false
        )

        assertThat(decision).isEqualTo(Decision.IGNORE)
    }

    @Test
    fun `the engagement gate is checked before reading the node tree`() {
        val roots = RecordingRoots(accessibility = true)

        decide(
            signals(isGenericSubSettings = true),
            engaged = false,
            roots = roots
        )

        assertThat(roots.reads).isEmpty()
    }

    @Test
    fun `the three self-removal surfaces are intercepted once protection is engaged`() {
        // 1. FocusGuard's own accessibility toggle.
        assertThat(
            decide(
                signals(
                    classTargetsAccessibilityServiceToggle = true,
                    textMentionsFocusGuard = true
                )
            )
        ).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)

        // 2. The device-admin screen.
        assertThat(
            decide(signals(classTargetsDeviceAdmin = true))
        ).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)

        // 3. FocusGuard's app details / uninstall entry.
        assertThat(
            decide(
                signals(
                    classTargetsAppDetails = true,
                    textMentionsFocusGuard = true
                )
            )
        ).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `those same surfaces stay open when nothing is being protected`() {
        assertThat(
            decide(signals(classTargetsDeviceAdmin = true), engaged = false)
        ).isEqualTo(Decision.IGNORE)
        assertThat(
            decide(
                signals(classTargetsAppDetails = true, textMentionsFocusGuard = true),
                engaged = false
            )
        ).isEqualTo(Decision.IGNORE)
    }

    // ------------------------------------------------------ strict pomodoro

    @Test
    fun `strict pomodoro takes precedence over any settings screen`() {
        val decision = decide(
            signals(
                classTargetsAccessibilityServiceToggle = true,
                textMentionsFocusGuard = true
            ),
            strictPomodoro = true
        )

        assertThat(decision).isEqualTo(Decision.POMODORO_LOCK)
    }

    @Test
    fun `strict pomodoro intercepts even a screen that would otherwise be ignored`() {
        val decision = decide(signals(), strictPomodoro = true)

        assertThat(decision).isEqualTo(Decision.POMODORO_LOCK)
    }

    // ------------------------------------------------------- click intercept

    @Test
    fun `click on the Accessibility menu entry is blocked at the entry point`() {
        // Blocked without requiring the app to be named: this is the one choke
        // point that does not depend on OEM class names. The section as a whole
        // becomes unreachable while a block is armed, by product decision.
        val decision = decide(
            signals(isViewClickedEvent = true, textMentionsAccessibility = true)
        )

        assertThat(decision).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `One UI accessibility screen is blocked on window transition`() {
        val decision = decide(
            signals(
                isWindowTransitionEvent = true,
                textMentionsAccessibility = true
            )
        )

        assertThat(decision).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `One UI installed apps accessibility row is blocked directly`() {
        val decision = decide(
            signals(textMentionsInstalledAccessibilityApps = true)
        )

        assertThat(decision).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `accessibility label in an ordinary content event does not block all Settings`() {
        val decision = decide(
            signals(
                isWindowTransitionEvent = false,
                textMentionsAccessibility = true
            )
        )

        assertThat(decision).isEqualTo(Decision.IGNORE)
    }

    @Test
    fun `click on the FocusGuard accessibility entry arms the transition guard`() {
        val decision = decide(
            signals(
                isViewClickedEvent = true,
                textMentionsAccessibility = true,
                textMentionsFocusGuard = true
            )
        )

        assertThat(decision).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `the narrower toggle rule still guards the destination if the click is missed`() {
        // Defence in depth: if the entry-point click never fires (gesture
        // navigation, deep link from a notification), the destination screen is
        // still intercepted once identified as FocusGuard's.
        val decision = decide(
            signals(
                classTargetsAccessibilityServiceToggle = true,
                textMentionsFocusGuard = true
            )
        )

        assertThat(decision).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `click mentioning device admin arms the transition guard`() {
        val decision = decide(
            signals(isViewClickedEvent = true, textMentionsDeviceAdmin = true)
        )

        assertThat(decision).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `click naming FocusGuard alone is not enough to intercept`() {
        // Seeing the app name on a settings list is not evidence of intent to
        // uninstall or revoke; requiring a second signal avoids false positives.
        val decision = decide(
            signals(isViewClickedEvent = true, textMentionsFocusGuard = true)
        )

        assertThat(decision).isEqualTo(Decision.IGNORE)
    }

    @Test
    fun `click naming FocusGuard plus a destructive control intercepts`() {
        val decision = decide(
            signals(
                isViewClickedEvent = true,
                textMentionsFocusGuard = true,
                textMentionsDestructiveControl = true
            )
        )

        assertThat(decision).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `click naming FocusGuard plus essential special access intercepts`() {
        val decision = decide(
            signals(
                isViewClickedEvent = true,
                textMentionsFocusGuard = true,
                textMentionsEssentialSpecialAccess = true
            )
        )

        assertThat(decision).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    // -------------------------------------------------------- guard window

    @Test
    fun `non-click event inside the guard window protects without re-arming`() {
        // The destination screen opens right after the click; re-arming here would
        // extend the window indefinitely while the user stays on the page.
        val decision = decide(signals(guardArmed = true))

        assertThat(decision).isEqualTo(Decision.PROTECT)
    }

    @Test
    fun `click inside the guard window does not take the guard shortcut`() {
        val decision = decide(
            signals(guardArmed = true, isViewClickedEvent = true)
        )

        assertThat(decision).isEqualTo(Decision.IGNORE)
    }

    @Test
    fun `guard window does not read the node tree`() {
        val roots = RecordingRoots(accessibility = true)

        decide(signals(guardArmed = true), roots = roots)

        assertThat(roots.reads).isEmpty()
    }

    // ------------------------------------------------------- direct screens

    @Test
    fun `FocusGuard service toggle screen is intercepted`() {
        val decision = decide(
            signals(
                classTargetsAccessibilityServiceToggle = true,
                textMentionsFocusGuard = true
            )
        )

        assertThat(decision).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `the whole accessibility section is closed while protection is engaged`() {
        // Antes só o interruptor do próprio FocusGuard era interceptado, e a
        // seção seguia navegável — o que deixava chegar até ele. Agora a lista
        // também sai, ao custo de TalkBack e tamanho de fonte ficarem fora do
        // alcance enquanto há bloqueio. Decisão de produto, pedida explicitamente.
        assertThat(
            decide(signals(classTargetsAccessibilityList = true))
        ).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)

        assertThat(
            decide(signals(classTargetsAccessibilityServiceToggle = true))
        ).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `accessibility and device admin decide without touching the node tree`() {
        // Velocidade é o requisito destas duas telas: elas desligam a proteção
        // em um toque, e ler a árvore custa binder síncrono. A decisão sai da
        // classe da tela, sem nenhuma leitura.
        listOf(
            signals(classTargetsAccessibilityList = true),
            signals(classTargetsAccessibilityServiceToggle = true),
            signals(classTargetsDeviceAdmin = true)
        ).forEach { screen ->
            val roots = RecordingRoots(focusGuard = true)

            assertThat(decide(screen, roots = roots))
                .isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
            assertThat(roots.reads).isEmpty()
        }
    }

    @Test
    fun `the accessibility section reopens once nothing is being protected`() {
        assertThat(
            decide(signals(classTargetsAccessibilityList = true), engaged = false)
        ).isEqualTo(Decision.IGNORE)
    }

    @Test
    fun `direct device admin screen intercepts on class name alone`() {
        val decision = decide(signals(classTargetsDeviceAdmin = true))

        assertThat(decision).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    // -------------------------------------------------- generic SubSettings

    @Test
    fun `generic SubSettings intercepts when event text mentions device admin`() {
        val decision = decide(
            signals(isGenericSubSettings = true, textMentionsDeviceAdmin = true)
        )

        assertThat(decision).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `generic SubSettings falls back to the node tree for device admin`() {
        val roots = RecordingRoots(deviceAdmin = true)

        val decision = decide(signals(isGenericSubSettings = true), roots = roots)

        assertThat(decision).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
        assertThat(roots.reads).contains("deviceAdmin")
    }

    @Test
    fun `generic SubSettings needs accessibility and FocusGuard together`() {
        // OEM skins host the per-service toggle inside a generic shell, so the
        // class name gives nothing away. Accessibility context alone is not
        // enough — that would swallow the whole section again.
        val accessibilityOnly = RecordingRoots(accessibility = true)
        assertThat(
            decide(signals(isGenericSubSettings = true), roots = accessibilityOnly)
        ).isEqualTo(Decision.IGNORE)

        val both = RecordingRoots(accessibility = true, focusGuard = true)
        assertThat(
            decide(signals(isGenericSubSettings = true), roots = both)
        ).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `bare SubSettings with no matching signal is ignored`() {
        val decision = decide(signals(isGenericSubSettings = true))

        assertThat(decision).isEqualTo(Decision.IGNORE)
    }

    // ------------------------------------------- FocusGuard control surface

    @Test
    fun `app details and uninstall are cut on arrival, before the name is known`() {
        // A evidência de que a página é do FocusGuard só existe depois que a
        // árvore renderiza, e esperar por ela era o intervalo em que dava para
        // tocar em "Desinstalar" antes do bloqueio. Agora a classe basta.
        val details = RecordingRoots(focusGuard = false)
        assertThat(decide(signals(classTargetsAppDetails = true), roots = details))
            .isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
        assertThat(details.reads).isEmpty()

        val uninstall = RecordingRoots(focusGuard = false)
        assertThat(decide(signals(classTargetsUninstall = true), roots = uninstall))
            .isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
        assertThat(uninstall.reads).isEmpty()
    }

    @Test
    fun `the app details cut costs every app while a block is running`() {
        // Preço assumido do corte por classe: sem saber de quem é a página, ela
        // sai para qualquer aplicativo. Documentado aqui para a troca ficar
        // explícita e não ser desfeita por engano.
        assertThat(
            decide(signals(classTargetsAppDetails = true, textMentionsFocusGuard = false))
        ).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `app details and uninstall reopen once nothing is being protected`() {
        assertThat(decide(signals(classTargetsAppDetails = true), engaged = false))
            .isEqualTo(Decision.IGNORE)
        assertThat(decide(signals(classTargetsUninstall = true), engaged = false))
            .isEqualTo(Decision.IGNORE)
    }

    @Test
    fun `package installer needs both FocusGuard and a destructive signal`() {
        val onlyName = decide(
            signals(packageName = INSTALLER, textMentionsFocusGuard = true)
        )
        assertThat(onlyName).isEqualTo(Decision.IGNORE)

        val both = decide(
            signals(
                packageName = INSTALLER,
                textMentionsFocusGuard = true,
                textMentionsDestructiveControl = true
            )
        )
        assertThat(both).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `package installer falls back to the node tree for both signals`() {
        val roots = RecordingRoots(focusGuard = true, destructive = true)

        val decision = decide(signals(packageName = INSTALLER), roots = roots)

        assertThat(decision).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    // ------------------------------------------- essential special access

    @Test
    fun `essential special access needs FocusGuard plus the access signal`() {
        val missingAccess = decide(
            signals(
                classTargetsEssentialSpecialAccess = true,
                textMentionsFocusGuard = true
            )
        )
        assertThat(missingAccess).isEqualTo(Decision.IGNORE)

        val complete = decide(
            signals(
                classTargetsEssentialSpecialAccess = true,
                textMentionsFocusGuard = true,
                textMentionsEssentialSpecialAccess = true
            )
        )
        assertThat(complete).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)
    }

    @Test
    fun `essential special access for another app is not intercepted`() {
        val decision = decide(
            signals(
                classTargetsEssentialSpecialAccess = true,
                textMentionsEssentialSpecialAccess = true
            )
        )

        assertThat(decision).isEqualTo(Decision.IGNORE)
    }

    // -------------------------------------------------------------- default

    @Test
    fun `unremarkable settings screen is left alone`() {
        val decision = decide(signals())

        assertThat(decision).isEqualTo(Decision.IGNORE)
    }

    private companion object {
        const val SETTINGS = "com.android.settings"
        const val INSTALLER = "com.android.packageinstaller"
    }
}
