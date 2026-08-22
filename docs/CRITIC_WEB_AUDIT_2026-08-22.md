# Auditoria web do Crítico — 2026-08-22

Status: **REPROVADO — há correções obrigatórias**

Esta é a primeira rodada externa do Crítico. Ela complementa a auditoria arquitetural já em andamento no PR #50. Os achados abaixo não autorizam copiar mecanismos de concorrentes que contrariem as políticas atuais do Android/Google Play.

## Pontos fortes confirmados

- `QUERY_ALL_PACKAGES` já foi removido e substituído por visibilidade direcionada.
- tráfego HTTP em claro está desativado e backup do app está desabilitado;
- AccessibilityService declara `isAccessibilityTool=false`, não pede screenshots nem gestos;
- o manifesto documenta os três usos de foreground service `specialUse` com propriedades específicas;
- a política interna já restringe anti-remoção forte ao fluxo de Device Owner;
- lint e testes unitários já são gates do CI, e release usa R8/resource shrinking.

## Achados prioritários

### FG-001 — P0 — target API 36 antes do corte do Google Play

**Evidência interna:** `app/build.gradle.kts` usa `compileSdk = 35` e `targetSdk = 35`.

**Benchmark externo:** a partir de 31/08/2026, novos apps e atualizações para mobile precisam direcionar Android 16 / API 36.

Fonte: https://support.google.com/googleplay/android-developer/answer/11926878

**Impacto:** após o corte, um novo envio/atualização pode ser bloqueado no Play Console.

**Ação:** migrar compile/target para 36 e executar uma revisão específica das mudanças comportamentais do Android 16 antes de publicar.

**Aceitação:** build, lint e testes verdes em API 36; checklist de mudanças comportamentais documentado; instalação e fluxo principal verificados em Android 16.

### FG-002 — P1 — isenção direta de otimização de bateria ainda precisa de decisão formal

