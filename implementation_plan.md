# Plano de implementação — widget compacto e controles do Pomodoro

## Objetivo
Deixar o widget do Pomodoro mais compacto e útil: **iniciar/parar o Pomodoro**, **alterar o número de sessões**, abrir o **dial giratório** a partir do relógio e usar por padrão a largura completa de uma grade de 4 colunas, mantendo redimensionamento horizontal e vertical.

## Diagnóstico
- [x] O `AppWidget` usa `RemoteViews`; o launcher não entrega gestos contínuos de arraste para um relógio customizado como uma tela Compose entrega.
- [x] Já existe `PomodoroWidgetDialActivity`, que reutiliza `PomodoroDurationDial`, o mesmo controle giratório usado pelo app, mas o relógio do widget não estava ligado a essa Activity.
- [x] `PomodoroManager.stopSession()` já encerra o Pomodoro com a limpeza correta; não é necessário duplicar lógica de parada.
- [x] `PomodoroPlanConfig.targetSessions` já representa o número de sessões no intervalo `0..5`; `0` significa “até eu parar”.
- [x] `PomodoroPlanConfig.normalized()` já força `strictBlocking = false`; o modo rigoroso não precisa de rota especial no widget/dial e deve permanecer desativado.
- [x] O widget anterior reservava altura mínima excessiva (`340dp`, resize mínimo `330dp`), deixando espaço vazio que o launcher não permitia reduzir.

## Implementação
- [x] Fazer o botão principal alternar entre `Iniciar` e `Parar`, sempre reutilizando `PomodoroManager.startPlan(...)`/`stopSession()`.
- [x] Remover a exceção de parada para Pomodoro rigoroso no widget.
- [x] Remover verificações e imports específicos de Pomodoro rigoroso da tela de dial do widget e persistir sempre `strictBlocking = false`.
- [x] Tornar o relógio clicável e abrir `PomodoroWidgetDialActivity` quando o Pomodoro estiver parado.
- [x] Reutilizar `PomodoroDurationDial` nessa Activity para permitir girar o relógio exatamente com o controle Compose já existente.
- [x] Manter controles `− / Sessões / +` diretamente no widget, limitados a `0..5` e bloqueados durante um runtime ativo.
- [x] Reduzir relógio, paddings e botões para eliminar espaço vertical ocioso.
- [x] Definir `targetCellWidth=4` e largura mínima de `320dp` para favorecer largura completa em launchers de quatro colunas, mantendo `minResizeWidth=200dp` para permitir ajuste posterior.
- [x] Reduzir altura padrão/mínima e permitir resize vertical até `230dp`.
- [x] Preservar `Configurar`, fases, pausas, notificações e demais regras normais do Pomodoro.

## Validação
- [x] Confirmar que o relógio abre o dial somente quando não existe Pomodoro ativo.
- [x] Confirmar que o botão principal inicia quando parado e para quando ativo.
- [x] Confirmar limites das sessões: `0` não diminui e `5` não aumenta.
- [x] Confirmar que a edição de sessões fica bloqueada durante runtime ativo.
- [x] Confirmar que a configuração de widget continua redimensionável nos dois eixos.
- [x] Confirmar que a mudança permanece concentrada no widget/dial e não altera Modo Foco ou mecanismos gerais de bloqueio.
- [ ] Executar CI com Android Lint, testes unitários e compilação do performance harness.

## Critério de conclusão
O widget deve ocupar por padrão quatro colunas em launchers compatíveis, poder ser reduzido depois, não reservar grande área vazia, permitir escolher `0..5` sessões, iniciar/parar o Pomodoro e abrir pelo próprio relógio o dial giratório existente para ajustar minutos. O Pomodoro rigoroso deve permanecer desativado e sem tratamento especial no fluxo do widget.
