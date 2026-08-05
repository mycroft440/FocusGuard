# FocusGuard — Code Review & Organization Assessment

**Data:** 2026-08-05
**Branch analisada:** `main` (commit `937f74d`)
**Versão do app:** 2.5.0 (versionCode 10)
**Stack:** Kotlin 2.0.21 · AGP 8.7.2 · Gradle 8.10.2 · Hilt 2.52 · Room 2.6.1 (KSP) · compileSdk 35 · minSdk 26
**Tamanho:** 22.030 LOC em 93 arquivos `.kt` (main) + 3.039 LOC em 31 arquivos de teste

> **Este documento substitui a revisão de 2026-06-25, que ficou factualmente
> obsoleta.** As afirmações antigas de "zero testes / nota F", "sem DI", "sem
> ViewModel/Repository", "`minifyEnabled false`", "logger em pasta pública
> Downloads", "não existe `libs.versions.toml`" e "Kotlin 1.9.23 / AGP 8.1.2 /
> compileSdk 34" **não valem mais** — todas foram resolvidas. Manter aquele texto
> no repositório desinformava quem chegasse ao projeto.

---

## Veredito Sintético

> **A disciplina de engenharia subiu muito. O que ficou atrás é a camada de UI
> e o enforcement automatizado.**
>
> A camada de segurança — o núcleo do produto — está genuinamente bem testada:
> 12 arquivos em `security/` cobertos por 11 arquivos de teste, 246 testes
> unitários no total. Migrações de Room testadas, cálculo de agendamento com
> teste próprio, Hilt montado, R8 ligado, logger em armazenamento privado.
>
> A dívida remanescente é concentrada e nomeável: telas Compose gigantes, o
> serviço de acessibilidade subtestado, e ferramentas de qualidade instaladas
> mas desligadas.

**Scorecard:**

| Dimensão | Nota | Comentário |
|---|---|---|
| Funcionalidade | **A−** | Escopo ambicioso e efetivamente implementado |
| Arquitetura | **B−** | Hilt + ViewModel + Repository presentes; lógica ainda vaza para telas |
| Testes | **B** | 246 testes unitários; `security/` bem coberto, `service/` não |
| Build/CI | **C+** | CI roda lint + testes + build; detekt existe mas nunca executa |
| Segurança | **B+** | Crypto correta, R8 ativo, manifest enxuto, logs privados |
| i18n | **B−** | pt-BR + en completos e travados por lint; 28 strings ainda hardcoded no código |
| Organização de pacotes | **C−** | `ui.compose.screens` com 31 arquivos; 4 single-file packages |
| Manutenibilidade | **C** | 5 arquivos acima de 900 LOC concentram o risco |

---

## 1. Inventário & Tamanhos

| Fonte | Arquivos | LOC |
|---|---|---|
| `app/src/main` | 93 | 22.030 |
| `app/src/test` | 31 | 3.039 |
| `app/src/androidTest` | 1 | 29 |

### Top 10 maiores arquivos

| # | LOC | Arquivo |
|---|---|---|
| 1 | 1.403 | `service/BlockingAccessibilityService.kt` |
| 2 | 1.319 | `ui/compose/screens/ProtectionSetupScreen.kt` |
| 3 | 1.122 | `admin/DeviceOwnerManager.kt` |
| 4 | 1.061 | `manager/BlockingSessionManager.kt` |
| 5 | 911 | `utils/WebsiteBlocker.kt` |
| 6 | 849 | `ui/compose/screens/PermissionsScreen.kt` |
| 7 | 759 | `ui/compose/screens/SessionsListScreen.kt` |
| 8 | 621 | `ui/compose/screens/UsageLimitsComponents.kt` |
| 9 | 588 | `ui/compose/screens/PasswordManagementScreen.kt` |
| 10 | 565 | `ui/compose/screens/UsageStatsDashboardScreen.kt` |

`BlockingAccessibilityService.kt` **dobrou** desde a revisão anterior (687 →
1.403 LOC) e segue como o arquivo mais crítico do produto.

---

## 2. Cobertura de Testes

246 testes unitários (`@Test`) em 31 arquivos, mais 1 instrumentado. A
distribuição é desigual de um jeito que importa:

| Pacote | Arquivos main | Arquivos de teste | Leitura |
|---|---|---|---|
| `security` | 12 | 11 | Excelente — é o núcleo e está coberto |
| `utils` | 8 | 5 | Bom |
| `admin` | 4 | 3 | Bom |
| `repository` | 2 | 2 | Bom |
| `service` | **2 (2.376 LOC)** | **1** | **Lacuna crítica** |
| `database` | 4 | 1 | Migrações cobertas |
| `receiver` | 5 | 1 | Fraco |
| `ui/compose/screens` | 31 | 2 | Esperado para Compose |
| `analytics` | 1 | 0 | Sem cobertura |
| `di` | 3 | 0 | Aceitável (wiring) |

