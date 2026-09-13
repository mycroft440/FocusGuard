# Plano de implementação — controles do Pomodoro no widget

## Objetivo
Permitir controlar o Pomodoro diretamente pelo widget da tela inicial: **parar uma sessão normal em execução** e **alterar o número de sessões planejadas** sem precisar abrir a tela completa do app.

## Diagnóstico
- [x] O widget atual possui apenas `Configurar` e `Iniciar`.
- [x] Quando existe runtime ativo, o botão principal muda para “em execução”, mas é desabilitado; portanto não há caminho para parar pelo widget.
- [x] `PomodoroManager.stopSession()` já encerra um plano normal com a limpeza correta; não é necessário criar uma segunda lógica de parada.
- [x] A tela principal não permite parada manual quando o Pomodoro rigoroso está ativo. O widget deve preservar essa proteção e não pode virar uma rota de saída antecipada.
- [x] O número de sessões já pertence a `PomodoroPlanConfig.targetSessions`, normalizado no intervalo `0..5`; `0` significa “até eu parar”.
- [x] `PomodoroPlanStore.saveConfig()` já persiste a configuração e atualiza os widgets.
- [x] Alterar `targetSessions` durante um runtime ativo exigiria sincronizar também o estado em memória do plano; para evitar uma mudança de regra não solicitada, os controles serão editáveis apenas quando o Pomodoro estiver parado.

## Implementação
- [x] Fazer o botão principal alternar entre `Iniciar` e `Parar` em Pomodoro normal.
- [x] Roteá-lo para `startPlan(...)` quando ocioso e `stopSession()` quando um plano normal estiver ativo.
- [x] Manter o botão bloqueado como “em execução” se o runtime for rigoroso e rejeitar também a ação de parada no receiver.
- [x] Adicionar ao layout do widget uma linha compacta `−  Sessões  +`.
- [x] Exibir `Até eu parar` quando `targetSessions == 0`, reutilizando as strings já existentes.
- [x] Permitir reduzir/aumentar `targetSessions` entre 0 e 5 e persistir pelo `PomodoroPlanStore`.
- [x] Desabilitar `−/+` durante um Pomodoro ativo, mantendo o valor visível e evitando alterar o plano em execução.
- [x] Ajustar a altura mínima do widget para acomodar a nova linha sem cortar os botões existentes.
- [x] Manter o botão `Configurar`, o relógio, o fluxo de permissões e a lógica central do Pomodoro intactos.

## Validação
- [x] Revisar o diff para garantir que as mudanças estão limitadas ao widget e ao plano desta tarefa.
- [x] Confirmar que o botão principal continua iniciando quando parado.
- [x] Confirmar que o mesmo botão para somente Pomodoro normal quando ativo.
- [x] Confirmar que Pomodoro rigoroso continua sem parada antecipada pelo widget.
- [x] Confirmar limites das sessões: 0 não diminui e 5 não aumenta.
- [x] Confirmar que a edição de sessões fica bloqueada durante runtime ativo.
- [ ] Executar CI com Android Lint, testes unitários e compilação do performance harness.

## Critério de conclusão
No widget, o usuário deve conseguir escolher de `0` a `5` sessões antes de iniciar (`0 = até eu parar`), iniciar o plano e, durante a execução normal, usar o botão principal para parar o Pomodoro. Pomodoro rigoroso deve continuar impossível de interromper antecipadamente. Nenhuma outra regra do Pomodoro ou área do app deve ser alterada.
