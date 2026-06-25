# FocusGuard — Code Review & Organization Assessment

**Data:** 2026-06-25
**Branch analisada:** `main` (commit `e0a1c70`)
**Versão do app:** 2.3.1-nuclear (versionCode 8)
**Stack:** Kotlin 1.9.23 · AGP 8.1.2 · Compose BOM 2024.04.01 · compileSdk 34 · minSdk 21
**Tamanho:** 13.133 LOC em 58 arquivos `.kt`

---

## Veredito Sintético

> **O FocusGuard é funcionalmente ambicioso mas arquiteturalmente indisciplinado.**
>
> Tem plumbing de segurança real (Device Admin, EncryptedSharedPreferences, Accessibility Service, biometria), mas **sem separação de concerns, sem testes, sem DI, com dependências defasadas, i18n quebrada, código morto e 9 god-screens em Compose**. A documentação `.agents/` promete "qualidade nuclear" mas o código não entrega a disciplina de engenharia (testes, lint enforcement, arquitetura) que o manifesto exige.

**Scorecard:**

| Dimensão | Nota | Comentário |
|---|---|---|
| Funcionalidade | **B+** | Features ambiciosas e implementadas |
| Arquitetura | **D** | Sem padrão, sem ViewModel/Repository/DI |
| Testes | **F** | Zero testes; diretórios `test/` não existem |
| Build/CI | **C+** | CI existe mas só compila; sem lint/test/detekt |
| Segurança | **C+** | Crypto correta mas logger em pasta pública |
| i18n | **D** | 92% das strings não traduzidas fora do pt-BR |
| Organização de pacotes | **C** | `ui.compose.screens` com 23 arquivos; 5 single-file packages |
| Manutenibilidade | **D+** | 9 god-screens; mudanças em managers rippleiam por UI |

---

## 1. Inventário & Tamanhos

### Top 10 maiores arquivos (god class candidates)

| # | LOC | Arquivo |
|---|---|---|
| 1 | 741 | `ui/compose/screens/SessionsListScreen.kt` |
| 2 | 732 | `ui/compose/screens/PermissionsScreen.kt` |
| 3 | 687 | `service/BlockingAccessibilityService.kt` |
| 4 | 612 | `ui/compose/screens/PasswordManagementScreen.kt` |
| 5 | 526 | `ui/compose/screens/UsageStatsDashboardScreen.kt` |
| 6 | 455 | `ui/compose/screens/UsageLimitsComponents.kt` |
| 7 | 447 | `ui/compose/screens/PomodoroScreen.kt` |
| 8 | 429 | `admin/DeviceOwnerManager.kt` |
| 9 | 417 | `ui/compose/screens/LimitsSecurityScreen.kt` |
| 10 | 388 | `ui/compose/screens/UsageLimitsScreen.kt` |

**9 dos 10 maiores são telas Compose** — a lógica de negócio está concentrada na UI, não separada.

### Código morto / incompleto

| Arquivo | LOC | Situação |
|---|---|---|
| `ui/compose/screens/InsightsScreen.kt` | 56 | **Placeholder** com `// continua aqui...` — feature nunca implementada |
| `analytics/AdvancedUsageAnalytics.kt` | 222 | **Nunca referenciado** em nenhum outro arquivo |
| `utils/.ag-write-10e5d868e8c8463d92a5e80644ab9ec1.tmp` | ~200 | **Arquivo temporário** deixado por ferramenta de agente; quase cópia idêntica de `WebsiteBlocker.kt` |
| `ui/compose/screens/TimeAwareFinalConfigStep.kt` | 35 | Suspeito — verificar uso |

### Lixo na raiz do repo

10 arquivos Python/JSON de agent tooling que não fazem parte do app Android:

```
ag_toolkit.py            (184 KB!)
generate_plan.py
run_edits.py
scratch.py
plan.json
refine_blocker.json
update_blocker.json
update_blocker_simple.json
update_permissions_ux.json
update_a11y.json
security_hardening_plan.json
security_hardening_plan_v2.json
security_hardening.patch
```