**A lacuna concreta:** `service/` tem 2.376 LOC — o `BlockingAccessibilityService`,
que decide em tempo real se um app ou site é bloqueado, e o
`PomodoroForegroundService`. Existe apenas `WebsiteBlockNavigationTest`. Uma
regressão nesse caminho quebra a proposta central do app e nenhum teste
avisaria.

O único teste instrumentado (`DeviceOwnerProtectionInstrumentedTest`) é
adequado: Device Owner só é verificável em aparelho real.

---

## 3. Portões de qualidade instalados mas desligados

Este é o padrão mais recorrente do projeto: a ferramenta é instalada, o arquivo
de configuração é escrito, e o enforcement nunca liga.

### 3.1 detekt não analisa nada

`build.gradle.kts:11` aplica `alias(libs.plugins.detekt)` **no projeto raiz**,
que não contém fonte Kotlin. Não existe bloco `detekt {}` em nenhum arquivo, o
módulo `app` nunca aplica o plugin, e `config/detekt/detekt.yml` — com
`maxIssues: 0` — não é referenciado por nada. O CI nunca invoca a task.

Resultado: **zero arquivos analisados, zero issues reportadas**, com a aparência
de um projeto que tem static analysis.

**Correção:** aplicar o plugin no módulo `app`, apontar `source` para
`app/src/main/java` e `app/src/test/java`, referenciar o `detekt.yml`, e
adicionar um job `detekt` ao workflow.

### 3.2 `MissingTranslation` estava rebaixado a warning — *corrigido em 2026-08-05*

`app/build.gradle.kts` declarava `abortOnError = true` mas com
`warning.add("MissingTranslation")`. Isso deixou **591 de 645 strings** passarem
não traduzidas pelo CI em verde, em 10 locales declarados. O portão existia e
estava explicitamente desarmado.

Agora `MissingTranslation` e `ExtraTranslation` são ambos `error`, e
`resourceConfigurations` declara apenas `en`, `pt`, `pt-rBR`.

### 3.3 Lacuna no CI

`.github/workflows/android-ci.yml` roda `lintDebug`, `testDebugUnitTest` e
`assembleDebug` (+ `assembleRelease` condicional em `main`). Não roda `detekt`.
Testes instrumentados também não rodam — razoável, exigem emulador.

---

## 4. i18n

### Estado anterior

`values/` (pt-BR) com 645 strings distribuídas em 6 arquivos; 10 locales
declarados (`ar bn en es fr hi in ru ur zh`) com 54–57 strings cada. Nenhuma
string marcada `translatable="false"` — ou seja, todas as 645 eram para
traduzir, e ~92% do texto caía no fallback português em qualquer idioma.

Detalhe agravante: `LanguageScreen.kt` só oferecia 4 opções (Automático,
Português, English, Español). Os outros 8 locales **nunca eram alcançáveis pela
UI** — só por mudança de idioma do sistema. Eram peso morto desde o início.

### Estado atual

Dois locales, ambos 100% completos, em paridade exata de chaves (645 = 645),
validada também nos 31 format strings (`%1$d`, `%1$s`, `%2$s`…). Lint travado
como `error` nas duas direções, então divergência futura quebra o build.

`values-en/` passou a espelhar a estrutura de 6 arquivos da base em vez de
concentrar tudo em `strings.xml`, para que diffs futuros fiquem localizados.

### Traduções defasadas corrigidas no processo

O `values-en` antigo continha texto que não correspondia mais à base — tradução
feita uma vez e nunca revisitada quando o produto mudou:

| Chave | Base (pt) | `en` antigo (errado) |
|---|---|---|
| `time_block` | Jejum de Dopamina | Time Block |
| `time_block_sub` | Dias e horários com cancelamento restrito | Days and Hours (Impossible to cancel) |
| `password_block_sub` | proteja o app para que outras pessoas não acessem | Fixed or Period (Requires password to change) |
| `blocked_websites_title` | Sites e palavras bloqueados | Blocked Websites |
| `add_website_button` | Adicionar site ou palavra | Add Website |
| `app_selection_preventive` | Aplicativos predefinidos (não instalados) | Preventive Block (Not Installed) |

### O que ainda falta — 28 strings hardcoded no código

`MissingTranslation` **não pega isso.** 28 strings em português estão escritas
direto em Kotlin, fora do sistema de recursos, então um usuário em inglês vê
português nesses pontos mesmo com os recursos 100% traduzidos:

