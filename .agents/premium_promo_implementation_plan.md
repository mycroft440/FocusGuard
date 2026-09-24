# Código promocional Premium

- Menu: SettingsScreen, imediatamente abaixo do Instagram; diálogo Material 3 com validação e confirmação.
- PremiumStateStore: ativação local persistente pelo código josegustavo34, sem expiração; não altera bloqueios ou permissões.
- FocusGuardAds: impedir requests e apresentação, conferir callbacks tardios e encerrar preloads existentes.
- FocusGuardBannerAd: observar ativação e remover/destruir banner imediatamente.
- RewardedGateCoordinator/Activity: executar ação diretamente para Premium, sem consumir créditos de vídeos.
- MonetizationStateStore: descartar fila de intersticiais e não gerar novas pendências Premium.
- Validar persistência, código inválido, bypass de todos os pacotes e fila Pomodoro; executar testes, lint e build quando ambiente permitir.
