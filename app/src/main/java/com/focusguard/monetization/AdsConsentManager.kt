package com.focusguard.monetization

import android.content.Context
import androidx.activity.ComponentActivity
import com.focusguard.utils.FocusGuardLogger
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * Centraliza consentimento e opções de privacidade da UMP.
 *
 * A atualização de consentimento ocorre uma vez por processo e pode ser iniciada
 * no lançamento do app. Toda solicitação de anúncio aguarda esta etapa e consulta
 * canRequestAds() antes de prosseguir.
 */
object AdsConsentManager {
    private val lock = Any()
    private val pendingCallbacks = mutableListOf<(ConsentInformation) -> Unit>()

    @Volatile
    private var updateInFlight = false

    @Volatile
    private var updateCompletedThisProcess = false

    /**
     * Atualiza consentimento no início da Activity. O callback sempre é entregue
     * depois da tentativa de atualização/formulário, inclusive quando a UMP usa
     * uma decisão válida de sessão anterior após erro de rede.
     */
    fun refresh(
        activity: ComponentActivity,
        onComplete: () -> Unit = {}
    ) {
        ensureUpdated(activity) { onComplete() }
    }

    fun ensureCanRequestAds(
        activity: ComponentActivity,
        onResult: (Boolean) -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) {
            onResult(false)
            return
        }
        ensureUpdated(activity) { consentInformation ->
            onResult(consentInformation.canRequestAds())
        }
    }

    fun isPrivacyOptionsRequired(context: Context): Boolean {
        val consentInformation = UserMessagingPlatform.getConsentInformation(
            context.applicationContext
        )
        return consentInformation.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    }

    fun showPrivacyOptions(
        activity: ComponentActivity,
        onDismissed: (String?) -> Unit = {}
    ) {
        if (activity.isFinishing || activity.isDestroyed) {
            onDismissed("A tela não está disponível.")
            return
        }
        ensureUpdated(activity) { consentInformation ->
            if (consentInformation.privacyOptionsRequirementStatus !=
                ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
            ) {
                onDismissed(null)
                return@ensureUpdated
            }
            UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
                if (formError != null) {
                    FocusGuardLogger.log(
                        "AdsConsent",
                        "Falha ao abrir opções de privacidade: ${formError.message}"
                    )
                }
                onDismissed(formError?.message)
            }
        }
    }

    private fun ensureUpdated(
        activity: ComponentActivity,
        callback: (ConsentInformation) -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val consentInformation = UserMessagingPlatform.getConsentInformation(
            activity.applicationContext
        )

        synchronized(lock) {
            if (updateCompletedThisProcess) {
                callback(consentInformation)
                return
            }
            pendingCallbacks += callback
            if (updateInFlight) return
            updateInFlight = true
        }

        val params = ConsentRequestParameters.Builder().build()
        consentInformation.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    if (formError != null) {
                        FocusGuardLogger.log(
                            "AdsConsent",
                            "Formulário de privacidade terminou com aviso: ${formError.message}"
                        )
                    }
                    finish(consentInformation)
                }
            },
            { requestError ->
                FocusGuardLogger.log(
                    "AdsConsent",
                    "Falha ao atualizar consentimento: ${requestError.message}"
                )
                // A UMP pode reutilizar uma decisão válida de uma sessão anterior.
                finish(consentInformation)
            }
        )
    }

    private fun finish(consentInformation: ConsentInformation) {
        val callbacks = synchronized(lock) {
            updateInFlight = false
            updateCompletedThisProcess = true
            pendingCallbacks.toList().also { pendingCallbacks.clear() }
        }
        callbacks.forEach { callback -> callback(consentInformation) }
    }
}
