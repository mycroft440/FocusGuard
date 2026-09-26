package com.focusguard.monetization

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.focusguard.utils.FocusGuardLogger
import java.text.Normalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Modo Premium: sem anúncios e sem anúncios recompensados para liberar funções.
 *
 * É ativado pela compra do app no Google Play (produto único [PRODUCT_ID]) ou por um
 * código promocional. O estado fica salvo no aparelho; a compra também é restaurada
 * a cada abertura do app.
 */
object PremiumManager {
    const val PRODUCT_ID = "hardblock_premium"

    private const val PREFS = "premium_mode"
    private const val KEY_ACTIVE = "active"
    private const val KEY_SOURCE = "source"
    private const val SOURCE_PROMO = "promo"
    private const val SOURCE_PURCHASE = "purchase"

    // Códigos aceitos, já normalizados (minúsculas, sem acento e sem espaços).
    private val PROMO_CODES = setOf("josegustavo34")

    private val premium = MutableStateFlow(false)
    val isPremiumFlow: StateFlow<Boolean> = premium.asStateFlow()

    @Volatile private var loaded = false
    private var billingClient: BillingClient? = null
    private var appContext: Context? = null
    private var purchaseListener: ((PurchaseResult) -> Unit)? = null

    enum class PurchaseResult { ACTIVATED, CANCELLED, UNAVAILABLE, PENDING }

    fun isPremium(context: Context): Boolean {
        ensureLoaded(context)
        return premium.value
    }

    /** Carrega o estado salvo e confere, em segundo plano, se há compra no Google Play. */
    fun initialize(context: Context) {
        ensureLoaded(context)
        connect(context) { client -> restorePurchases(client) }
    }

    /** Retorna true se o código é válido e o Premium foi ativado. */
    fun redeemPromoCode(context: Context, rawCode: String): Boolean {
        if (normalizeCode(rawCode) !in PROMO_CODES) return false
        activate(context, SOURCE_PROMO)
        return true
    }

    fun purchase(activity: Activity, onResult: (PurchaseResult) -> Unit) {
        purchaseListener = onResult
        connect(activity) { client ->
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(
                    listOf(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(PRODUCT_ID)
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build()
                    )
                )
                .build()
            client.queryProductDetailsAsync(params) { result, details ->
                val product = details.firstOrNull()
                if (result.responseCode != BillingClient.BillingResponseCode.OK ||
                    product == null
                ) {
                    finishPurchase(PurchaseResult.UNAVAILABLE)
                    return@queryProductDetailsAsync
                }
                val flow = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(
                        listOf(
                            BillingFlowParams.ProductDetailsParams.newBuilder()
                                .setProductDetails(product)
                                .build()
                        )
                    )
                    .build()
                activity.runOnUiThread {
                    val launch = client.launchBillingFlow(activity, flow)
                    if (launch.responseCode != BillingClient.BillingResponseCode.OK) {
                        finishPurchase(PurchaseResult.UNAVAILABLE)
                    }
                }
            }
        }
    }

    internal fun normalizeCode(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("\\s+"), "")
            .lowercase()

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        premium.value = prefs(context).getBoolean(KEY_ACTIVE, false)
        loaded = true
    }

    private fun activate(context: Context, source: String) {
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_SOURCE, source)
            .apply()
        loaded = true
        premium.value = true
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val purchasesUpdated = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val handled = purchases.orEmpty().map(::handlePurchase)
                finishPurchase(
                    when {
                        PurchaseResult.ACTIVATED in handled -> PurchaseResult.ACTIVATED
                        PurchaseResult.PENDING in handled -> PurchaseResult.PENDING
                        else -> PurchaseResult.UNAVAILABLE
                    }
                )
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                billingClient?.let(::restorePurchases)
                finishPurchase(PurchaseResult.ACTIVATED)
            }
            BillingClient.BillingResponseCode.USER_CANCELED ->
                finishPurchase(PurchaseResult.CANCELLED)
            else -> finishPurchase(PurchaseResult.UNAVAILABLE)
        }
    }

    private fun handlePurchase(purchase: Purchase): PurchaseResult {
        if (PRODUCT_ID !in purchase.products) return PurchaseResult.UNAVAILABLE
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
            return PurchaseResult.PENDING
        }
        appContext?.let { activate(it, SOURCE_PURCHASE) }
        if (!purchase.isAcknowledged) {
            billingClient?.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
            ) { ack ->
                if (ack.responseCode != BillingClient.BillingResponseCode.OK) {
                    FocusGuardLogger.log("Premium", "Falha ao confirmar compra: ${ack.debugMessage}")
                }
            }
        }
        return PurchaseResult.ACTIVATED
    }

    private fun restorePurchases(client: BillingClient) {
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                purchases.forEach(::handlePurchase)
            }
        }
    }

    private fun finishPurchase(result: PurchaseResult) {
        val listener = purchaseListener ?: return
        purchaseListener = null
        listener(result)
    }

    private fun connect(context: Context, onReady: (BillingClient) -> Unit) {
        appContext = context.applicationContext
        val client = billingClient ?: runCatching {
            BillingClient.newBuilder(context.applicationContext)
                .setListener(purchasesUpdated)
                .enablePendingPurchases(
                    PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
                )
                .build()
        }.getOrNull()?.also { billingClient = it }

        if (client == null) {
            finishPurchase(PurchaseResult.UNAVAILABLE)
            return
        }
        if (client.isReady) {
            onReady(client)
            return
        }
        runCatching {
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        onReady(client)
                    } else {
                        finishPurchase(PurchaseResult.UNAVAILABLE)
                    }
                }

                override fun onBillingServiceDisconnected() = Unit
            })
        }.onFailure { finishPurchase(PurchaseResult.UNAVAILABLE) }
    }
}
