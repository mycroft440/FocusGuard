# FocusGuard — fast path cache follow-up

## Objetivo atual

Reduzir a latência de autoproteção removendo leituras externas do caminho crítico sem criar falsos positivos, quebrar a manutenção autorizada ou enfraquecer as proteções após reboot.

## Estado

- CUMPRIDO: comparar o `main` pós-PR #65 com o relatório de otimização.
- CUMPRIDO: pré-carregar `DeviceOwnerMaintenanceGate` no início do processo e tornar manutenção/tempo restante memory-only após preload/open/revoke.
- CUMPRIDO: preservar deadline monotônico, BOOT_COUNT e validação de data/hora automáticas fora do callback crítico.
- CUMPRIDO: remover `DevicePolicyManager.isDeviceOwnerApp()` redundante das decisões críticas de App Info/Settings.
- CUMPRIDO: pré-carregar `DeviceAdminActivationWindow`, validar DPM ao abrir/restaurar a autorização e tornar a decisão durante o evento memory-only.
- CUMPRIDO: impedir que expiração das janelas Device Admin e remoção autenticada faça limpeza de SharedPreferences durante uma tentativa bloqueada.
- CUMPRIDO: fazer `isSelfProtectionEngagedNow()` consultar apenas snapshots voláteis já alimentados pelo estado persistido/Device Owner fora do callback.
- CUMPRIDO: usar `AccessibilityEvent.getSource(0)` no Android 13+ para evitar prefetch desnecessário quando o nó fonte é realmente necessário.
- CUMPRIDO: reduzir o fallback direto de `findAccessibilityNodeInfosByText()` a um locator forte (`FocusGuard`) por estágio; as buscas amplas permanecem somente no último fallback de política.
- CUMPRIDO: memoizar cada `RootSignals` por evento para impedir repetição da mesma consulta de root em ramos diferentes da política.
- CUMPRIDO: adicionar teste unitário específico garantindo uma única avaliação por sinal memoizado.
- CUMPRIDO: adicionar telemetria best-effort de evento → frame commit da cortina, separando o pedido de exibição do frame efetivamente submetido.
- CUMPRIDO: adicionar testes unitários para o cálculo da latência de frame commit.
- CUMPRIDO: PR #66 validado em Unit Tests, Android Lint e build Release APK + Debug APK + AAB e integrado na `main`.
- CUMPRIDO: adicionar módulo oficial `:baselineprofile` com `BaselineProfileRule` e Macrobenchmark de cold start.
- CUMPRIDO: configurar Pixel 6 AOSP/API 35 como Gradle Managed Device reproduzível para geração de Baseline Profile.
- CUMPRIDO: manter o baseline manual do hard-block e habilitar geração explícita de perfil AndroidX sem tornar toda build dependente de dispositivo.
- CUMPRIDO: adicionar comparativo cold start sem compilação vs Baseline Profile exigido.
- CUMPRIDO: adicionar compilação obrigatória do harness de performance ao CI normal.
- CUMPRIDO: adicionar parser p50/p95/p99 para `A11yLatency` e para JSON bruto de Macrobenchmark.
- CUMPRIDO: documentar matriz Samsung/Pixel/Xiaomi, coleta de 100+ tentativas por superfície e critérios de aceitação.
- EM VALIDAÇÃO: CI do PR #67 deve passar Unit Tests, Lint, APK/AAB e compilação do novo harness.
- DEPENDÊNCIA EXTERNA: números absolutos p50/p95/p99 Samsung/Pixel/Xiaomi exigem os aparelhos físicos ou device farm; nenhum provedor/dispositivo físico está conectado nesta sessão, portanto não serão fabricados dados sintéticos.

## Critério de aprovação

A implementação de software está pronta para integração quando o diff estiver revisado e o CI passar Unit Tests, Android Lint, Release APK, Debug APK, Release AAB, compilação do harness e self-tests dos parsers. A matriz física permanece um procedimento operacional executável assim que hardware real estiver disponível.
