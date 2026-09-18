# Plano de implementação — opção de bloqueio por períodos do dia na Home

## Diagnóstico
- [x] A Home renderiza `BlockTypeUi.entries`, hoje com apenas PASSWORD, DAILY_LIMIT e DOPAMINE_FAST.
- [x] A faixa diária já existe no fluxo `TIME` via `TimeBlockSessionConfigScreen`.
- [x] `BlockSession` já diferencia sessões recorrentes por `isRecurring/isFixed24h`.
- [x] O problema é de exposição e separação de modo: o agendamento foi incorporado ao card antigo em vez de ganhar uma opção própria.

## Implementação
- [ ] Adicionar um quarto tipo visual "Bloquear apps em períodos do dia" na Home.
- [ ] Manter "Bloqueie sem senha por tempo" como bloqueio contínuo de 24 h.
- [ ] Encaminhar ambos pela infraestrutura `TIME`, usando um modo de configuração explícito.
- [ ] Mostrar dias/horários somente no modo por períodos do dia.
- [ ] Separar no resumo as sessões `TIME` recorrentes das contínuas.
- [ ] Adicionar strings pt/en em paridade.
- [ ] Adicionar teste de regressão garantindo a presença da nova opção e o banner aplicável.

## Validação
- [ ] Revisar diff e garantir que PASSWORD e DAILY_LIMIT não mudaram.
- [ ] Executar testes unitários focados.
- [ ] Executar compile/lint/test do app quando o ambiente permitir.