**Ação:** Movidos para `.gitignore` neste PR.

---

## 2. Organização de Pacotes

| Pacote | Arquivos | LOC | Observação |
|---|---|---|---|
| `com.focusguard` (root) | 2 | 76 | OK |
| `com.focusguard.admin` | 2 | 510 | OK |
| `com.focusguard.analytics` | 1 | 222 | **Single-file package** + morto |
| `com.focusguard.data` | 1 | 79 | Single-file package |
| `com.focusguard.database` | 4 | 496 | OK |
| `com.focusguard.manager` | 3 | 661 | OK |
| `com.focusguard.receiver` | 2 | 159 | OK |
| `com.focusguard.security` | 2 | 449 | OK |
| `com.focusguard.service` | 2 | 973 | OK |
| `com.focusguard.ui` | 5 | 935 | Activities híbridas XML+Compose |
| `com.focusguard.ui.compose.screens` | **23** | **7.318** | **SUPERLOTADO — 56% do LOC total** |
| `com.focusguard.ui.compose.components.limits` | 2 | 275 | Apenas 2 componentes reutilizáveis no app todo |
| `com.focusguard.ui.compose.layout` | 1 | 115 | Single-file |
| `com.focusguard.ui.compose.navigation` | 1 | 225 | Single-file |
| `com.focusguard.ui.compose.theme` | 1 | 154 | Single-file |
| `com.focusguard.utils` | 5 | 486 | Concers misturados (logger + blocker + perms + prefs) |

### Problemas estruturais

1. **`ui.compose.screens` com 23 arquivos** — acima do limite saudável de 8. Deveria ser subdividido:
   ```
   ui/compose/screens/sessions/         (4 telas: list, detail, active, status sheet)
   ui/compose/screens/pomodoro/         (1 tela: PomodoroScreen)
   ui/compose/screens/permissions/      (3 telas: Permissions, FinalConfig, TimeAwareFinalConfig)
   ui/compose/screens/auth/             (2 telas: Auth, PasswordManagement)
   ui/compose/screens/limits/           (5 telas: UsageLimits, LimitsSecurity, UsageLimitsComponents, AppSelection, BlockCustomization)
   ui/compose/screens/insights/         (3 telas: Insights, UsageStatsDashboard, IntruderLog)
   ui/compose/screens/settings/         (2 telas: Settings, Language)
   ui/compose/screens/main/             (1 tela: MainScreen)
   ```

2. **5 single-file packages** — `analytics`, `data`, `layout`, `navigation`, `theme` — com 1 arquivo cada. Sinal de granularidade errada.

3. **`components/` com apenas 2 arquivos** — sinal de que componentes reutilizáveis não estão sendo extraídos; o contrário da sobre-utilização acima.

---

## 3. Arquitetura — **SEM PADRÃO FORMAL**

| Verificação | Resultado |
|---|---|
| MVVM / MVI / MVC / Clean Architecture | **Nenhum** |
| `ViewModel()` subclasses | **0** |
| `Repository` interfaces/classes | **0** |
| `UseCase` classes | **0** |
| DI (Hilt / Dagger / Koin / `@Inject`) | **Nenhum** — `kapt` usado só para Room |
| Separação UI ↔ Lógica de negócio | **Fraca** |

### Smells arquiteturais concretos

- `MainActivity.kt:38-40` constrói `DeviceOwnerManager`, `AuthManager`, `PomodoroManager` em `onCreate` e passa adiante como parâmetros pela `FocusGuardNavHost` — wiring manual.
- `FocusGuardNavHost.kt` é uma navegação custom feita à mão (não Jetpack Navigation), gerenciando estado de tela com `mutableStateOf` — frágil.
- `DeviceOwnerManager.kt` constrói `AlertDialog` e `Toast` — **UI vazando para camada de domínio**.
- **22 `scope.launch`** dentro de telas Compose + **56 `LaunchedEffect`/`rememberCoroutineScope`** — uso pesado de corrotinas na UI.
- **15 chamadas diretas** a `AppDatabase.getDatabase(context)` dentro de arquivos de UI.
- **11 chamadas diretas** a `BlockingSessionManager.getInstance(context).checkAndEnforce()` dentro de composables.

