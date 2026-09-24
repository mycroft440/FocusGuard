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
            FocusGuardLogger.log("AdsConsent", "Activity indisponível antes da validação UMP")
            onResult(false)
            return
        }
        ensureUpdated(activity) { consentInformation ->
            val canRequestAds = consentInformation.canRequestAds()
            logState("decisão de request", consentInformation)
            onResult(canRequestAds)
        }
    }

    /**
     * Decisão de consentimento salva de uma sessão anterior, sem ida à rede.
     * Serve só para adiantar o preload na abertura; toda exibição continua
     * passando por [ensureCanRequestAds] com o consentimento atualizado.
     */
    fun canRequestAdsFromCachedConsent(context: Context): Boolean = runCatching {
        UserMessagingPlatform.getConsentInformation(context.applicationContext)
            .canRequestAds()
    }.getOrDefault(false)

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
                logState("opções de privacidade não necessárias", consentInformation)
                onDismissed(null)
                return@ensureUpdated
            }
            UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
                if (formError != null) {
                    FocusGuardLogger.log(
                        "AdsConsent",
                        "Falha ao abrir opções de privacidade: " +
                            "code=${formError.errorCode}, message=${formError.message}"
                    )
                } else {
                    logState("opções de privacidade concluídas", consentInformation)
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

        logState("antes da atualização", consentInformation)
        val params = ConsentRequestParameters.Builder().build()
        consentInformation.requestConsentInfoUpdate(
            activity,
            params,
            {
                logState("atualização UMP recebida", consentInformation)
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    if (formError != null) {
                        FocusGuardLogger.log(
                            "AdsConsent",
                            "Formulário de privacidade terminou com aviso: " +
                                "code=${formError.errorCode}, message=${formError.message}"
                        )
                    } else {
                        FocusGuardLogger.log(
                            "AdsConsent",
                            "Formulário de privacidade concluído ou não necessário"
                        )
                    }
                    finish(consentInformation)
                }
            },
            { requestError ->
                FocusGuardLogger.log(
                    "AdsConsent",
                    "Falha ao atualizar consentimento: " +
                        "code=${requestError.errorCode}, message=${requestError.message}"
                )
                // A UMP pode reutilizar uma decisão válida de uma sessão anterior.
                finish(consentInformation)
            }
        )
    }

    private fun finish(consentInformation: ConsentInformation) {
        logState("estado final", consentInformation)
        val callbacks = synchronized(lock) {
            updateInFlight = false
            updateCompletedThisProcess = true
            pendingCallbacks.toList().also { pendingCallbacks.clear() }
        }
        callbacks.forEach { callback -> callback(consentInformation) }
    }

    private fun logState(stage: String, consentInformation: ConsentInformation) {
        val consentStatus = when (consentInformation.consentStatus) {
            ConsentInformation.ConsentStatus.NOT_REQUIRED -> "NOT_REQUIRED"
            ConsentInformation.ConsentStatus.REQUIRED -> "REQUIRED"
            ConsentInformation.ConsentStatus.OBTAINED -> "OBTAINED"
            else -> "UNKNOWN"
        }
        FocusGuardLogger.log(
            "AdsConsent",
            "UMP $stage: consentStatus=$consentStatus, " +
                "privacyOptions=${consentInformation.privacyOptionsRequirementStatus}, " +
                "formAvailable=${consentInformation.isConsentFormAvailable}, " +
                "canRequestAds=${consentInformation.canRequestAds()}"
        )
    }
}
