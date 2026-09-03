# Instruções obrigatórias — Monetização e anúncios

Qualquer agente ou pessoa que adicionar, remover, mover ou alterar anúncios no FocusGuard deve, **antes de editar o código**:

1. Ler `docs/AD_MONETIZATION_PLAYBOOK.md` inteiro.
2. Revisar as políticas oficiais do AdMob listadas no final do playbook quando a alteração for relevante ou quando o documento estiver desatualizado.
3. Preservar `FocusGuardAds` como ponto central de integração; não espalhar IDs/carregadores pelas telas.
4. Preservar UMP/consentimento, tratamento de falhas e proteção contra fullscreen duplicado.
5. Não trocar os IDs oficiais de teste usados no Release sem autorização explícita do responsável pelo projeto.
6. Priorizar lucro sustentável (LTV/ARPDAU + retenção), não quantidade bruta de impressões.
7. Para rewarded: opt-in claro, recompensa anunciada, crédito somente no callback real e progresso persistente para pacotes.
8. Para interstitial: somente transições naturais, nunca saída do app, background, meio de tarefa ou back-to-back.
9. Adicionar/atualizar testes e métricas quando criar uma nova regra de monetização.
10. Só considerar concluído com testes, Lint e CI verdes.

Se uma solicitação entrar em conflito com política oficial do Google, **não implemente a violação**; proponha a alternativa mais lucrativa que permaneça compatível.

> **Baseline validada em 2026-09-03:** a recuperação do Pomodoro em segundo plano que sustenta o anúncio no encerramento passou por `testDebugUnitTest`, `lintDebug` e `assembleDebug`. Qualquer alteração futura em anúncios ou no fluxo de encerramento do Pomodoro deve revalidar essa integração.
