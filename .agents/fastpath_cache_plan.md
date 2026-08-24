# FocusGuard — fast path cache follow-up

## Objetivo atual

Reduzir a latência de autoproteção removendo leituras externas do caminho crítico sem criar falsos positivos, quebrar a manutenção autorizada ou enfraquecer as proteções após reboot.

## Estado

- CUMPRIDO: comparar o `main` pós-PR #65 com o relatório de otimização.
- CUMPRIDO: identificar o gate de manutenção Device Owner como principal leitura externa ainda repetida no fast path.
- CUMPRIDO: pré-carregar `DeviceOwnerMaintenanceGate` no início do processo.
- CUMPRIDO: fazer `isTemporarilyUnlocked()` e `remainingMillis()` decidirem por snapshot em memória após preload/open/revoke.
- CUMPRIDO: preservar deadline monotônico, BOOT_COUNT e exigência de data/hora automáticas na carga/abertura da janela.
- CUMPRIDO: pré-carregar `DeviceAdminActivationWindow` no início do processo.
- CUMPRIDO: impedir que expiração/reboot da janela Device Admin faça limpeza de SharedPreferences durante uma tentativa bloqueada.
- EM VALIDAÇÃO: Unit Tests, Android Lint e build APK/AAB da branch.
- PRÓXIMO OBJETIVO: remover a chamada redundante a `DevicePolicyManager.isDeviceOwnerApp()` do evento crítico usando snapshot de papel administrativo com invalidação segura.
- PRÓXIMO OBJETIVO: usar `AccessibilityEvent.getSource(0)` no Android 13+ quando a árvore for realmente necessária.
- PRÓXIMO OBJETIVO: reduzir `findAccessibilityNodeInfosByText()` sem perder cobertura One UI/HyperOS/AOSP.
- PRÓXIMO OBJETIVO: medir evento → frame commit da cortina e separar source/root/DPM/WindowManager/HOME na telemetria.
- PRÓXIMO OBJETIVO: adicionar Macrobenchmark/Baseline Profile Generator e matriz física para p50/p95/p99.

## Critério de aprovação

A mudança só está pronta para integração quando o diff estiver revisado, os testes e Lint estiverem verdes e o build final da branch gerar APK/AAB sem regressões.
