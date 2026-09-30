package com.focusguard.utils

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Nomes de apps para as telas de bloqueio, guardados no processo.
 *
 * Resolver o nome é uma chamada ao PackageManager que carrega recursos do outro app
 * (2–20 ms). A tela de senha fazia isso na thread principal antes do primeiro
 * quadro; o serviço agora pré-carrega os apps protegidos fora da main.
 */
object AppLabelCache {
    private val labels = ConcurrentHashMap<String, String>()

    // Apps sem nome (não instalados): guardados por um minuto, para não voltar ao
    // PackageManager a cada tela. Instalar o app também passa por invalidate().
    private val misses = ConcurrentHashMap<String, Long>()
    private const val MISS_TTL_NANOS = 60_000_000_000L

    private fun isRecentMiss(packageName: String): Boolean {
        val at = misses[packageName] ?: return false
        if (System.nanoTime() - at < MISS_TTL_NANOS) return true
        misses.remove(packageName)
        return false
    }

    fun get(context: Context, packageName: String?): String? {
        val target = packageName?.takeIf(String::isNotBlank) ?: return null
        labels[target]?.let { return it }
        if (isRecentMiss(target)) return null
        return load(context, target)
    }

    fun prewarm(context: Context, packageNames: Collection<String>) {
        packageNames.forEach { packageName ->
            if (packageName.isNotBlank() &&
                !labels.containsKey(packageName) &&
                !isRecentMiss(packageName)
            ) load(context, packageName)
        }
    }

    /** Um app foi instalado, atualizado ou removido: o nome pode ter mudado. */
    fun invalidate(packageName: String?) {
        if (packageName.isNullOrBlank()) {
            labels.clear()
            misses.clear()
        } else {
            labels.remove(packageName)
            misses.remove(packageName)
        }
    }

    private fun load(context: Context, packageName: String): String? {
        val packageManager = context.applicationContext.packageManager
        return runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrNull()
            ?.takeIf(String::isNotBlank)
            .also { label ->
                if (label != null) labels[packageName] = label else misses[packageName] = System.nanoTime()
            }
    }
}