---

## 4. State Management

| Padrão | Contagem | Observação |
|---|---|---|
| `mutableStateOf` | **141** | Dominante |
| `LaunchedEffect` + `rememberCoroutineScope` | 56 | |
| `StateFlow` / `MutableStateFlow` | 6 | Apenas em `PomodoroManager.kt` |
| `LiveData` | 0 | OK (Compose-first) |
| `collectAsState` | poucos | Verificar abrangência |

### Anti-padrões

- `SessionsListScreen.kt` declara **11 `mutableStateOf`** no topo de uma única composable — deveria ser um `ViewModel` + `StateFlow`.
- **19 composables excedem 80 linhas**; a pior é `ActiveSessionsScreen()` com 205 linhas.
- **19 chamadas `withContext(Dispatchers.IO)`** dentro de `ui/compose/screens/` — boilerplate de IO thread na UI que deveria estar num Repository.
- `SessionsListScreen.kt:53` e `:498` instanciam managers em `remember{}` — singleton wiring manual por tela.

---

## 5. Code Smells Específicos

### Long Methods (>80 linhas, top 12)

| Arquivo:linha | Tamanho | Função |
|---|---|---|
| `ActiveSessionsScreen.kt:29` | 205 | `ActiveSessionsScreen()` |
| `AuthScreen.kt:47` | 192 | `AuthScreen()` |
| `AppSelectionScreen.kt:48` | 194 | `AppSelectionScreen()` |
| `BlockingSessionStatusSheet.kt:29` | 162 | `BlockingSessionStatusSheet()` |
| `PomodoroScreen.kt:84` | 157 | `PomodoroScreen()` |
| `UsageLimitsComponents.kt:155` | 151 | `AppLimitDialog()` |
| `SessionsListScreen.kt:583` | 150 | `SessionListItem()` |
| `TimeBlockSessionConfigScreen.kt:59` | 136 | `TimeBlockSessionConfigScreen()` |
| `SessionsListScreen.kt:48` | 136 | `SessionsListScreen()` |
| `PomodoroScreen.kt:244` | 135 | `StopwatchTimer()` |
| `AppSelectionScreen.kt:245` | 118 | `AppSelectionItem()` |
| `MainScreen.kt:35` | 100 | `MainScreen()` |

### God Objects (referências cruzadas)

| Classe | Referências | Concern |
|---|---|---|
| `FocusGuardLogger` | 124 | Logger — OK |
| `PomodoroManager` | 37 | Singleton |
| `AuthManager` | 37 | Instanciado por toda UI — sem DI |
| `BlockingSessionManager` | 32 | Singleton |
| `AppDatabase` | 30 | Acessado direto de composables 15× |
| `DeviceOwnerManager` | 24 | Singleton |
| `AdvancedUsageAnalytics` | **1** | **MORTO** |
| `SecurePrefsManager` | 2 | Verificar uso |

### Duplicação

- **36 `getInstance(`** — 5 singletons (`PomodoroManager`, `BlockingSessionManager`, `AuthManager` via construtor, `DeviceOwnerManager`, `AppDatabase`). Wiring manual em toda parte.
- `BlockingAccessibilityService.kt:97-101` **duplica** a lista de pacotes de competidores que também está em `data/PredefinedApps.kt:15-30` — duas fontes de verdade.
- `findEditTextWithUrl` aparece em 2 arquivos; `createNotificationChannel` em 2 arquivos — verificar.
- **19 `withContext(Dispatchers.IO)`** repetidos em telas — boilerplate de thread pool.

### TODO/FIXME

