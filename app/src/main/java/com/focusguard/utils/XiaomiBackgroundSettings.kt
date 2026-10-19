package com.focusguard.utils

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.focusguard.R
import java.util.Locale

/**
 * Restrições próprias de segundo plano da Xiaomi (MIUI e HyperOS, em aparelhos Xiaomi, Redmi
 * e POCO). Além da otimização de bateria do Android, que a tela de permissões já pede, o sistema
 * tem o "Início automático" e a "Economia de bateria" de cada app: sem liberar os dois, o serviço
 * de acessibilidade atrasa ou para com o app fechado, e o bloqueio chega tarde.
 *
 * O Android não informa se esses dois estão liberados, então os atalhos só abrem as telas.
 */
object XiaomiBackgroundSettings {
    private val XIAOMI_BRANDS = listOf("xiaomi", "redmi", "poco")

    // Apps do sistema da MIUI e do HyperOS (declarados em <queries> no manifesto).
    private const val SECURITY_CENTER = "com.miui.securitycenter"
    private const val POWER_KEEPER = "com.miui.powerkeeper"

    /**
     * Aparelho Xiaomi, Redmi ou POCO, ou qualquer aparelho com a MIUI ou o HyperOS (que trazem
     * os apps do sistema da Xiaomi, mesmo com outro fabricante informado).
     */
    fun isXiaomiSystem(context: Context): Boolean =
        isXiaomiDevice(Build.MANUFACTURER, Build.BRAND) ||
            isInstalled(context, SECURITY_CENTER) ||
            isInstalled(context, POWER_KEEPER)

    /** POCO e Redmi costumam informar o fabricante "Xiaomi" e a marca própria. */
    internal fun isXiaomiDevice(manufacturer: String?, brand: String?): Boolean =
        listOf(manufacturer, brand).any { value ->
            val name = value.orEmpty().lowercase(Locale.ROOT)
            XIAOMI_BRANDS.any(name::contains)
        }

    /** Tela de início automático; sem ela nesta versão do sistema, os detalhes do app. */
    fun openAutoStart(context: Context) {
        val autoStart = Intent().setComponent(
            ComponentName(SECURITY_CENTER, "com.miui.permcenter.autostart.AutoStartManagementActivity")
        )
        if (start(context, autoStart)) return
        if (start(context, Intent("miui.intent.action.OP_AUTO_START"))) return
        openAppDetails(context)
    }

    /**
     * Economia de bateria do app ("Sem restrições"); sem ela, os detalhes do app, que na MIUI e
     * no HyperOS também trazem a economia de bateria.
     */
    fun openBatterySaver(context: Context) {
        val battery = Intent()
            .setComponent(ComponentName(POWER_KEEPER, "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
            .putExtra("package_name", context.packageName)
            .putExtra("package_label", context.getString(R.string.app_name))
        if (start(context, battery)) return
        openAppDetails(context)
    }

    private fun openAppDetails(context: Context) {
        start(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
        )
    }

    private fun start(context: Context, intent: Intent): Boolean {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: RuntimeException) {
            // Tela inexistente nesta versão do sistema (ActivityNotFoundException) ou protegida
            // (SecurityException): tenta a próxima.
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun isInstalled(context: Context, packageName: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
        } else {
            context.packageManager.getPackageInfo(packageName, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
