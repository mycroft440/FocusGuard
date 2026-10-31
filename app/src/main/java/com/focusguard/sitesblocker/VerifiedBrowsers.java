package com.focusguard.sitesblocker;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Registra em que versão de cada navegador o método da família achou a barra de endereço.
 *
 * É a rede de segurança para um navegador suportado que deixa de ser lido, como o Mi Browser 14
 * depois de mudar a barra: a cada versão, a barra precisa ser achada ao menos uma vez com uma
 * página na tela, ou o navegador é fechado. A versão junta a data da última instalação ou
 * atualização do navegador e a do próprio app (BrowserVersions), porque qualquer uma das duas pode
 * mudar a barra ou o método que a lê.
 */
final class VerifiedBrowsers {
    private static final String VERIFIED_PREFS_NAME = "verified_browsers";
    private static final String FAILED_PREFS_NAME = "unreadable_browsers";

    private final Context context;
    private final BrowserVersions versions;

    VerifiedBrowsers(Context context) {
        this(context, new BrowserVersions(context));
    }

    VerifiedBrowsers(Context context, BrowserVersions versions) {
        this.context = context.getApplicationContext();
        this.versions = versions;
    }

    /** Se a barra já foi achada na versão instalada do navegador. */
    boolean isVerified(String packageName) {
        String version = versions.versionOf(packageName);
        // Sem a versão não há o que comparar; a rede de segurança não age.
        if (version == null) return true;
        return version.equals(prefs(VERIFIED_PREFS_NAME).getString(packageName, null));
    }

    /** Se a barra não foi achada na versão instalada e o navegador foi fechado. */
    boolean hasFailed(String packageName) {
        String version = versions.versionOf(packageName);
        return version != null
                && version.equals(prefs(FAILED_PREFS_NAME).getString(packageName, null));
    }

    void markVerified(String packageName) {
        String version = versions.versionOf(packageName);
        if (version == null) return;
        prefs(VERIFIED_PREFS_NAME).edit().putString(packageName, version).apply();
        prefs(FAILED_PREFS_NAME).edit().remove(packageName).apply();
    }

    void markFailed(String packageName) {
        String version = versions.versionOf(packageName);
        if (version == null) return;
        prefs(FAILED_PREFS_NAME).edit().putString(packageName, version).apply();
    }

    static String version(long browserUpdatedAt, long appUpdatedAt) {
        return BrowserVersions.version(browserUpdatedAt, appUpdatedAt);
    }

    private SharedPreferences prefs(String name) {
        return context.getSharedPreferences(name, Context.MODE_PRIVATE);
    }
}
