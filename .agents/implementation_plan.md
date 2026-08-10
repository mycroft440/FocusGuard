# Plano de implementação — métricas de uso

## Objetivos

1. Contabilizar um acesso ao aplicativo somente depois de entrar e sair dele.
2. Exibir o tempo real de uso do telefone nos últimos 7 dias.
3. Mostrar os períodos de maior e menor uso médio em blocos de 3 horas.

## Regras de contabilização

- Apenas aplicativos realmente visíveis em primeiro plano geram tempo de uso.
- Trocas de Activity no mesmo app permanecem na mesma sessão.
- Trocar de aplicativo, apagar a tela ou exibir a tela de bloqueio encerra a
  sessão atual.
- O aplicativo ainda visível no fim da consulta é contabilizado até aquele
  instante.
- Launchers e componentes sem inicialização pelo usuário não entram no total.
- Sessões que atravessam meia-noite ou um limite de 3 horas são divididas entre
  os intervalos correspondentes.
- O histórico sempre contém os 7 dias, inclusive dias com zero uso.
- Maior e menor período usam os 7 dias completos anteriores; hoje não entra na
  média para não favorecer horários que ainda não aconteceram.

## Validação

- Testar sessões simples, sessão ainda aberta, troca interna de Activities,
  troca de apps, tela desligada e pacotes não elegíveis.
- Testar dias sem uso, virada da meia-noite, limites de 3 horas e exclusão do
  dia atual incompleto.
- Executar testes unitários, lint e compilação dos APKs.
