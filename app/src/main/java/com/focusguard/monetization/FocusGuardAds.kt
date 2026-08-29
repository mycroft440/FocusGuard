package com.focusguard.monetization

import android.app.Activity
import android.content.Context
import androidx.lifecycle.Lifecycle
import com.focusguard.utils.FocusGuardLogger
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAd
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAd
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdEventCallback
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Ponto único de integração de anúncios do FocusGuard.
 *
 * Enquanto a conta AdMob de produção não estiver configurada, todos os IDs abaixo
 * são IDs oficiais de teste do Google. Eles DEVEM ser substituídos antes de uma
 * versão de produção monetizada.
 */
object FocusGuardAds {
    const val TEST_APP_ID = "ca-app-pub-3940256099942544~3347511713"
    const val TEST_INTERSTITIAL_ID = "ca-app-pub-3940256099942544/1033173712"
    const val TEST_REWARDED_ID = "ca-app-pub-3940256099942544/5224354917"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initMutex = Mutex()
    @Volatile private var initialized = false
    private val pomodoroAdInFlight = AtomicBoolean(false)

    fun warmUp(context: Context) {
        scope.launch {
            runCatching { ensureInitialized(context.applicationContext) }
                .onFailure {
                    FocusGuardLogger.logError("Ads", "Falha ao inicializar anúncios", it)
                }
        }
    }

    private suspend fun ensureInitialized(context: Context) {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            MobileAds.initialize(
                context,
                InitializationConfig.Builder(TEST_APP_ID).build()
            ) { }
            // A API Next-Gen está pronta para aceitar requests quando initialize retorna.
            initialized = true
        }
    }

    /**
     * Mostra um rewarded somente após ação explícita do usuário.
     * A recompensa é creditada exclusivamente por onUserEarnedReward.
     */
    fun showRewarded(
        activity: Activity,
        onRewardEarned: () -> Unit,
        onClosedWithoutReward: () -> Unit,
        onUnavailable: (String) -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) {
            onUnavailable("A tela não está disponível para exibir o anúncio.")
            return
        }

        scope.launch {
            runCatching { ensureInitialized(activity.applicationContext) }
                .onFailure { error ->
                    withContext(Dispatchers.Main) {
                        onUnavailable(error.message ?: "Não foi possível inicializar os anúncios.")
                    }
                    return@launch
                }

            withContext(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed ||
                    !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                ) {
                    onUnavailable("Volte para o aplicativo e tente novamente.")
                    return@withContext
                }

                RewardedAd.load(
                    AdRequest.Builder(TEST_REWARDED_ID).build(),
                    object : AdLoadCallback<RewardedAd> {
                        override fun onAdLoaded(ad: RewardedAd) {
                            var rewardEarned = false
                            ad.adEventCallback = object : RewardedAdEventCallback {
                                override fun onAdDismissedFullScreenContent() {
                                    if (!rewardEarned) onClosedWithoutReward()
                                }

                                override fun onAdFailedToShowFullScreenContent(
                                    fullScreenContentError: FullScreenContentError
                                ) {
                                    if (!rewardEarned) {
                                        onUnavailable(
                                            fullScreenContentError.message.ifBlank {
                                                "O anúncio não pôde ser exibido."
                                            }
                                        )
                                    }
                                }
                            }
                            ad.show(activity) {
                                if (!rewardEarned) {
                                    rewardEarned = true
                                    onRewardEarned()
                                }
                            }
                        }

                        override fun onAdFailedToLoad(adError: LoadAdError) {
                            onUnavailable(
                                adError.message.ifBlank { "Nenhum anúncio está disponível agora." }
                            )
                        }
                    }
                )
            }
        }
    }

    /**
     * O fim do plano Pomodoro é persistido como pendente. Se o plano terminar em
     * segundo plano, o anúncio aparece somente quando uma Activity estiver RESUMED.
     */
    fun showPendingPomodoroCompletion(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) return
        if (!MonetizationStateStore.hasPomodoroCompletionAdPending(activity)) return
        if (!pomodoroAdInFlight.compareAndSet(false, true)) return

        scope.launch {
            runCatching { ensureInitialized(activity.applicationContext) }
                .onFailure { error ->
                    pomodoroAdInFlight.set(false)
                    FocusGuardLogger.logError("Ads", "Falha no anúncio final do Pomodoro", error)
                    return@launch
                }

            withContext(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed ||
                    !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                ) {
                    pomodoroAdInFlight.set(false)
                    return@withContext
                }

                // Uma tentativa de apresentação corresponde a um único encerramento de plano.
                MonetizationStateStore.consumePomodoroCompletionAdPending(activity)
                InterstitialAd.load(
                    AdRequest.Builder(TEST_INTERSTITIAL_ID).build(),
                    object : AdLoadCallback<InterstitialAd> {
                        override fun onAdLoaded(ad: InterstitialAd) {
                            ad.adEventCallback = object : InterstitialAdEventCallback {
                                override fun onAdDismissedFullScreenContent() {
                                    pomodoroAdInFlight.set(false)
                                }

                                override fun onAdFailedToShowFullScreenContent(
                                    fullScreenContentError: FullScreenContentError
                                ) {
                                    pomodoroAdInFlight.set(false)
                                    FocusGuardLogger.log(
                                        "Ads",
                                        "Interstitial Pomodoro indisponível: ${fullScreenContentError.message}"
                                    )
                                }
                            }
                            ad.show(activity)
                        }

                        override fun onAdFailedToLoad(adError: LoadAdError) {
                            pomodoroAdInFlight.set(false)
                            // Fail-open: o fim do Pomodoro nunca fica preso por falta de anúncio.
                            FocusGuardLogger.log(
                                "Ads",
                                "Interstitial Pomodoro não carregou: ${adError.message}"
                            )
                        }
                    }
                )
            }
        }
    }
}