- **Zero** marcadores `TODO`, `FIXME`, `XXX`, `HACK`, `@Deprecated` no código todo — surpreendente para 13k LOC. Ou houve limpeza deliberada, ou não há disciplina de anotação.

---

## 6. Dependências — Defasadas

| Item | Atual | Recomendado | Impacto |
|---|---|---|---|
| `compileSdk` / `targetSdk` | 34 | **35** | Play Store exige targetSdk 35 para novos submissions |
| AGP | 8.1.2 | 8.7+ | Bug fixes, KSP support |
| Kotlin | 1.9.23 | **2.0.21+** (K2 compiler) | Performance, KSP nativo |
| Compose Compiler ext | 1.5.11 | Migrar para Compose Compiler Gradle Plugin (com Kotlin 2.0) | Bloqueia upgrade de Kotlin |
| Compose BOM | 2024.04.01 | 2024.10+ | Bug fixes, APIs novas |
| `core-ktx` | 1.12.0 | 1.13+ | |
| `appcompat` | 1.6.1 | 1.7+ | |
| `material` | 1.11.0 | 1.12+ | |
| `lifecycle-*` | 2.7.0 | 2.8+ | |
| `activity-compose` | 1.8.2 | 1.9+ | |
| CameraX | 1.3.1 | 1.4+ | |
| `security-crypto` | 1.1.0-alpha06 | Ainda alpha — risco em produção | |
| `kapt` | usado (Room) | Migrar para **KSP** | kapt deprecated, 2× mais lento |

### Version management

- **Não existe `gradle/libs.versions.toml`** — todas as versões hardcoded inline em `app/build.gradle`. Convenção moderna é usar version catalog.

### Test dependencies

- `junit:junit:4.13.2` e espresso declarados — porém **0 testes existem** (dirs `app/src/test/` e `app/src/androidTest/` não existem).

---

## 7. i18n — Quebrada

| Locale | Strings | Cobertura |
|---|---|---|
| `values` (default = pt-BR) | **394** | 100% |
| `values-ar` | 31 | 8% |
| `values-bn` | 31 | 8% |
| `values-en` | 28 | 7% |
| `values-es` | 28 | 7% |
| `values-fr` | 28 | 7% |
| `values-hi` | 28 | 7% |
| `values-in` | 28 | 7% |
| `values-ru` | 28 | 7% |
| `values-ur` | 28 | 7% |
| `values-zh` | 28 | 7% |

**~92% das strings caem para fallback português** em qualquer locale não-pt. A feature multi-idioma está largada.

### Strings hardcoded no código

- **39 ocorrências** de `Text("...")` com texto em português direto no código (em `FinalConfigStep.kt:70`, `LimitsSecurityScreen.kt:233`, `UsageLimitsComponents.kt:223`, etc.). Essas não podem ser traduzidas sem refatoração.

---

## 8. Build Config

| Setting | Valor | Risco |
|---|---|---|
| `lint.abortOnError` | true | Estrito mas frágil sem testes |
| `lint.checkReleaseBuilds` | true | OK |
| `minifyEnabled` (release) | **false** | **R8/ProGuard desativado — APK maior, sem ofuscação** |
| `proguard-rules.pro` | **NÃO EXISTIA** | Quebraria se `minifyEnabled` fosse ligado — **criado neste PR** |
| Signing release | Fallback silencioso para debug se env vars faltarem | Footgun: release debug-signed sem aviso |
| Build types | Apenas `debug` + `release` | OK para app single-variant |
| `kapt` | Usado (Room) | Migrar para KSP |

### Detekt — configurado mas **MORTO**

- Plugin `io.gitlab.arturbosch.detekt` 1.23.6 declarado no `build.gradle` raiz.
- `config/detekt/detekt.yml` existe com `maxIssues: 0` (estrito) e thresholds `LongMethod: 60`, `LargeClass: 600`.
- **Mas nenhuma task `detekt` está conectada ao `check` ou ao CI** — a config é inerte. O CI anterior rodava apenas `assembleDebug`.
- **Há 19 violações do threshold `LongMethod: 60`** só nas composables.

