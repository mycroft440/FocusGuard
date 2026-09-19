package com.focusguard.monetization

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import com.focusguard.BuildConfig
import com.focusguard.utils.FocusGuardLogger
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdPreloader
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.PreloadConfiguration
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationStatus
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAd
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoader
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoaderCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAd
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdEventCallback
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Ponto único de integração de anúncios do FocusGuard.
 *
 * Os IDs são fornecidos pelo BuildConfig: Debug usa unidades oficiais de teste
 * do Google e Release usa as unidades reais dos formatos ativos. A seleção é
 * automática e não exige troca manual antes de publicar.
 */
object FocusGuardAds {

    private const val ADAPTIVE_BANNER_PRELOAD_BUFFER_SIZE = 2
    private const val ADAPTIVE_BANNER_PRELOAD_PREFIX = "focusguard-adaptive-banner"
    private const val INITIALIZATION_TIMEOUT_MILLIS = 35_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initMutex = Mutex()

    @Volatile
    private var initialized = false

    private val runtimeConfigurationLogged = AtomicBoolean(false)
    private val pomodoroAdInFlight = AtomicBoolean(false)

    /**
     * Mantido para compatibilidade com o Application. O processo ainda não possui
     * uma Activity capaz de concluir a UMP, portanto o preload real começa no
     * primeiro warmUp(ComponentActivity).
     */
    fun warmUp(context: Context) {
        FocusGuardLogger.log("Ads", "Warm-up aguardando a primeira Activity para validar consentimento")
    }

    /**
     * Inicia consentimento + SDK + preload do banner já na abertura da Activity.
     *
     * O preloader oficial mantém um buffer pequeno e o reabastece automaticamente
     * depois que uma tela consome um BannerAd. Nenhum AdView invisível é mantido.
     */
    fun warmUp(activity: ComponentActivity) {
        if (activity.isFinishing || activity.isDestroyed) return
        withAdsReady(
            activity = activity,
            onUnavailable = { message ->
                FocusGuardLogger.log("Ads", "Warm-up indisponível: $message")
            },
            onReady = {
                val widthDp = activity.resources.configuration.screenWidthDp.coerceAtLeast(300)
                startAdaptiveBannerPreload(activity, widthDp)
            }
        )
    }

