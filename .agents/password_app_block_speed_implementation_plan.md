# Bloqueio de app por senha: redução de latência

## Pontos afetados

- `BlockNoticeActivity` chama `AppBlockSurfaceResolver` antes da tela de senha; o resolver preserva a prioridade de TIME, limite diário e Focus Mode.
- `BlockingSessionManager.findResponsibleSessionId` consulta apps de cada sessão PASSWORD, inclusive sessões de outros alvos.
- `BlockingSessionManager.credentialUnlockOrigin` verifica limites de sites por meio de `unlockCredentialProtectedLimit` mesmo quando a tentativa contém apenas um app.
- A cortina imediata e a checagem de propriedade antes de conceder a visita continuam no serviço e no painel de senha.

## Checklist

- [x] Inspecionar interceptação, autenticação, concessão de visita e prioridade dos bloqueios.
- [x] Consultar diretamente as sessões PASSWORD do app e manter o filtro de janela ativa.
- [x] Evitar consultas a limites de sites em tentativas que só envolvem um app.
- [x] Cobrir a seleção de sessões com TIME, sessões inativas e apps distintos.
- [x] Revisar diff e executar testes/build disponíveis, sem alterar o roteamento dos outros bloqueios. Android CI Pro #1836: testes unitários, Android Lint e compilação do módulo de performance passaram.