**Ação neste PR:** workflow novo roda `./gradlew detekt` com `continue-on-error: true` para começar a coletar violações sem quebrar o build.

---

## 9. Segurança — Pontos de Atenção

| Item | Status |
|---|---|
| API keys / secrets hardcoded | Nenhum (bom) |
| `EncryptedSharedPreferences` (AES256-GCM + AES256-SIV) | Correto |
| `AuthManager` com `MessageDigest` + `SecureRandom` | OK (verificar se é SHA-256) |
| `network_security_config.xml` com `cleartextTrafficPermitted="false"` | Bom |
| `System.exit` / `Runtime.exec` / reflection | **Zero** (bom) |
| **Logger escreve em `Environment.DIRECTORY_DOWNLOADS`** | **QUALQUER app com storage pode ler logs de comportamento do usuário** — violação de privacidade |
| `StrictPomodoroLock.kt` escreve timestamp em SharedPreferences **plaintext** | Inconsistente com "nuclear security" |
| `MainActivity.kt:35` escreve `launchAttemptCount` em prefs plaintext | Mesmo |
| `BlockingAccessibilityService.kt:203` — `http://www.google.com` hardcoded | Cleartext! Deveria ser `https` ou removido |
| Release builds **debug-signed** se `KEYSTORE_FILE` ausente | Fallback silencioso perigoso |

### Hardcoded competitor packages

`data/PredefinedApps.kt:15-55` lista 9+ pacotes (`com.instagram.android`, `com.facebook.katana`, `com.google.android.youtube`, `com.zhiliaoapp.musically`, `com.snapchat.android`, `com.reddit.frontpage`, `com.netflix.mediaclient`, `com.tencent.ig`).

`BlockingAccessibilityService.kt:97-101` **duplica** 4 desses pacotes numa lista separada.

`BlockingAccessibilityService.kt:484-486` tem lógica `isYouTubeContent()` especial-casing YouTube — **frágil**: se o YouTube mudar package name, o bloqueio quebra.

### Permissões redundantes

- `SCHEDULE_EXACT_ALARM` **e** `USE_EXACT_ALARM` declaradas — `USE_EXACT_ALARM` supersede na API 33+.
- `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` sem `maxSdkVersion="28"` — silenciosamente ignoradas em Android 10+ mas still flagged pela Play review.
- `QUERY_ALL_PACKAGES` — permissão de alta escrutinação pela Play Store, requer justificativa.

---

## 10. Branches — 9 candidatas a deleção

| Branch | Último commit | Situação |
|---|---|---|
| `main` | atual | Ativa |
| `backup/pomodoro-ui-before-tweaks` | 2026-05-06 | **Stale — deletar** |
| `fix/comprehensive-code-review` | 2026-03-15 | **Mais antiga, provável abandonada** |
| `fix/insights-crash` | 2026-05-01 | Stale |
| `fix/insights-crash-main` | 2026-05-01 | **Duplicata da anterior — deletar uma** |
| `fix/final-ui-pomodoro-review` | 2026-05-01 | Provável merged |
| `fix/robust-sessions-ui` | 2026-05-01 | Provável merged |
| `fix/ui-review-issues` | 2026-05-01 | Provável merged |
| `agent/ui-ux-corrections` | 2026-05-01 | Prefixo `agent/` sugere scratch |
| `feat/custom-pomodoro-time` | 2026-05-01 | Provável merged |
| `ui/compact-header-menu` | 2026-05-01 | Provável merged |

**Ação recomendada:** `git branch -d` das merged; `git push origin --delete` das stale; documentar política de ciclo de vida de branch.

---

## 11. Documentação `.agents/`

