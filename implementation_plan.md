# Plano de implementação — controles do Pomodoro no widget

## Objetivo
Permitir controlar o Pomodoro diretamente pelo widget da tela inicial: **parar uma sessão em execução** e **alterar o número de sessões planejadas** sem precisar abrir a tela completa do app.

## Diagnóstico
- [x] O widget atual possui apenas `Configurar` e `Iniciar`.
- [x] Quando existe runtime ativo, o botão principal muda para “em execução”, mas é desabilitado; portanto não há caminho para parar pelo widget.
- [x] `PomodoroManager.stopSession()` já encerra o plano com a limpeza correta; não é necessário criar uma segunda lógica de parada.
- [x] O número de sessões já pertence a `PomodoroPlanConfig.targetSessions`, normalizado no intervalo `0..5`; `0` significa “até eu parar”.
- [x] `PomodoroPlanStore.saveConfig()` já persiste a configuração e atualiza os widgets.
- [x] Alterar `targetSessions` durante um runtime ativo exigiria sincronizar também o estado em memória do plano; para evitar uma mudança de regra não solicitada, os controles serão editáveis apenas quando o Pomodoro estiver parado.

## Implementação
- [ ] Fazer o botão principal alternar entre `Iniciar` e `Parar` conforme o runtime.
- [ ] Roteá-lo para `startPlan(...)` quando ocioso e `stopSession()` quando ativo.
- [ ] Adicionar ao layout do widget uma linha compacta `−  Sessões  +`.
- [ ] Exibir `Até eu parar` quando `targetSessions == 0`, reutilizando as strings já existentes.
- [ ] Permitir reduzir/aumentar `targetSessions` entre 0 e 5 e persistir pelo `PomodoroPlanStore`.
- [ ] Desabilitar `−/+` durante um Pomodoro ativo, mantendo o valor visível e evitando alterar o plano em execução.
- [ ] Manter o botão `Configurar`, o relógio, o fluxo de permissões e a lógica do Pomodoro intactos.

## Validação
- [ ] Revisar o diff para garantir que as mudanças estão limitadas ao widget e ao plano desta tarefa.
- [ ] Confirmar que o botão principal continua iniciando quando parado.
- [ ] Confirmar que o mesmo botão para o Pomodoro quando ativo.
- [ ] Confirmar limites das sessões: 0 não diminui e 5 não aumenta.
- [ ] Confirmar que a edição de sessões fica bloqueada durante runtime ativo.
- [ ] Executar CI com Android Lint, testes unitários e compilação do performance harness.

## Critério de conclusão
No widget, o usuário deve conseguir escolher de `0` a `5` sessões antes de iniciar (`0 = até eu parar`), iniciar o plano e, durante a execução, usar o botão principal para parar o Pomodoro. Nenhuma outra regra do Pomodoro ou área do app deve ser alterada.
