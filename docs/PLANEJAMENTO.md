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
| 2 | Melhorar a aparência do app | cumprido |
| 3 | Manter o relógio do pomodoro (sem alterações) | cumprido |
| 4 | Manter a organização e os nomes dos bloqueios | cumprido |
| 5 | Manter todas as funções (nada de lógica alterada) | cumprido |
| 6 | Mexer apenas e exclusivamente na aparência | cumprido |
| 7 | Revisão do agente supervisor até ele aprovar | cumprido (aprovado na rodada 2) |
| 8 | Repaginar Permissões, Lista de Sessões, Recuperação e Detalhe da Sessão | cumprido |
| 9 | Resolver a emenda no topo da tela (sem mexer em insets) | cumprido |
| 10 | Nova rodada do supervisor sobre os itens 8 e 9 | parcial — agente caiu por limite de sessão; revisão feita por conta própria |
| 11 | Ao terminar tudo, commitar tudo na main | em andamento |
| 12 | Não alterar os bloqueios nem como funcionam — só aparência | cumprido e verificado |

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

### Rodada 1 do supervisor — REPROVADO

Bloqueador: `lineHeight` no `bodyLarge`. Ele é o estilo herdado por todo `Text`
sem estilo próprio, e o app troca `fontSize` inline em centenas de lugares sem
trocar a entrelinha — inclusive na tela do Pomodoro, que o usuário pediu para
não mexer. Corrigido antes do parecer chegar (mesmo achado, encontrado nas duas
pontas).

Demais correções aplicadas a partir do parecer:
- Métricas pintava um halo próprio por cima do halo da tela principal quando
  aparecia como aba, criando um corte horizontal. Agora só pinta fundo quando é
  tela própria.
- `StatusPill` com acento escuro (o vermelho do jejum) tinha texto a 3.7:1.
  Texto passa a branco quando a luminância do acento é baixa.
- `ProfileScreen` tinha casca nova e cartão antigo lado a lado. Unificado.
- `FocusCard` só monta a máquina de toque quando é clicável; o `clickable` foi
  reposicionado depois do fundo, senão o brilho do toque ficava escondido.
- Faixa de acento do cartão de bloqueio deixou de cobrir a borda.
- Tokens mortos removidos, entre eles `AccentBlue` — era azul, não ciano, e
  diluiria a identidade se entrasse em uso.
- `dark_card_elevated` no XML estava espelhando o token errado.

### Rodada 2 do supervisor — APROVADO

Sem bloqueadores. Ele reconferiu, item a item: `bodyLarge` voltou ao
comportamento antigo e o relógio do Pomodoro está intacto; os demais estilos com
entrelinha não vazam porque nenhum é o estilo ambiente; o halo de Métricas está
correto nos dois caminhos (como aba e como rota própria); a pílula vermelha
subiu de 3.7:1 para 13.9:1 e ciano, âmbar e verde não regrediram; a ordem dos
modificadores do `FocusCard` está certa; os 18 pontos de chamada dele não
alternam entre clicável e não clicável, então os dois caminhos são estáveis.

Ajustes finais dele, aplicados:
- `gradient_start`/`gradient_end` no XML tinham ficado órfãos (eram o espelho
  dos tokens removidos). Trio removido e o comentário do arquivo passou a dizer
  quais recursos realmente têm uso.
- `FocusGuardAmbientBackground` ganhou `enabled` — desligar o halo é mais
  honesto que pintá-lo com cores invisíveis.
- Comentário do `FocusCard` dizia "vem depois de propósito" quando o correto é
  "precisa continuar depois".

### Pendente de verificação em aparelho

`headlineSmall` e `titleSmall` são estilos novos e alcançam telas que não foram
revisadas visualmente: `headlineSmall` é o título de todo `AlertDialog` do
Material 3 (~20 arquivos) e `titleSmall` é o rótulo das abas. Nenhum quebra
layout pela medição, mas vale abrir um diálogo no aparelho antes de fechar.

### Sugestões — o usuário mandou executar (objetivos 8 e 9)

Sobre a sugestão 1 (item 8): repaginação mecânica das quatro telas, sem tocar
em lógica. Modo de Foco, Configuração de Proteção e a cortina de bloqueio
continuam fora, pelo motivo já registrado.

Sobre a sugestão 2 (item 9): `enableEdgeToEdge()` segue descartado — muda o
contrato de margens de todas as telas e três Activities já mexem nisso por
conta própria; seria comportamento de janela, não aparência. Mas o propósito
dela (não haver emenda visível no topo) dá para cumprir de outro jeito: fazer
o halo começar transparente na primeira linha, encostando na cor exata da barra
de status, e só atingir o pico um pouco abaixo. Mesmo resultado, sem tocar em
inset nenhum.

### Texto original das sugestões oferecidas

1. Faixa preta da barra de status em Android 14 e anteriores. A correção
   (`enableEdgeToEdge()`) muda o contrato de margens de todas as telas e há
   caminhos sensíveis dependendo disso — é mudança de comportamento de janela
   disfarçada de aparência.
2. Repaginar Permissões, Lista de Sessões, Recuperação e Detalhe da Sessão.
   Troca mecânica de `Card` por `FocusCard`, sem tocar em lógica.
   Não repaginar: Modo de Foco, Configuração de Proteção e a cortina de
   bloqueio — muito estado de permissão e segurança acoplado.


## Verificação dos bloqueios (pedido explícito do usuário)

O usuário pediu cuidado redobrado com os bloqueios. Provas levantadas contra
`origin/main`, e não por leitura casual:

- Nenhum arquivo em `manager/`, `service/`, `security/`, `database/`,
  `receiver/`, `admin/`, `utils/`, `data/`, `focusmode/`, `pomodoro/` ou
  `analytics/` aparece no diff. Só `ui/compose/` e `colors.xml`.
- Nenhum arquivo de strings mudou — em nenhum idioma. O único recurso alterado
  é `colors.xml`.
- O enum `BlockTypeUi` é idêntico byte a byte: mesmos nomes, mesma ordem
  (PASSWORD, DAILY_LIMIT, DOPAMINE_FAST), mesmos ícones, cores e `entriesOf`.
- Nas quatro telas que tocam bloqueio (BlockTypeDetail, SessionsList,
  SessionDetail, Main), a lista de chamadas a `BlockingSessionManager`,
  `AuthManager`, `WebsiteBlocker`, `PredefinedApps`, `BlockCountdownPolicy` e ao
  banco é idêntica antes e depois, chamada por chamada.
- A separação das listas em aplicativos e sites (`filterNot { it.isWebsite }` /
  `filter { it.isWebsite }`) e a ordem das seções continuam as mesmas; as linhas
  só mudaram de indentação.