**Evidência interna:** o manifesto declara `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; o checklist do próprio projeto registra que `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` voltou ao fluxo e mantém a decisão aberta.

**Benchmark externo:** o Android mantém uma lista de casos aceitáveis para a solicitação direta e orienta que a exceção só seja usada quando Doze/App Standby quebrar a função principal ou houver justificativa técnica adequada.

Fontes:
- https://developer.android.com/training/monitoring-device-state/doze-standby
- https://support.google.com/googleplay/android-developer/answer/17190352

**Impacto:** risco de revisão/rejeição de política se o caso de uso não for justificável; também adiciona fricção no onboarding.

**Ação:** medir a confiabilidade real do bloqueio com e sem a exceção em Pixel/Samsung/Xiaomi e então escolher entre remover a solicitação direta, usar apenas a tela geral de otimização ou documentar tecnicamente por que o recurso principal exige a exceção.

**Aceitação:** decisão escrita + evidência de teste físico + fluxo de permissão atualizado + declaração Play coerente.

### FG-003 — P1 — custo do AccessibilityService precisa ser medido

**Evidência interna:** `accessibility_service_config.xml` escuta `typeWindowStateChanged`, `typeWindowsChanged`, `typeWindowContentChanged`, `typeViewClicked`, `typeViewTextChanged` e `typeViewFocused`, recupera janelas/conteúdo e usa `notificationTimeout="0"`.

**Benchmark externo:** a documentação do Android explica que eventos de acessibilidade atravessam IPC caro e que `notificationTimeout` pode reduzir propagação excessiva. Também recomenda solicitar apenas os eventos necessários.

Fontes:
- https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo
- https://developer.android.com/guide/topics/ui/accessibility/service

**Impacto:** CPU/bateria e pressão de eventos em telas dinâmicas; alterar sem medir também pode introduzir atraso no bloqueio.

**Ação:** criar benchmark/telemetria local de frequência e latência por tipo de evento; remover eventos que não participam da decisão ou aplicar debounce/timeout apenas onde não prejudique bloqueio instantâneo.

**Aceitação:** latência de bloqueio permanece dentro da meta definida e eventos/processamento por minuto caem ou ficam comprovadamente necessários.

### FG-004 — P1 — cobertura de CI não representa integrações críticas de sistema

**Evidência interna:** o workflow atual executa Android Lint, unit tests e build de APK, mas não possui job de testes instrumentados/managed devices para Accessibility, Usage Access, alarmes, reinício, foreground services ou Device Owner.

**Impacto:** regressões em APIs dependentes do SO/OEM podem passar com unit tests verdes.

**Ação:** adicionar uma camada de instrumentação viável no CI e manter matriz física/manual para casos que emulador não reproduz adequadamente.

**Aceitação:** smoke tests instrumentados para fluxos testáveis + checklist físico Pixel/Samsung/Xiaomi versionado e executado antes de release.

### FG-005 — P1 — pipeline de release gera APK, mas publicação Play exige AAB

**Evidência interna:** o job `release` compila `assembleRelease` e publica `app/build/outputs/apk/release/*.apk`; o próprio checklist do projeto ainda marca Android App Bundle como etapa pendente.

**Impacto:** artefato automático atual não é o artefato final esperado para publicação normal no Google Play.

**Ação:** adicionar `bundleRelease`, artefato `.aab` e validações correspondentes, preservando APK para testes quando útil.

**Aceitação:** CI produz AAB assinado de release de forma reproduzível e mantém segredo/keystore fora do artefato.

### FG-006 — P1 — revisar os três foreground services `specialUse` como produto e política

**Evidência interna:** BlockingAccessibilityService, PomodoroForegroundService e FocusModeForegroundService são declarados como `specialUse` e possuem descrições no manifesto.

**Benchmark externo:** o Google Play revisa cada `specialUse`; foreground service precisa ser benéfico, ligado ao recurso principal, iniciado/perceptível pelo usuário, interrompível e usado somente pelo tempo necessário.

Fontes:
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://support.google.com/googleplay/android-developer/answer/17190352

**Impacto:** risco de política ou bateria caso algum serviço possa ser substituído por mecanismo mais apropriado ou permaneça ativo além do necessário.

**Ação:** mapear ciclo de vida, gatilho, notificação, parada e necessidade de cada serviço; manter `specialUse` somente onde a evidência justificar.

### FG-007 — P1 — decomposição arquitetural continua sendo requisito da auditoria

**Evidência interna:** o PR #50 já identificou e começou a decompor `BlockingAccessibilityService`, `BlockingSessionManager` e `DeviceOwnerManager`, além de remover acesso Room direto de Composables e grafos de dependência paralelos.

**Impacto:** classes monolíticas tornam correções de bloqueio/segurança mais arriscadas e dificultam testes isolados.

**Ação:** concluir os critérios já definidos em `.agents/implementation_plan.md` antes de expandir o produto.

### FG-008 — P2 — Baseline Profile / Startup Profile ausentes

**Evidência interna:** não há módulo/ configuração de Baseline Profile ou Macrobenchmark no repositório pesquisado.

**Benchmark externo:** Android documenta aproximadamente 30% de melhoria de execução nos caminhos cobertos por Baseline Profiles e recomenda Startup Profiles em conjunto para otimizar inicialização.

Fontes:
- https://developer.android.com/topic/performance/baselineprofiles/overview
- https://developer.android.com/topic/performance/startupprofiles/dex-layout-optimizations

**Ação:** depois de estabilizar arquitetura, medir startup e navegação crítica com Macrobenchmark e gerar perfis para os caminhos mais usados.

### FG-009 — P2 — UI adaptável ainda não usa Material 3 Adaptive

**Evidência interna:** não foram encontrados `WindowSizeClass`, `currentWindowAdaptiveInfo`, `NavigationSuiteScaffold` ou dependências Material 3 Adaptive.

**Benchmark externo:** as recomendações Android atuais orientam layouts com window size classes e Material 3 Adaptive; a versão 1.3.0 está estável desde 12/08/2026.

Fontes:
- https://developer.android.com/develop/adaptive-apps/guides/adaptive-dos-and-donts
- https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive

**Impacto:** tablets, dobráveis, desktop windowing e multi-window podem receber layout apenas esticado em vez de realmente adaptado.

**Ação:** auditar primeiro Main/Proteção/Métricas/Configurações e implantar navegação/layout por classes de janela onde trouxer benefício real.

### FG-010 — P2 — permissões devem continuar progressivas e ligadas à função usada

**Evidência interna:** o manifesto reúne Acesso de Uso, notificações, câmera, DND, alarme exato, foreground service, wake lock e bateria. Há uma tela central de permissões.

**Benchmark externo:** Android/Play privilegiam minimização, transparência e solicitação de acesso sensível apenas quando necessário.

**Ação:** durante a auditoria de onboarding, separar permissões realmente essenciais ao bloqueio básico das opcionais de recursos específicos. Ex.: câmera não deve ser um pré-requisito global se só servir a uma função opcional.

### FG-011 — P2 — oportunidades competitivas a avaliar, não copiar automaticamente

**Sinais externos atuais:**

- AppBlock oferece condições de Strict Mode por tempo/agenda, cooldown e aprovação por parceiro; também documenta defesas contra recentes, split-screen e apps reinstalados.
- Freedom possui Locked Mode, sessões recorrentes, blocklists reutilizáveis, filtros por categorias e pausa limitada em Locked Mode.
- Stay Focused divulga limites de tempo, perfis, timeline, contagem/estatísticas e bloqueios de Shorts/Reels.
- discussões recentes de usuários continuam destacando dois problemas: bypass fácil e bloqueio de sites inconsistente entre navegadores.

Fontes:
- https://appblock.app/help/android/strict-mode/
- https://support.freedom.to/en/articles/1802927-locked-mode
- https://support.freedom.to/en/articles/4581989-android-blocklist-and-session-set-up
- https://www.stayfocused.me/

**Candidatos para avaliação de produto:** cooldown voluntário; aprovação por pessoa de confiança; grupos/perfis reutilizáveis; limite coletivo por grupo; limite por número de aberturas; categorias prontas; bloqueio seletivo de feeds/Shorts/Reels quando tecnicamente robusto; diagnóstico automático de permissões/bateria/navegador.

**Restrição:** mecanismos de anti-desinstalação/Configurações de concorrentes não devem ser copiados para instalações pessoais se dependerem de Accessibility de modo incompatível com a política vigente. O FocusGuard deve manter o caminho forte sob Device Owner/gestão autorizada.

### FG-012 — P2 — tornar confiabilidade de bloqueio uma métrica de qualidade

**Ação:** definir uma matriz versionada por Android/OEM/navegador contendo tempo até bloqueio, taxa de bypass, recuperação após reboot/update, comportamento em multi-window/PiP/recentes e impacto de bateria. Sem essa matriz, “inquebrável” não é uma propriedade verificável.

**Aceitação:** cada release crítica registra resultados mínimos para Pixel/Android puro, Samsung/One UI e Xiaomi/HyperOS, além dos navegadores suportados.

## Ordem recomendada ao Executor

1. concluir a Parte 1 arquitetural do PR #50 e obter CI verde;
2. FG-001 (API 36) antes do prazo de publicação;
3. FG-002, FG-003 e FG-006 (permissões/bateria/acessibilidade/FGS) com medição e política;
4. FG-004, FG-005 e FG-012 (qualidade/release/matriz de confiabilidade);
5. FG-008 e FG-009 (performance e adaptabilidade);
6. FG-010 e FG-011 (onboarding e evolução competitiva);
7. rodada final do Crítico sobre o projeto completo.

O Crítico deve reabrir a pesquisa web em cada rodada porque políticas, Android e concorrentes mudam com frequência.