| Arquivo | Situação |
|---|---|
| `nuclear_standards.md` | Manifesto de qualidade. Exige "Modularização: Separe UI de Lógica de Negócio (Managers/ViewModels)" — **VIOLADO**: zero ViewModels, 19 composables com lógica inline. |
| `project_map.md` | Auto-gerado. Diz "Padrões de Design: Definir padrões aqui..." — **placeholder nunca preenchido**. |
| `token_savings.md` | 4 linhas genéricas. |
| `universal_instructions.md` | Prompt genérico para IAs. |
| `workflows/new_feature.md` | Workflow de 5 passos. **Passo 2 diz "Implemente ou atualize um Manager"** — sem menção a ViewModel/Repository. O workflow **codifica o anti-padrão arquitetural**. |
| `workflows/github_actions_android.md` | Tutorial genérico de CI. |

### README inconsistente

- Afirma **"Persistência: Room, DataStore"** — `DataStore` tem **0 referências** no codebase. Apenas Room + SharedPreferences + EncryptedSharedPreferences são usados.
- Diz para abrir no "Android Studio Koala ou superior" — Koala = 2024.1, daria warnings sobre Kotlin 1.9.23 / AGP 8.1.2 antigos.
- README em português apenas — irônico dado o gap de i18n.

---

## 12. Top 10 Prioridades (Ranqueadas por Impacto)

| # | Issue | Esforço | Impacto |
|---|---|---|---|
| **1** | **Sem arquitetura + sem DI**: zero ViewModels, zero Repositories, zero UseCases, zero DI. Composables chamam managers e DAOs diretamente (15 DB sites + 11 manager sites na UI). Mudança em manager signature rippleia por UI. | Alto | Crítico — bloqueia evolução sustentável |
| **2** | **Zero testes** num produto de segurança/bloqueio que usa Device Admin + Accessibility. Diretórios `test/` nem existem. O "nuclear quality" éunenforceable. | Médio | Crítico — produto de segurança sem testes é risco |
| **3** | **Release com `minifyEnabled false`** + `proguard-rules.pro` inexistente (criado neste PR). APK maior, sem ofuscação, sem dead-code elimination. | Baixo | Alto — segurança + performance |
| **4** | **9 god-screens Compose**: `SessionsListScreen.kt` com 741 LOC, 11 `mutableStateOf`, chamadas DB inline. | Alto | Alto — manutenibilidade |
| **5** | **i18n quebrada**: 394 strings em pt-BR vs 28-31 nos outros 10 locales. + 39 strings hardcoded em código. | Médio | Alto — UX global |
| **6** | **Toolchain defasado bloqueia upgrades**: Kotlin 1.9.23 + Compose Compiler 1.5.11 + AGP 8.1.2 + compileSdk 34. Não pode usar K2, KSP, latest Compose, ou submeter na Play com targetSdk 35. | Médio | Alto — débito cresce |
| **7** | **Logger escreve em pasta pública Downloads** — qualquer app lê logs de comportamento do usuário. Violação de privacidade para app de bloqueio. | Baixo | Alto — privacidade |
| **8** | **Código morto/incompleto em produção**: `InsightsScreen.kt` placeholder, `AdvancedUsageAnalytics.kt` (222 LOC) sem referências, `.ag-write-*.tmp` no source tree, 10 arquivos Python na raiz. | Baixo | Médio — poluição |
| **9** | **9 branches git stale** incluindo `backup/`, 2 duplicatas `fix/insights-crash*`, e a mais antiga de 2026-03-15. | Baixo | Médio — higiene |
| **10** | **Detekt configurado mas não enforced**: `detekt.yml` com `maxIssues: 0` mas sem task wired ao check/CI. Config inerte. **(Parcialmente corrigido neste PR — CI agora roda detekt com `continue-on-error`)** | Baixo | Médio — disciplina |

### Como os top 3 se conectam

