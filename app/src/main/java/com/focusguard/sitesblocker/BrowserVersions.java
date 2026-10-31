package com.focusguard.sitesblocker;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;

import java.util.HashMap;
import java.util.Map;

/**
 * Versão de cada navegador para as listas salvas por versão (VerifiedBrowsers e RejectedBrowsers).
 *
 * A versão junta a data da última instalação ou atualização do navegador e a do próprio app,
 * porque qualquer uma das duas pode mudar a barra ou o método que a lê.
 */
final class BrowserVersions {
    // O navegador pode ser atualizado com o serviço rodando. A versão é consultada de novo após
    // esse intervalo, sem uma chamada ao PackageManager a cada evento.
    private static final long VERSION_CACHE_MS = 30_000L;

    private final Context context;
    private final Map<String, String> versions = new HashMap<>();
    private final Map<String, Long> versionsReadAt = new HashMap<>();

    BrowserVersions(Context context) {
        this.context = context.getApplicationContext();
    }

    static String version(long browserUpdatedAt, long appUpdatedAt) {
        return browserUpdatedAt + "/" + appUpdatedAt;
    }

    /** Versão instalada do navegador, ou null se ela não pôde ser lida. */
    String versionOf(String packageName) {
        if (packageName == null) return null;

        long now = SystemClock.elapsedRealtime();
        Long readAt = versionsReadAt.get(packageName);
        if (readAt != null && now - readAt < VERSION_CACHE_MS) return versions.get(packageName);

        String version = readVersion(packageName);
        versions.put(packageName, version);
        versionsReadAt.put(packageName, now);
        return version;
    }

    private String readVersion(String packageName) {
        PackageInfo browser = packageInfo(packageName);
        PackageInfo app = packageInfo(context.getPackageName());
        if (browser == null || app == null) return null;
        return version(browser.lastUpdateTime, app.lastUpdateTime);
    }

    private PackageInfo packageInfo(String packageName) {
        PackageManager packageManager = context.getPackageManager();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return packageManager.getPackageInfo(
                        packageName,
                        PackageManager.PackageInfoFlags.of(0)
                );
            }
            return packageManager.getPackageInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }
}
