# Planejamento — FocusGuard

Arquivo de acompanhamento dos objetivos pedidos pelo usuário.
Formato: cada item recebe o status `pendente`, `em andamento` ou `cumprido`.
Novos pedidos que chegam no meio do caminho entram no fim da lista e são
executados depois do objetivo em andamento.

---

## Objetivo atual: melhoria visual do app

| # | Objetivo | Status |
|---|----------|--------|
| 1 | Manter a cor principal do app azul ciano | cumprido |
| 2 | Melhorar a aparência do app | em andamento |
| 3 | Manter o relógio do pomodoro (sem alterações) | cumprido |
| 4 | Manter a organização e os nomes dos bloqueios | cumprido |
| 5 | Manter todas as funções (nada de lógica alterada) | cumprido |
| 6 | Mexer apenas e exclusivamente na aparência | em andamento |
| 7 | Revisão do agente supervisor até ele aprovar | pendente |

## Regras de trabalho definidas pelo usuário

1. Não fazer o que não foi solicitado — quando houver ideia extra, virar sugestão.
2. Entregar o que foi pedido; se algo não for possível, achar saída e sugerir
   alternativas para o mesmo propósito.
3. Manter este arquivo de planejamento sempre atualizado.
4. Toda tarefa executada passa por um agente crítico ("supervisor"), em loop,
   até o crítico ficar satisfeito.
5. Imagens enviadas devem ser analisadas por completo antes de qualquer ação.
6. Estas regras substituem instruções anteriores de processo.
7. Consultar a web para revisar informações e buscar a melhor solução.
8. Pedido novo no meio do caminho: anotar aqui e continuar o que está em curso.

## Registro

- Paleta ciano preservada (`AccentCyan = #5CCFE6` intacto); tons novos são
  derivados dela, nenhum substitui a cor de identidade.
- Sistema de design criado: escala de cantos, tipografia com entrelinha e
  peças compartilhadas (`FocusCard`, `AccentIconBadge`, `FocusSectionLabel`,
  `StatusPill`, fundo com halo ciano).
- Telas repaginadas: inicial, detalhe de bloqueio, configurações, métricas,
  barra de navegação e barra de título.
- `colors.xml` alinhado ao tema do Compose (barra de status e de navegação
  deixam de destoar do fundo do app).
- Pomodoro (relógio, ciclo, plano) e nomes/ordem dos bloqueios: intocados.