| Arquivo | Ocorrências |
|---|---|
| `ui/compose/screens/UsageBlockConfigScreen.kt` | 13 |
| `ui/compose/screens/ActiveSessionsScreen.kt` | 4 |
| `ui/compose/screens/LimitsSecurityScreen.kt` | 4 |
| `ui/compose/screens/BlockCustomizationScreen.kt` | 3 |
| `service/BlockingAccessibilityService.kt` | 1 |
| `ui/BlockNoticeActivity.kt` | 1 |
| `ui/compose/screens/LanguageScreen.kt` | 1 |
| `ui/compose/screens/PomodoroScreen.kt` | 1 |

---

## 5. A descoberta que liga duas dívidas: recursos extraídos, call sites nunca religados

Das 645 strings de recurso, **150 (23%) não têm nenhuma referência** a
`R.string.<nome>` nem `@string/<nome>` em todo `app/src`.

O reflexo natural é chamar isso de código morto e deletar. **Seria errado.**
Cruzando a lista de órfãs com as 28 strings hardcoded, vários pares batem
exatamente:

| Texto hardcoded no Kotlin | Recurso órfão correspondente |
|---|---|
| `"Bloqueio Ativo"` (`ActiveSessionsScreen.kt:45`) | `status_blocking_active` |
| `"Sessão Registrada (Aguardando janela)"` (`:49`) | `status_session_registered` |
| `"Nenhuma Sessão Ativa"` (`:53`) | `status_no_session` |
| `"Sessões Ativas"` (`:61`) | `sessoes_ativas` |
| `"Personalizar Bloqueio"` (`BlockCustomizationScreen.kt:68`) | `personalizar_bloqueio` |
| `"Salvar Configurações"` (`:190`) | `salvar_configuracoes` |
| `"Idioma / Language"` (`LanguageScreen.kt:28`) | `idioma_language` |
| `"Desativar Modo Segurança?"` (`LimitsSecurityScreen.kt:236`) | `limits_safety_mode_disable_title` |
| `"Digite sua senha"` (`:247`) | `digite_sua_senha` |
| `"Quantidade de dias (máx. 120)"` (`UsageBlockConfigScreen.kt:260`) | `quantidade_de_dias_max_120` |

**Diagnóstico:** as strings foram extraídas para `strings.xml` por uma
ferramenta automatizada — o que explica os nomes gerados a partir do próprio
texto português (`quantidade_de_dias_max_120`,
`crie_uma_senha_para_desbloqueios_autoriz` truncado em 40 caracteres) — mas os
call sites nunca foram atualizados para `stringResource()`. Órfãs e hardcoded
são **dois sintomas do mesmo trabalho interrompido**.

O bug, nesses casos, é a ligação faltando — não a string. Deletar as órfãs
tornaria a correção mais difícil.

### Evidência de que a extração foi automatizada e sem revisão

- `<string name="navigationtransition">NavigationTransition</string>` — a
  ferramenta extraiu um *label de transição do Compose* (ferramental de debug,
  nunca visível ao usuário) como string traduzível.
- Dois recursos continham interpolação Kotlin literal, detalhados em §6.
- Nomes truncados arbitrariamente em 40 caracteres
  (`bloqueios_por_tempo_nao_podem_ser_revoga`,
  `mantem_o_focusguard_ativo_para_garantir_`).

---

## 6. Bugs encontrados e corrigidos

### Template Kotlin dentro de string XML — 2 ocorrências, ambas user-facing

```xml
<!-- antes -->
<string name="falha_ao_revogar_device_owner_e_message">Falha ao revogar Device Owner: ${e.message}</string>
<string name="senha_count_1">Senha ${count + 1}</string>
```

O Android não interpola `${…}` em recursos XML — renderiza como texto cru.

- **`DeviceOwnerManager.kt:1210`** exibia um Toast com o literal
  `${e.message}`. A exceção era capturada e nunca usada, então a mensagem real
  do erro se perdia exatamente no caminho em que o usuário mais precisa dela:
  a falha ao revogar Device Owner.
- **`AuthManager.kt:271`** rotulava **toda** senha salva como
  `Senha ${count + 1}`. Todas ficavam com o mesmo rótulo quebrado em vez de
  "Senha 1", "Senha 2" — o rótulo é o único jeito de distinguir senhas na tela
  de gerenciamento.

Ambos passaram a format strings (`%1$s` / `%1$d`), com os argumentos ligados nos
call sites (`e.message ?: e.javaClass.simpleName` e
`passwordDao.getAllStatic().size + 1`).

---

## 7. Dívida remanescente

| # | Item | Detalhe |
|---|---|---|
| 7.1 | 150 strings órfãs / 28 hardcoded | §4 e §5. Tratar como um só trabalho: religar o que tem par, remover o resto. |
| 7.2 | `ui.compose.screens` com 31 arquivos | A revisão anterior flagou 23 e recomendou subdividir; hoje são 31 — **piorou**. Só `sessions/` foi extraído (2 arquivos). |
| 7.3 | 4 single-file packages | `analytics`, `layout`, `navigation`, `theme` — 1 arquivo cada. Granularidade errada, inofensivo. |
| 7.4 | `analytics/AdvancedUsageAnalytics.kt` | Já não é código morto (é referenciado), mas segue sem nenhum teste. |
| 7.5 | 5 arquivos acima de 900 LOC | §1. Concentram o risco de regressão. |

