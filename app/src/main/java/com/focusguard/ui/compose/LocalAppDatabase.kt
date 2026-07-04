package com.focusguard.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.focusguard.database.AppDatabase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Hilt EntryPoint para acesso ao AppDatabase de fora de componentes Hilt.
 *
 * P2-1 a P2-4: Substitui chamadas diretas a `AppDatabase.getDatabase(context)`
 * em Composables. Embora `AppDatabase.getDatabase` agora delegue para o mesmo
 * singleton (corrigido em P0-1), esta abordagem é mais limpa arquiteturalmente:
 *
 *  - Deixa explícito que a UI está acessando o DB via DI (não bypass)
 *  - Permite mock em testes instrumentados (substituir AppDatabase real por fake)
 *  - Centraliza a dependência em um único lugar — se futuramente migrarmos
 *    para Repository pattern, basta mudar esta função
 *
 * Uso:
 * ```kotlin
 * @Composable
 * fun MyScreen() {
 *     val db = rememberAppDatabase()
 *     val dao = db.appPasswordDao()
 *     // ...
 * }
 * ```
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppDatabaseEntryPoint {
    fun appDatabase(): AppDatabase
}

/**
 * Recupera a instância singleton de [AppDatabase] via Hilt EntryPoint.
 *
 * Usa `remember` para evitar chamadas repetidas a `EntryPointAccessors`
 * (que faz lookup em mapa interno) em recomposições.
 *
 * Em cenários onde Hilt não está inicializado (extremamente raro — apenas
 * em testes unitários sem Robolectric), cai para `AppDatabase.getDatabase`
 * legado.
 */
@Composable
fun rememberAppDatabase(): AppDatabase {
    val context = LocalContext.current
    return remember(context) {
        try {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                AppDatabaseEntryPoint::class.java
            )
            entryPoint.appDatabase()
        } catch (_: Throwable) {
            // Fallback para callers em teste ou cenários edge onde Hilt
            // não está inicializado — delega para o singleton legado.
            AppDatabase.getDatabase(context)
        }
    }
}