A ausência de arquitetura (#1) é a causa raiz de #4 (god-screens — sem ViewModel, a UI acumula lógica), #2 (sem separação, testes unitários são impossíveis) e #8 (código morto acumula porque não há estrutura clara onde features deveriam estar). **Resolver #1 desbloqueia #2 e #4.**

---

## 13. Plano de Ação Sugerido

### Fase 1 — Higiene rápida (1-2 dias, low-risk)

- [x] Criar `proguard-rules.pro` (feito neste PR)
- [x] Expandir `.gitignore` para agent tooling (feito neste PR)
- [x] Workflow CI com lint + detekt + testes (feito neste PR, com `continue-on-error`)
- [ ] Deletar branches stale (9 candidatas)
- [ ] Remover arquivos Python da raiz (`.gitignore` agora os ignora, mas ainda estão tracked — `git rm --cached`)
- [ ] Deletar `.ag-write-*.tmp` do source tree
- [ ] Deletar `AdvancedUsageAnalytics.kt` (morto)
- [ ] Implementar ou deletar `InsightsScreen.kt` (placeholder)
- [ ] Corrigir logger: mover de Downloads para `context.filesDir`
- [ ] README: remover menção a DataStore (inexistente)

### Fase 2 — Modernização do toolchain (3-5 dias)

- [ ] Migrar `kapt` → `ksp` (Room)
- [ ] Upgrade Kotlin 1.9.23 → 2.0.21 + Compose Compiler Gradle Plugin
- [ ] Upgrade AGP 8.1.2 → 8.7, compileSdk 34 → 35
- [ ] Criar `gradle/libs.versions.toml` (version catalog)
- [ ] Ligar `minifyEnabled true` em release + validar `proguard-rules.pro`
- [ ] Adicionar `MissingTranslation` lint enforcement (depois de completar i18n)

### Fase 3 — Arquitetura (1-2 semanas)

- [ ] Adicionar Hilt para DI
- [ ] Criar `ViewModel` por tela (começar pelas top-5 god-screens)
- [ ] Criar camada `Repository` que encapsula `AppDatabase`
- [ ] Migrar lógica de negócio das composables para ViewModels
- [ ] Adicionar testes unitários para `AuthManager`, `BlockingSessionManager`, `StrictPomodoroLock`, `WebsiteBlocker`
- [ ] Subdividir `ui.compose.screens` em subpastas por feature (sessions, pomodoro, auth, etc.)
- [ ] Unificar lista de pacotes de competidores (uma fonte em `PredefinedApps.kt`)

### Fase 4 — i18n (3-5 dias)

- [ ] Audit `strings.xml` vs `values-*` — completar traduções faltantes
- [ ] Extrair 39 strings hardcoded para `strings.xml`
- [ ] Adicionar `lint` `MissingTranslation` como blocking no CI

---

## 14. O que este PR entrega

| Arquivo | Mudança |
|---|---|
| `.github/workflows/android-ci.yml` | **Reescrito** — agora 3 jobs paralelos: `static-analysis` (detekt + lint), `unit-tests` (placeholder para quando houver testes), `build` (debug + release condicional, com summary no GitHub). |
| `app/proguard-rules.pro` | **Criado** — regras para Room, Compose, CameraX, Coil, MPAndroidChart, Biometric, security-crypto, classes do FocusGuard. |
| `.gitignore` | **Reescrito** — adicionadas entradas para `.ag-write-*.tmp`, `*.ag.tmp`, e os 13 arquivos Python/JSON de agent tooling que poluem a raiz. |
| `docs/CODE_REVIEW.md` | **Criado** — este documento. |

### Próximos passos imediatos

1. **Aceitar este PR** — só melhora CI/segurança/higiene sem risco funcional
2. **Configurar secrets de release** (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) no GitHub para o job de release começar a rodar
3. **Decidir qual das Fases 1-4 atacar primeiro** — sugiro Fase 1 (higiene) + Fase 2 (toolchain) antes de Fase 3 (arquitetura)
4. **Deletar branches stale** assim que este PR for merged

---

*Documento gerado em 2026-06-25 por análise automatizada do codebase no commit `e0a1c70` da branch `main`.*