---

## 8. Google Play — riscos de publicação

O perfil do app (AccessibilityService + Device Owner + câmera) é dos mais
escrutinados da loja.

### Bem resolvido

- Sem `QUERY_ALL_PACKAGES`; visibilidade direcionada via `<queries>` com
  filtros de LAUNCHER e BROWSABLE.
- `isAccessibilityTool="false"` — honesto, e é o caminho correto: exige
  divulgação proeminente, que existe
  (`SensitivePermissionsDisclosureScreen`, `strings_trust.xml`).
- Os `accessibilityEventTypes` amplos — incluindo `typeViewTextChanged`, que dá
  acesso a texto digitado — são **todos genuinamente consumidos** pelo serviço:
  detecção de app em foreground (`TYPE_WINDOW_STATE_CHANGED`,
  `TYPE_WINDOWS_CHANGED`), leitura de URL em navegador
  (`TYPE_WINDOW_CONTENT_CHANGED`, `TYPE_VIEW_TEXT_CHANGED`, `TYPE_VIEW_FOCUSED`)
  e interceptação de telas de settings (`TYPE_VIEW_CLICKED`). Não é excesso de
  escopo — mas **é** o ponto que a revisão do Play vai questionar, e a
  justificativa precisa estar pronta por escrito.
- `allowBackup="false"`, `usesCleartextTraffic="false"`,
  `networkSecurityConfig`, assinatura v2+v3 com v1 desligado.
- Device Admin declara **uma única** política (`force-lock`) — escopo mínimo.
- FGS `specialUse` com `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` preenchido em ambos os
  serviços, o que a revisão do Play exige.
- `ACCESS_NOTIFICATION_POLICY` é legitimamente usada
  (`BlockingSessionManager.kt:1100`, via `setInterruptionFilter`).

### Riscos abertos

| Risco | Detalhe |
|---|---|
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | `PermissionsScreen.kt:816` dispara `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` com `data = package:…`. Essa permissão tem lista fechada de usos aceitáveis no Play, e "app de foco/bloqueio" não está claramente nela. **O `GOOGLE_PLAY_TRUST_CHECKLIST.md` afirmava que essa solicitação direta havia sido removida — a afirmação está errada:** o commit `e9a271b` a reintroduziu, e existe um teste (`BatteryOptimizationPermissionManifestTest`) que *garante* a permissão no manifest. Decidir entre remover a solicitação direta (mandando o usuário para `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`, que não exige a permissão) ou preparar a justificativa. |
| `targetSdk = 35` | O Play exige anualmente a API mais recente −1, com corte em 31 de agosto. Confirmar no Play Console se a exigência vigente já é 36 — o SDK local já tem `android-36` instalado. Prazo curto. |
| Câmera "invisível" | `limits_intruder_selfie_desc` descreve captura invisível pela frontal após erro de senha. É acionada pelo próprio dono do aparelho, mas captura oculta é gatilho de escrutínio anti-stalkerware. Manter a divulgação explícita e demonstrar no vídeo de revisão. |
| Política de privacidade | `PRIVACY_POLICY_DRAFT.md` ainda é rascunho; precisa de URL HTTPS pública antes do envio. |

---

## 9. Plano de ação priorizado

1. **Ligar o detekt de verdade** (§3.1) — a ferramenta já está instalada e
   configurada, só não está plugada em nada. Maior retorno por esforço no
   documento.
2. **Testar `BlockingAccessibilityService`** (§2) — maior LOC e maior risco do
   projeto com um único arquivo de teste.
3. **Confirmar exigência de `targetSdk` no Play Console** (§8) — prazo de
   agosto, e é bloqueio duro de publicação.
4. **Decidir sobre `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`** (§8) — remover a
   solicitação direta ou documentar a justificativa. Corrigir a afirmação
   errada no checklist.
5. **Religar as strings órfãs e eliminar as 28 hardcoded** (§5) — tratar como um
   só trabalho, distinguindo "morta" de "não-ligada" caso a caso.
6. **Quebrar `ui.compose.screens`** (§7.2) — refactor mecânico, baixo risco,
   mas piorou desde a última revisão.

---

*Documento gerado em 2026-08-05 por análise do codebase no commit `937f74d` da
branch `main`. Métricas de LOC, contagem de testes, paridade de strings e uso de
permissões foram medidas diretamente na árvore de arquivos, não estimadas.*
