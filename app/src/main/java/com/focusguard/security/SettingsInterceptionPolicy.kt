package com.focusguard.security

/**
 * Pure decision tree that decides whether the accessibility service should
 * intercept a system settings screen, and what action to take.
 *
 * The individual classifiers ([AccessibilitySettingsPolicy] and
 * [ManagedSelfProtectionPolicy]) already have their own tests; what lives here
 * is the *composition* of those signals — the eight interception paths that were
 * previously inlined in `BlockingAccessibilityService.handleSettingsInterception`
 * and therefore untestable without a device.
 *
 * Reading the accessibility node tree is expensive, so the `root*` signals are
 * passed as lambdas and evaluated only on the branches that need them. Keep them
 * lazy when calling.
 */
object SettingsInterceptionPolicy {

    val settingsPackages = setOf(
        "com.android.settings",
        "com.miui.securitycenter",
        "com.huawei.systemmanager",
        "com.samsung.android.sm",
        "com.samsung.android.sm_cn"
    )

    val packageInstallerPackages = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.miui.packageinstaller"
    )

    val protectedSystemPackages = settingsPackages + packageInstallerPackages

    /** What the service should do with the event. */
    enum class Decision {
        /** Let the event through untouched. */
        IGNORE,

        /** Strict Pomodoro is running: go back and re-show the lock screen. */
        POMODORO_LOCK,

        /** Run the protection action and arm the transition guard. */
        PROTECT_AND_ARM_GUARD,

        /**
         * Run the protection action without re-arming the guard. Used when the
         * guard is already counting down and a follow-up, non-click event
         * arrives during the window.
         */
        PROTECT
    }

    /**
     * Signals extracted from the event before any node-tree read.
     *
     * @param isViewClickedEvent whether the event is `TYPE_VIEW_CLICKED`.
     * @param guardArmed whether the transition guard window is still open.
     */
    data class EventSignals(
        val packageName: String,
        val isViewClickedEvent: Boolean,
        val isWindowTransitionEvent: Boolean,
        val guardArmed: Boolean,

        /**
         * The per-service screen carrying an accessibility on/off switch. Note
         * this is deliberately *not* "any accessibility screen": the section as a
         * whole stays usable during a block.
         */
        val classTargetsAccessibilityServiceToggle: Boolean,

        /**
         * Qualquer tela que apenas lista recursos de acessibilidade. É de onde
         * se chega ao interruptor do FocusGuard.
         */
        val classTargetsAccessibilityList: Boolean,
        val classTargetsDeviceAdmin: Boolean,
        val classTargetsAppDetails: Boolean,
        val classTargetsUninstall: Boolean,
        val classTargetsEssentialSpecialAccess: Boolean,
        val isGenericSubSettings: Boolean,
        val textMentionsAccessibility: Boolean,
        val textMentionsInstalledAccessibilityApps: Boolean,
        val textMentionsDeviceAdmin: Boolean,
        val textMentionsFocusGuard: Boolean,
        val textMentionsDestructiveControl: Boolean,
        val textMentionsEssentialSpecialAccess: Boolean
    )

    /**
     * Lazily-evaluated reads of the live accessibility node tree. Each is only
     * invoked on the branch that needs it.
     */
    class RootSignals(
        val mentionsAccessibility: () -> Boolean,
        val mentionsDeviceAdmin: () -> Boolean,
        val mentionsFocusGuard: () -> Boolean,
        val mentionsDestructiveControl: () -> Boolean,
        val mentionsEssentialSpecialAccess: () -> Boolean
    )

    /**
     * @param selfProtectionEngaged true when the app should defend its own
     *   removal right now. Two things turn it on:
     *   - a true Device Owner with hardening armed and maintenance closed
     *     (the strongest form), or
     *   - consumer mode: no Device Owner, but a block, usage limit or the adult
     *     filter is live at this moment. Off the moment nothing is being
     *     protected, so a phone with no active block stays freely removable.
     *
     *   When it is off, every screen is ignored — the whole point is that the
     *   app never traps a user who has nothing running.
     */
    fun decide(
        signals: EventSignals,
        selfProtectionEngaged: Boolean,
        strictPomodoroActive: Boolean,
        rootSignals: RootSignals
    ): Decision {
        if (signals.packageName !in protectedSystemPackages) return Decision.IGNORE
        if (!selfProtectionEngaged) return Decision.IGNORE

        if (strictPomodoroActive) return Decision.POMODORO_LOCK

        // Corte seco pela classe da tela, antes de qualquer texto ou leitura de
        // árvore, e sem depender de o FocusGuard estar nomeado nela.
        //
        // É a decisão mais rápida que existe aqui — nenhum binder, nenhuma
        // varredura — e é onde a velocidade importa: cada uma destas telas
        // desliga a proteção em um ou dois toques, e qualquer condição conferida
        // antes é tempo que o usuário tem para alcançar o botão.
        //
        // Informações do app e o diálogo de desinstalação entram sem exigir o
        // nome porque a evidência de que a página é do FocusGuard só existe
        // depois que a árvore renderiza. Esperar por ela era exatamente o
        // intervalo em que dava para tocar em "Desinstalar" antes do bloqueio.
        //
        // O preço é grosso e assumido: enquanto houver bloqueio ativo, nenhuma
        // página de informações de app abre e nenhuma desinstalação acontece —
        // nem de outros aplicativos. Fora de bloqueio, tudo volta ao normal.
        if (signals.classTargetsAccessibilityList ||
            signals.classTargetsAccessibilityServiceToggle ||
            signals.classTargetsDeviceAdmin ||
            signals.classTargetsAppDetails ||
            signals.classTargetsUninstall
        ) {
            return Decision.PROTECT_AND_ARM_GUARD
        }

        // Defesa específica para a rota da One UI mostrada no aparelho:
        // Acessibilidade > Configurações avançadas > Aplicativos instalados.
        // O rótulo chega no evento mesmo quando a classe OEM é genérica.
        if (signals.textMentionsInstalledAccessibilityApps) {
            return Decision.PROTECT_AND_ARM_GUARD
        }

        // Algumas versões da One UI entregam a tela principal numa Activity
        // genérica. Na transição de janela, o título "Acessibilidade" identifica
        // a tela sem varrer a árvore inteira. Eventos de conteúdo ficam fora
        // desta regra para não fechar a tela inicial de Configurações só porque
        // o item Acessibilidade aparece na lista.
        if (signals.isWindowTransitionEvent && signals.textMentionsAccessibility) {
            return Decision.PROTECT_AND_ARM_GUARD
        }

        // A click on an entry point fires before the destination screen opens, so
        // arm a short guard and intercept the transition too.
        //
        // The Accessibility entry is blocked at the menu, without requiring the app
        // to be named: it is the one choke point that does not depend on an OEM's
        // class names, and it is where the switch that disables FocusGuard lives.
        // The practical consequence is that the Accessibility section as a whole is
        // unreachable while a block is armed — an explicit product decision, not an
        // oversight. See `classTargetsAccessibilityServiceToggle` for the narrower
        // rule that still guards the destination if this click is ever missed.
        if (signals.isViewClickedEvent &&
            (signals.textMentionsAccessibility ||
                signals.textMentionsDeviceAdmin ||
                (signals.textMentionsFocusGuard &&
                    (signals.textMentionsDestructiveControl ||
                        signals.textMentionsEssentialSpecialAccess)))
        ) {
            return Decision.PROTECT_AND_ARM_GUARD
        }

        if (signals.guardArmed && !signals.isViewClickedEvent) {
            return Decision.PROTECT
        }

        // Only the screen that can switch FocusGuard's own service off, and only
        // once the screen is confirmed to be about FocusGuard. Another service's
        // toggle is none of our business.
        if (signals.classTargetsAccessibilityServiceToggle &&
            (signals.textMentionsFocusGuard || rootSignals.mentionsFocusGuard())
        ) {
            return Decision.PROTECT_AND_ARM_GUARD
        }

        if (signals.classTargetsDeviceAdmin ||
            (signals.isGenericSubSettings &&
                (signals.textMentionsDeviceAdmin || rootSignals.mentionsDeviceAdmin()))
        ) {
            return Decision.PROTECT_AND_ARM_GUARD
        }

        val onFocusGuardControlSurface =
            signals.classTargetsAppDetails ||
                signals.classTargetsUninstall ||
                signals.packageName in packageInstallerPackages
        if (onFocusGuardControlSurface &&
            (signals.textMentionsFocusGuard || rootSignals.mentionsFocusGuard()) &&
            (signals.classTargetsAppDetails ||
                signals.classTargetsUninstall ||
                signals.textMentionsDestructiveControl ||
                rootSignals.mentionsDestructiveControl())
        ) {
            return Decision.PROTECT_AND_ARM_GUARD
        }

        if (signals.classTargetsEssentialSpecialAccess &&
            (signals.textMentionsFocusGuard || rootSignals.mentionsFocusGuard()) &&
            (signals.textMentionsEssentialSpecialAccess ||
                rootSignals.mentionsEssentialSpecialAccess())
        ) {
            return Decision.PROTECT_AND_ARM_GUARD
        }

        // OEM skins often host the per-service toggle inside a generic SubSettings
        // shell, so the class name alone gives nothing away. Requiring both the
        // accessibility context *and* FocusGuard keeps that path covered without
        // swallowing the rest of the section.
        if (signals.isGenericSubSettings &&
            (signals.textMentionsAccessibility || rootSignals.mentionsAccessibility()) &&
            (signals.textMentionsFocusGuard || rootSignals.mentionsFocusGuard())
        ) {
            return Decision.PROTECT_AND_ARM_GUARD
        }

        return Decision.IGNORE
    }
}
