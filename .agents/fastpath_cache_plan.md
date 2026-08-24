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
- CUMPRIDO: CI do commit funcional anterior (`c09493c`) passou em Unit Tests, Android Lint e build Release APK + Debug APK + AAB.
- EM VALIDAÇÃO: CI final do head atual, incluindo o teste de memoização e esta atualização de planejamento.
- PRÓXIMO OBJETIVO: adicionar Macrobenchmark/Baseline Profile Generator e matriz física Samsung/Pixel/Xiaomi para medir p50/p95/p99 e decidir se vale reduzir ainda mais o fallback OEM raro.

## Critério de aprovação

A mudança só está pronta para integração quando o diff estiver revisado, os testes e Lint estiverem verdes e o build final da branch gerar APK/AAB sem regressões.