    private suspend fun ensureInitialized(context: Context) {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return

            logRuntimeConfiguration(context)
            val completion = CompletableDeferred<InitializationStatus>()
            MobileAds.initialize(
                context.applicationContext,
                InitializationConfig.Builder(BuildConfig.ADMOB_APP_ID).build()
            ) { status ->
                completion.complete(status)
            }

            val initializationStatus = withTimeout(INITIALIZATION_TIMEOUT_MILLIS) {
                completion.await()
            }
            logInitializationStatus(initializationStatus)
            initialized = true
            FocusGuardLogger.log("Ads", "GMA Next-Gen inicializado; requests de anúncios liberados")
        }
    }

    private fun logRuntimeConfiguration(context: Context) {
        if (!runtimeConfigurationLogged.compareAndSet(false, true)) return
        FocusGuardLogger.log(
            "Ads",
            "Configuração runtime: package=${context.packageName}, " +
                "buildType=${BuildConfig.BUILD_TYPE}, appId=${BuildConfig.ADMOB_APP_ID}, " +
                "banner=${BuildConfig.ADMOB_BANNER_AD_UNIT_ID}, " +
                "interstitial=${BuildConfig.ADMOB_INTERSTITIAL_AD_UNIT_ID}, " +
                "rewarded=${BuildConfig.ADMOB_REWARDED_AD_UNIT_ID}, " +
                "native=${BuildConfig.ADMOB_NATIVE_AD_UNIT_ID}"
        )
    }

    private fun logInitializationStatus(status: InitializationStatus) {
        val adapters = status.adapterStatusMap.entries
            .sortedBy { it.key }
            .joinToString(" | ") { (adapterName, adapterStatus) ->
                val description = adapterStatus.description.replace('\n', ' ').trim()
                "$adapterName=${adapterStatus.initializationState}," +
                    "${adapterStatus.latency}ms,$description"
            }
            .ifBlank { "nenhum adaptador reportado" }

        FocusGuardLogger.log(
            "Ads",
            "Inicialização GMA concluída em ${status.totalLatency}ms; adapters=[$adapters]"
        )
    }

    private fun logLoadFailure(format: String, adError: LoadAdError) {
        FocusGuardLogger.log(
            "Ads",
            AdsDiagnostics.formatLoadFailure(
                format = format,
                code = adError.code.toString(),
                message = adError.message,
                errorDump = adError.toString(),
                responseInfo = adError.responseInfo?.toString()
            )
        )
    }

    private fun withAdsReady(
        activity: ComponentActivity,
        onReady: () -> Unit,
        onUnavailable: (String) -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) {
            onUnavailable("A tela não está disponível para exibir anúncios.")
            return
        }

        activity.runOnUiThread {
            AdsConsentManager.ensureCanRequestAds(activity) { canRequestAds ->
                if (!canRequestAds) {
                    onUnavailable("Os anúncios não podem ser solicitados com as escolhas de privacidade atuais.")
                    return@ensureCanRequestAds
                }

                scope.launch {
                    runCatching { ensureInitialized(activity.applicationContext) }
                        .onFailure { error ->
                            FocusGuardLogger.logError(
                                "Ads",
                                "Falha ao inicializar GMA Next-Gen",
                                error
                            )
                            withContext(Dispatchers.Main) {
                                onUnavailable(
                                    error.message ?: "Não foi possível inicializar os anúncios."
                                )
                            }
                        }
                        .onSuccess {
                            withContext(Dispatchers.Main) {
                                if (activity.isFinishing || activity.isDestroyed) {
                                    onUnavailable("A tela não está mais disponível.")
                                } else {
                                    onReady()
                                }
                            }
                        }
                }
            }
        }
    }

    private fun startAdaptiveBannerPreload(
        activity: ComponentActivity,
        widthDp: Int
    ) {
        val normalizedWidthDp = widthDp.coerceAtLeast(300)
        val preloadId = adaptiveBannerPreloadId(normalizedWidthDp)
        val adSize = AdSize.getLargeAnchoredAdaptiveBannerAdSize(
            activity,
            normalizedWidthDp
        )
        val request = BannerAdRequest.Builder(
            BuildConfig.ADMOB_BANNER_AD_UNIT_ID,
            adSize
        ).build()
        val started = BannerAdPreloader.start(
            preloadId,
            PreloadConfiguration(
                request = request,
                bufferSize = ADAPTIVE_BANNER_PRELOAD_BUFFER_SIZE
            )
        )
        FocusGuardLogger.log(
            "Ads",
            if (started) {
                "Preload de banner iniciado para largura ${normalizedWidthDp}dp"
            } else {
                "Preload de banner já ativo para largura ${normalizedWidthDp}dp"
            }
        )
    }

    private fun adaptiveBannerPreloadId(widthDp: Int): String =
        "$ADAPTIVE_BANNER_PRELOAD_PREFIX-${widthDp.coerceAtLeast(300)}"

    fun loadNative(
        activity: ComponentActivity,
        onLoaded: (NativeAd) -> Unit,
        onUnavailable: (String) -> Unit = {}
    ) {
        withAdsReady(
            activity = activity,
            onUnavailable = onUnavailable,
            onReady = {
                val request = NativeAdRequest.Builder(
                    BuildConfig.ADMOB_NATIVE_AD_UNIT_ID,
                    listOf(NativeAd.NativeAdType.NATIVE)
                ).build()
                NativeAdLoader.load(
                    request,
                    object : NativeAdLoaderCallback {
                        override fun onNativeAdLoaded(nativeAd: NativeAd) {
                            FocusGuardLogger.log("Ads", "Native carregado")
                            if (activity.isFinishing || activity.isDestroyed) {
                                nativeAd.destroy()
                            } else {
                                onLoaded(nativeAd)
                            }
                        }

                        override fun onAdFailedToLoad(adError: LoadAdError) {
                            logLoadFailure("Native", adError)
                            onUnavailable(
                                adError.message.ifBlank {
                                    "Nenhum anúncio nativo está disponível agora."
                                }
                            )
                        }
                    }
                )
            }
        )
    }

    fun loadLargeAdaptiveBanner(
        activity: ComponentActivity,
        adView: AdView,
        widthDp: Int,
        onLoaded: () -> Unit = {},
        onUnavailable: (String) -> Unit = {}
    ) {
        withAdsReady(
            activity = activity,
            onUnavailable = onUnavailable,
            onReady = {
                val normalizedWidthDp = widthDp.coerceAtLeast(300)
                val preloadId = adaptiveBannerPreloadId(normalizedWidthDp)
                val preloadedAd = BannerAdPreloader.pollAd(preloadId)
                if (preloadedAd != null) {
                    adView.registerBannerAd(preloadedAd, activity)
                    FocusGuardLogger.log(
                        "Ads",
                        "Banner adaptativo servido do preload para ${normalizedWidthDp}dp"
                    )
                    onLoaded()
                    return@withAdsReady
                }

                val adSize = AdSize.getLargeAnchoredAdaptiveBannerAdSize(
                    activity,
                    normalizedWidthDp
                )
                val request = BannerAdRequest.Builder(
                    BuildConfig.ADMOB_BANNER_AD_UNIT_ID,
                    adSize
                ).build()
                adView.loadAd(
                    request,
                    object : AdLoadCallback<BannerAd> {
                        override fun onAdLoaded(ad: BannerAd) {
                            FocusGuardLogger.log("Ads", "Banner adaptativo carregado diretamente")
                            onLoaded()
                        }

                        override fun onAdFailedToLoad(adError: LoadAdError) {
                            logLoadFailure("Banner adaptativo", adError)
                            onUnavailable(
                                adError.message.ifBlank { "Nenhum banner está disponível agora." }
                            )
                        }
                    }
                )
            }
        )
    }

    /** A recompensa só é creditada por onUserEarnedReward. */
    fun showRewarded(
        activity: ComponentActivity,
        onRewardEarned: () -> Unit,
        onClosedWithoutReward: () -> Unit,
        onUnavailable: (String) -> Unit
    ) {
        withAdsReady(
            activity = activity,
            onUnavailable = onUnavailable,
            onReady = {
                if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    onUnavailable("Volte para o aplicativo e tente novamente.")
                    return@withAdsReady
                }

                RewardedAd.load(
                    AdRequest.Builder(BuildConfig.ADMOB_REWARDED_AD_UNIT_ID).build(),
                    object : AdLoadCallback<RewardedAd> {
                        override fun onAdLoaded(ad: RewardedAd) {
                            FocusGuardLogger.log("Ads", "Rewarded carregado")
                            var rewardEarned = false
                            ad.adEventCallback = object : RewardedAdEventCallback {
                                override fun onAdDismissedFullScreenContent() {
                                    FocusGuardLogger.log(
                                        "Ads",
                                        "Rewarded fechado; rewardEarned=$rewardEarned"
                                    )
                                    if (!rewardEarned) onClosedWithoutReward()
                                }

                                override fun onAdFailedToShowFullScreenContent(
                                    fullScreenContentError: FullScreenContentError
                                ) {
                                    FocusGuardLogger.log(
                                        "Ads",
                                        "Rewarded falhou ao exibir: $fullScreenContentError"
                                    )
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
                                    FocusGuardLogger.log("Ads", "Rewarded creditado pelo callback real")
                                    onRewardEarned()
                                }
                            }
                        }

                        override fun onAdFailedToLoad(adError: LoadAdError) {
                            logLoadFailure("Rewarded", adError)
                            onUnavailable(
                                adError.message.ifBlank {
                                    "Nenhum anúncio está disponível agora."
                                }
                            )
                        }
                    }
                )
            }
        )
    }

    /**
     * Exibe no máximo um intersticial por conclusão persistida de plano Pomodoro.
     * Falha de carregamento mantém a conclusão na fila; falha ao apresentar devolve
     * a reserva à fila para uma futura tentativa.
     */
    fun showPendingPomodoroCompletion(activity: ComponentActivity) {
        if (activity.isFinishing || activity.isDestroyed ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) return
        if (!MonetizationStateStore.hasPomodoroCompletionAdPending(activity)) return
        if (!pomodoroAdInFlight.compareAndSet(false, true)) return

        withAdsReady(
            activity = activity,
            onUnavailable = { message ->
                pomodoroAdInFlight.set(false)
                FocusGuardLogger.log("Ads", "Pomodoro aguardando anúncio: $message")
            },
            onReady = {
                if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    pomodoroAdInFlight.set(false)
                    return@withAdsReady
                }

                InterstitialAd.load(
                    AdRequest.Builder(BuildConfig.ADMOB_INTERSTITIAL_AD_UNIT_ID).build(),
                    object : AdLoadCallback<InterstitialAd> {
                        override fun onAdLoaded(ad: InterstitialAd) {
                            FocusGuardLogger.log("Ads", "Interstitial Pomodoro carregado")
                            if (activity.isFinishing || activity.isDestroyed ||
                                !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                            ) {
                                pomodoroAdInFlight.set(false)
                                return
                            }

                            if (!MonetizationStateStore.consumePomodoroCompletionAdPending(activity)) {
                                pomodoroAdInFlight.set(false)
                                return
                            }

                            var reservationRestored = false
                            fun restoreReservation() {
                                if (!reservationRestored) {
                                    reservationRestored = true
                                    MonetizationStateStore.restorePomodoroCompletionAdPending(activity)
                                }
                            }

                            ad.adEventCallback = object : InterstitialAdEventCallback {
                                override fun onAdDismissedFullScreenContent() {
                                    FocusGuardLogger.log("Ads", "Interstitial Pomodoro fechado")
                                    pomodoroAdInFlight.set(false)
                                }

                                override fun onAdFailedToShowFullScreenContent(
                                    fullScreenContentError: FullScreenContentError
                                ) {
                                    restoreReservation()
                                    pomodoroAdInFlight.set(false)
                                    FocusGuardLogger.log(
                                        "Ads",
                                        "Interstitial Pomodoro falhou ao exibir: $fullScreenContentError"
                                    )
                                }
                            }

                            runCatching { ad.show(activity) }
                                .onFailure { error ->
                                    restoreReservation()
                                    pomodoroAdInFlight.set(false)
                                    FocusGuardLogger.logError(
                                        "Ads",
                                        "Falha ao apresentar intersticial do Pomodoro",
                                        error
                                    )
                                }
                        }

                        override fun onAdFailedToLoad(adError: LoadAdError) {
                            pomodoroAdInFlight.set(false)
                            logLoadFailure("Interstitial Pomodoro", adError)
                        }
                    }
                )
            }
        )
    }
}
