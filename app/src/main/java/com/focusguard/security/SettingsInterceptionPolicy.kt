package com.focusguard.security

/**
 * Pure decision tree that decides whether the accessibility service should
 * intercept a system settings screen, and what action to take.
 *
 * Self-protection is deliberately target-scoped: a Settings class name, a Device
 * Admin/Accessibility gateway label, or the mere presence of the app name somewhere
 * in the root tree is never sufficient to block navigation. The current event must
 * directly identify FocusGuard before destructive controls are intercepted.
 *
 * A única identidade lida fora do evento é a de uma página de um app só (as
 * informações ou as notificações de um app), e lida na janela do próprio
 * evento: ali o rótulo exato do HardBlock é o assunto da página, não um item
 * entre outros. Veja [AppPage].
 */
object SettingsInterceptionPolicy {

    val settingsPackages = setOf(
        "com.android.settings",
        "com.miui.securitycenter",
        "com.huawei.systemmanager",
        "com.samsung.android.sm",
        "com.samsung.android.sm_cn",
        // One UI hospeda parte da área de Acessibilidade fora do Configurações.
        "com.samsung.accessibility"
    )

    val packageInstallerPackages = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.miui.packageinstaller"
    )

    val systemUiPackages = setOf("com.android.systemui")

    val protectedSystemPackages = settingsPackages + packageInstallerPackages

    /** System UI is included only for narrowly classified FocusGuard actions. */
    val interceptionPackages = protectedSystemPackages + systemUiPackages

    /**
     * A página do Configurações em que o evento acontece, quando ela trata de um
     * app só. Decidida na troca de janela, pela classe da Activity ou pelo título
     * "Informações do aplicativo", e mantida pelos eventos de conteúdo seguintes,
     * que chegam com a classe da View e não da tela.
     */
    enum class AppPage {
        NONE,
        /** Informações do app: notificações, permissões, bateria, desinstalar. */
        DETAILS,
        /** Notificações de um app, aberta direto pela engrenagem da notificação. */
        NOTIFICATIONS
    }

    fun appPageForWindow(className: String, windowTitle: Iterable<CharSequence?>): AppPage = when {
        ManagedSelfProtectionPolicy.classTargetsAppNotificationSettings(className) ->
            AppPage.NOTIFICATIONS
        ManagedSelfProtectionPolicy.classTargetsAppDetails(className) ||
            ManagedSelfProtectionPolicy.textTargetsAppInfoGateway(windowTitle) ->
            AppPage.DETAILS
        else -> AppPage.NONE
    }

    enum class Decision {
        IGNORE,
        PROTECT_AND_ARM_GUARD,
        PROTECT
    }

    data class EventSignals(
        val packageName: String,
        val isViewClickedEvent: Boolean,
        val isWindowTransitionEvent: Boolean,
        val guardArmed: Boolean,
        val classTargetsAccessibilityServiceToggle: Boolean,
        val classTargetsAccessibilityList: Boolean,
        val classTargetsDeviceAdmin: Boolean,
        val classTargetsAppDetails: Boolean,
        val classTargetsUninstall: Boolean,
        val classTargetsEssentialSpecialAccess: Boolean,
        val isGenericSubSettings: Boolean,
        val textMentionsAccessibility: Boolean,
        val textMentionsInstalledAccessibilityApps: Boolean,
        val textMentionsAccessibilityDisclosure: Boolean,
        val textMentionsDeviceAdmin: Boolean,
        val textMentionsFocusGuard: Boolean,
        val textMentionsDestructiveControl: Boolean,
        val textMentionsEssentialSpecialAccess: Boolean,
        val textMentionsAppInfoGateway: Boolean = false,
        /** A página do próprio serviço de acessibilidade do HardBlock. */
        val textMentionsOwnAccessibilityServicePage: Boolean = false,
        val appPage: AppPage = AppPage.NONE
    )

    /**
     * Root reads remain available for contextual classification only. In particular,
     * [mentionsFocusGuard] is intentionally not used as target identity by [decide].
     */
    class RootSignals(
        mentionsAccessibility: () -> Boolean,
        mentionsDeviceAdmin: () -> Boolean,
        mentionsFocusGuard: () -> Boolean,
        mentionsDestructiveControl: () -> Boolean,
        mentionsEssentialSpecialAccess: () -> Boolean,
        mentionsOwnAccessibilityServicePage: () -> Boolean = { false },
        /** A janela do evento tem um nó cujo texto é exatamente o rótulo do HardBlock. */
        eventWindowNamesFocusGuard: () -> Boolean = { false },
        /** A janela do evento tem os botões da página de um app (Forçar parada, Desinstalar). */
        eventWindowShowsAppPageControls: () -> Boolean = { false }
    ) {
        val mentionsAccessibility = memoized(mentionsAccessibility)
        val mentionsDeviceAdmin = memoized(mentionsDeviceAdmin)
        val mentionsFocusGuard = memoized(mentionsFocusGuard)
        val mentionsDestructiveControl = memoized(mentionsDestructiveControl)
        val mentionsEssentialSpecialAccess = memoized(mentionsEssentialSpecialAccess)
        val mentionsOwnAccessibilityServicePage = memoized(mentionsOwnAccessibilityServicePage)
        val eventWindowNamesFocusGuard = memoized(eventWindowNamesFocusGuard)
        val eventWindowShowsAppPageControls = memoized(eventWindowShowsAppPageControls)

        private fun memoized(source: () -> Boolean): () -> Boolean {
            val cached by lazy(LazyThreadSafetyMode.NONE) { source() }
            return { cached }
        }
    }

    fun decide(
        signals: EventSignals,
        selfProtectionEngaged: Boolean,
        deviceAdminActivationAuthorized: Boolean,
        maintenanceActive: Boolean = false,
        rootSignals: RootSignals
    ): Decision {
        if (signals.packageName !in interceptionPackages) return Decision.IGNORE

        updateTargetScope(signals)

        if (!selfProtectionEngaged) {
            SelfProtectionTargetScope.clear()
            return Decision.IGNORE
        }

        if (maintenanceActive) {
            if (!signals.textMentionsFocusGuard) return Decision.IGNORE
            val essentialAccess = signals.classTargetsEssentialSpecialAccess ||
                signals.textMentionsEssentialSpecialAccess ||
                rootSignals.mentionsEssentialSpecialAccess()
            return if (essentialAccess) Decision.PROTECT else Decision.IGNORE
        }

        if (signals.packageName in systemUiPackages) {
            if (!signals.isViewClickedEvent || !signals.textMentionsFocusGuard) {
                return Decision.IGNORE
            }

            return if (signals.textMentionsAccessibilityDisclosure ||
                signals.textMentionsDeviceAdmin
            ) {
                Decision.PROTECT
            } else {
                Decision.IGNORE
            }
        }

        // A página com o interruptor do serviço do HardBlock pode ser aberta
        // direto (notificação "pode ver e controlar sua tela", atalho, busca),
        // sem a palavra "Acessibilidade" na tela. Ela é reconhecida pela
        // descrição do serviço ou pelo rótulo "Atalho de HardBlock", textos que
        // só existem nessa página.
        if (signals.textMentionsOwnAccessibilityServicePage ||
            rootSignals.mentionsOwnAccessibilityServicePage()
        ) {
            return Decision.PROTECT
        }

        // As informações do HardBlock (ou as notificações dele) abertas direto —
        // ícone do launcher, Recentes, busca, a engrenagem da notificação, outro
        // app — chegam só com o título genérico, sem o nome do app no evento, e
        // ali dá para desligar as notificações ou forçar a parada. A identidade
        // vem então do cabeçalho da própria página. Nas informações, também os
        // botões da página precisam estar à vista: o SpaActivity hospeda ainda
        // as listas de apps, onde o HardBlock é só uma linha entre outras.
        if (!signals.isViewClickedEvent && signals.appPage != AppPage.NONE) {
            val ownAppPage = rootSignals.eventWindowNamesFocusGuard() &&
                (signals.appPage == AppPage.NOTIFICATIONS ||
                    rootSignals.eventWindowShowsAppPageControls())
            if (ownAppPage) return Decision.PROTECT_AND_ARM_GUARD
        }

        // ACTION_ADD_DEVICE_ADMIN is a short app-initiated enrollment window.
        // Direct FocusGuard identity is required, and unrelated destructive or
        // special-access surfaces remain protected even during that window.
        if (deviceAdminActivationAuthorized &&
            signals.textMentionsFocusGuard &&
            !signals.classTargetsAppDetails &&
            !signals.classTargetsUninstall &&
            !signals.classTargetsAccessibilityServiceToggle &&
            !signals.classTargetsEssentialSpecialAccess
        ) {
            return Decision.IGNORE
        }

        if (signals.isViewClickedEvent &&
            signals.textMentionsAppInfoGateway &&
            signals.textMentionsFocusGuard
        ) {
            return Decision.PROTECT
        }

        // Device Admin list/gateway stays available. Only a current event that
        // directly identifies FocusGuard may be treated as our administrative
        // control. A Device Admin class alone never reaches this branch.
        val deviceAdminContext = signals.textMentionsDeviceAdmin ||
            (signals.textMentionsFocusGuard && rootSignals.mentionsDeviceAdmin())
        if (signals.textMentionsFocusGuard && deviceAdminContext) {
            return Decision.PROTECT
        }

        if (signals.classTargetsAccessibilityServiceToggle &&
            signals.textMentionsFocusGuard
        ) {
            return Decision.PROTECT
        }

        if (signals.isViewClickedEvent && signals.textMentionsFocusGuard) {
            return Decision.PROTECT
        }

        if (signals.guardArmed &&
            !signals.isViewClickedEvent &&
            signals.textMentionsFocusGuard
        ) {
            return Decision.PROTECT
        }

        val onFocusGuardControlSurface =
            signals.classTargetsAppDetails ||
                signals.classTargetsUninstall ||
                signals.packageName in packageInstallerPackages
        if (onFocusGuardControlSurface &&
            signals.textMentionsFocusGuard &&
            (signals.classTargetsAppDetails ||
                signals.classTargetsUninstall ||
                signals.textMentionsDestructiveControl ||
                rootSignals.mentionsDestructiveControl())
        ) {
            return Decision.PROTECT
        }

        if (signals.classTargetsEssentialSpecialAccess &&
            signals.textMentionsFocusGuard &&
            (signals.textMentionsEssentialSpecialAccess ||
                rootSignals.mentionsEssentialSpecialAccess())
        ) {
            return Decision.PROTECT
        }

        if (signals.isGenericSubSettings &&
            signals.textMentionsFocusGuard &&
            (signals.textMentionsAccessibility || rootSignals.mentionsAccessibility())
        ) {
            return Decision.PROTECT
        }

        return Decision.IGNORE
    }

    /**
     * Bind temporary self-protection state to the current interaction. A direct
     * FocusGuard identity arms it; the next click/window transition in a protected
     * system package without that identity clears it immediately.
     */
    private fun updateTargetScope(signals: EventSignals) {
        if (signals.textMentionsFocusGuard) {
            SelfProtectionTargetScope.confirmFocusGuardTarget()
            return
        }
        if (signals.isViewClickedEvent || signals.isWindowTransitionEvent) {
            SelfProtectionTargetScope.clear()
        }
    }
}
