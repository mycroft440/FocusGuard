package com.focusguard.sitesblocker;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Lista em cache dos navegadores que não se encaixaram em nenhuma família e foram bloqueados.
 *
 * Um navegador da lista é fechado na próxima abertura assim que a janela dele fica ativa, sem
 * testar as famílias de novo e sem o prazo da primeira recusa. A lista fica em memória enquanto o
 * serviço roda (a consulta a cada evento não lê o disco) e salva para as próximas inicializações.
 *
 * Cada navegador fica guardado com a versão em que foi recusado (BrowserVersions). Depois de uma
 * atualização dele ou do app, ele volta a ser testado uma vez: a nova versão pode passar a mostrar
 * a URL na barra, ou o app pode ter ganhado uma família que a lê.
 */
final class RejectedBrowsers {
    static final String PREFS_NAME = "rejected_browsers";
    // Versão ainda não conhecida: a recusa adota a versão instalada na primeira consulta. Vale para
    // os registros do formato anterior (só "recusado", sem versão) e para uma recusa feita quando a
    // versão não pôde ser lida.
    static final String UNKNOWN_VERSION = "";

    private final SharedPreferences prefs;
    private final Function<String, String> versionOf;
    private final Map<String, String> rejected = new HashMap<>();

    RejectedBrowsers(Context context, BrowserVersions versions) {
        this(context, versions::versionOf);
    }

    RejectedBrowsers(Context context, Function<String, String> versionOf) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.versionOf = versionOf;
        load();
    }

    private void load() {
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) {
                rejected.put(entry.getKey(), (String) value);
            } else if (Boolean.TRUE.equals(value)) {
                rejected.put(entry.getKey(), UNKNOWN_VERSION);
            }
        }
    }

    /**
     * Se o navegador foi recusado na versão instalada. Uma recusa de outra versão sai da lista e o
     * navegador volta a ser testado.
     */
    boolean isRejected(String packageName) {
        if (packageName == null) return false;

        String rejectedVersion = rejected.get(packageName);
        if (rejectedVersion == null) return false;

        String installedVersion = versionOf.apply(packageName);
        // Sem a versão instalada não há como saber se ela mudou: a recusa continua valendo.
        if (installedVersion == null || installedVersion.equals(rejectedVersion)) return true;

        if (UNKNOWN_VERSION.equals(rejectedVersion)) {
            save(packageName, installedVersion);
            return true;
        }

        forget(packageName);
        return false;
    }

    /** Guarda o navegador como recusado na versão instalada. */
    void markRejected(String packageName) {
        if (packageName == null) return;

        String version = versionOf.apply(packageName);
        save(packageName, version == null ? UNKNOWN_VERSION : version);
    }

    /** Tira o navegador da lista (uma família se encaixou nele). */
    void forget(String packageName) {
        if (packageName == null || rejected.remove(packageName) == null) return;
        prefs.edit().remove(packageName).apply();
    }

    private void save(String packageName, String version) {
        if (version.equals(rejected.get(packageName))) return;
        rejected.put(packageName, version);
        prefs.edit().putString(packageName, version).apply();
    }
}
