package com.focusguard.monetization

import androidx.activity.ComponentActivity
import com.focusguard.utils.FocusGuardLogger
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * Centraliza a atualização de consentimento da UMP.
 *
 * Uma atualização é feita por processo antes da primeira solicitação de anúncio.
 * Isso mantém anúncios bloqueados até que a UMP diga que podem ser solicitados.
 */
object AdsConsentManager {
    private val lock = Any()
    private val pendingCallbacks = mutableListOf<(Boolean) -> Unit>()

    @Volatile
    private var updateInFlight = false

    @Volatile
    private var updateCompletedThisProcess = false

    fun ensureCanRequestAds(
        activity: ComponentActivity,
        onResult: (Boolean) -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) {
            onResult(false)
            return
        }

        val consentInformation = UserMessagingPlatform.getConsentInformation(
            activity.applicationContext
        )

        synchronized(lock) {
            if (updateCompletedThisProcess) {
                onResult(consentInformation.canRequestAds())
                return
            }
            pendingCallbacks += onResult
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
        val canRequest = consentInformation.canRequestAds()
        callbacks.forEach { callback -> callback(canRequest) }
    }
}
