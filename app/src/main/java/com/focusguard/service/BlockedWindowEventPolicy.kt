package com.focusguard.service

import android.app.usage.UsageEvents
import android.view.accessibility.AccessibilityEvent
import com.focusguard.security.PasswordTargetAccessGrant

/**
 * Se um aviso de janela (TYPE_WINDOW_STATE_CHANGED) de um app bloqueado é uma entrada.
 *
 * O Android entrega esse aviso de qualquer janela, mesmo fora da tela: o app continua
 * mandando avisos depois que a pessoa saiu dele (terminou de carregar, fechou um
 * painel). Tomados por entrada, abriam a senha por cima da tela inicial ou de outro app
 * segundos depois da saída. Só a janela na frente (na lista de janelas e ativa) é uma
 * entrada. A lista pode atrasar em relação a uma janela recém-aberta: fora dela, o aviso
 * ainda conta se o UsageEvents mostra o app na frente.
 */
internal object BlockedWindowEventPolicy {

    /**
     * [eventWindowInFront]: a janela do aviso está na tela e ativa; false se está fora da
     * tela ou atrás de outra; null se a lista de janelas não pôde ser lida (na dúvida,
     * bloqueia). [appInFront] só é lido quando a janela não basta.
     */
    fun isEntry(eventWindowInFront: Boolean?, appInFront: () -> Boolean): Boolean =
        eventWindowInFront != false || appInFront()

    /**
     * Se um TYPE_WINDOWS_CHANGED pode ter posto uma janela na frente: uma janela nova
     * na tela, ou a janela ativa/com foco que mudou. Mudanças só de tamanho, título ou
     * camada não são entrada e não valem uma consulta ao app. [windowChanges] é null
     * antes do Android 9 (o aviso não diz o que mudou) e então sempre conta; zero
     * também conta, porque alguns fabricantes mandam o aviso sem as mudanças.
     */
    fun mayBringWindowToFront(windowChanges: Int?): Boolean =
        windowChanges == null ||
            windowChanges == 0 ||
            windowChanges and WINDOW_TO_FRONT_CHANGES != 0

    private const val WINDOW_TO_FRONT_CHANGES =
        AccessibilityEvent.WINDOWS_CHANGE_ADDED or
            AccessibilityEvent.WINDOWS_CHANGE_ACTIVE or
            AccessibilityEvent.WINDOWS_CHANGE_FOCUSED

    /**
     * Lê os eventos de uso em ordem e diz se [target] terminou na frente: uma Activity
     * dele retomada, sem pausa depois, e nenhum outro app retomado depois dela. Telas de
     * sistema por cima (permissão, compartilhar) não contam como outro app.
     */
    class ForegroundTracker(private val target: String) {
        // Por Activity: na troca interna A → B, a parada de A chega depois da volta de B.
        private val resumedTargetActivities = mutableSetOf<String>()

        val targetInFront: Boolean
            get() = resumedTargetActivities.isNotEmpty()

        fun onEvent(packageName: String?, className: String?, eventType: Int) {
            val eventPackage = packageName.orEmpty()
            val activity = className.orEmpty()
            when (eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> when {
                    eventPackage == target -> resumedTargetActivities.add(activity)
                    PasswordTargetAccessGrant.endsVisit(eventPackage) ->
                        resumedTargetActivities.clear()
                }
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> if (eventPackage == target) {
                    resumedTargetActivities.remove(activity)
                }
            }
        }
    }
}
