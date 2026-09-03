# FocusGuard — Playbook de Monetização com Anúncios

> **Status:** instrução obrigatória para qualquer pessoa ou agente que adicionar, mover ou alterar anúncios no FocusGuard.
> **Última revisão:** 2026-09-03.
> **Objetivo:** maximizar **receita líquida por usuário ao longo do tempo**, sem sacrificar retenção, confiança, estabilidade ou conformidade com AdMob/Google Play.

## 1. Regra principal de lucro

Não maximize apenas o número de impressões. Maximize o conjunto:

**Receita por usuário × retenção × show rate × eCPM × fill/match rate − perda de usuários − risco de política.**

Uma posição com eCPM alto pode diminuir a receita total se fizer o usuário abandonar o app. Toda nova posição deve justificar por que aumenta o LTV/ARPDAU e não apenas impressões por sessão.

## 2. Estado atual do projeto

- O app usa Google Mobile Ads Next-Gen SDK e UMP/consentimento centralizado.
- `FocusGuardAds` é o ponto único de integração. Não criar carregadores paralelos de anúncios em telas individuais.
- **Debug e Release usam intencionalmente IDs oficiais de teste do Google.** Enquanto isso permanecer assim, o app gera **R$ 0** de receita real. Não trocar por IDs reais sem autorização explícita do responsável pelo projeto.
- O Pomodoro mantém conclusão de anúncio em fila persistente; término natural e encerramento manual geram uma oportunidade de intersticial.
- Rewarded deve creditar somente pelo callback de recompensa; fechar ou falhar não conta.

## 3. Ordem de preferência dos formatos

### 3.1 Rewarded — prioridade máxima quando há valor extra claro

Use rewarded para liberar **benefícios opcionais e adicionais**. Em geral, é o melhor formato para monetizar usuários engajados sem interromper uma tarefa.

Regras obrigatórias:
- mostrar claramente **o que o usuário recebe e quantos anúncios precisa assistir** antes de cada pacote;
- cada anúncio rewarded padrão precisa de opt-in afirmativo do usuário;
- se forem 3 anúncios, cada visualização deve partir de uma ação explícita; não encadear automaticamente;
- a recompensa deve ser interna ao app, não monetária e não transferível;
- conceder a recompensa somente após o callback real de recompensa;
- persistir progresso/crédito quando a recompensa exigir múltiplos anúncios.

No FocusGuard, prefira monetizar **capacidade adicional** (slots extras, funções opcionais, personalizações) em vez de bloquear a função central do app atrás de publicidade.

### 3.2 Interstitial — somente em transições naturais

Use intersticial apenas quando a tarefa terminou e existe uma pausa lógica. Bons exemplos no FocusGuard:
- fim do plano completo de Pomodoro;
- encerramento manual do Pomodoro, depois de salvar/limpar o estado;
- conclusão de um fluxo de configuração, quando não houver outro anúncio fullscreen imediatamente antes/depois.

Proibido/evitar:
- ao sair do app;
- no meio de formulário/configuração;
- inesperadamente enquanto o usuário está focado em uma tarefa;
- imediatamente depois de outro intersticial;
- a cada clique/ação;
- em segundo plano ou por cima de outro app.

**Limite mínimo de política:** nunca mais de um intersticial a cada duas ações do usuário. Na prática, usar frequência ainda menor quando retenção cair.

Sempre prefira **prefetch** do próximo intersticial em momento seguro, mas só mostre quando a Activity estiver `RESUMED`. Se o app estiver fechado, registre pendência e aguarde a próxima abertura/resume.

### 3.3 Native — alto valor em telas de conteúdo/estatística

Bom para `Impacto do bloqueio`, relatórios e telas com leitura mais longa. O native deve ficar inline, claramente identificado como anúncio e sem imitar controles do app.

Maximize demanda permitindo criativos de mídia/vídeo quando o SDK/formato suportar. Não coloque native colado a botões de navegação ou ações perigosas.

### 3.4 Adaptive Banner — receita contínua de baixa fricção

Use em telas de permanência relativamente longa, com separação clara de botões. Prefira **anchored adaptive banner** dimensionado à largura disponível.

Evite dois banners simultâneos na mesma tela. Se existir native + banner, medir retenção, saída da tela e receita incremental; remover o segundo formato se o ganho não compensar a piora de UX.

### 3.5 App Open — só com dados que justifiquem

Não adicionar por padrão. O Google recomenda esse formato para apps com aberturas/retornos frequentes e experiência clara de carregamento. Se o FocusGuard não tiver esse padrão de uso, rewarded/native/interstitial em transições tendem a ser escolhas melhores.

Se um dia for testado:
- mostrar apenas ao abrir/retornar ao app, integrado ao carregamento;
- nunca depois que o usuário já começou a interagir com o conteúdo;
- não colocar outro fullscreen imediatamente antes/depois;
- aplicar frequency cap.

## 4. Como maximizar receita tecnicamente

### 4.1 Aumentar show rate antes de aumentar quantidade de anúncios

Para fullscreen:
1. carregar antecipadamente quando houver consentimento e Activity válida;
2. controlar validade/expiração do objeto carregado;
3. não reservar/consumir a oportunidade antes de o anúncio estar pronto;
4. se falhar antes de aparecer, restaurar a oportunidade quando fizer sentido;
5. usar `AtomicBoolean`/mutex para impedir dois fullscreen simultâneos.

### 4.2 Mediação e bidding quando os IDs reais forem ativados

Quando o app entrar em monetização real:
- usar AdMob Mediation;
- priorizar **bidding em tempo real** para fazer redes competirem pelo mesmo pedido;
- para waterfall, ativar otimização de eCPM quando suportada;
- segmentar grupos por formato, plataforma e, quando houver volume suficiente, geografia;
- adicionar fontes gradualmente e medir fill, show rate, eCPM e latência.

Não adicionar SDKs de redes apenas por quantidade. Cada rede aumenta tamanho do app, tempo de inicialização, superfície de privacidade e risco de crashes.

### 4.3 Frequency caps e experimentos

Toda nova posição fullscreen precisa de limite de frequência e hipótese mensurável.

Antes/depois ou A/B test deve acompanhar pelo menos:
- ARPDAU/receita por usuário ativo;
- eCPM por formato;
- match/fill rate;
- show rate (impressões / anúncios carregados ou oportunidades elegíveis);
- retenção D1/D7 quando houver volume;
- sessões por usuário;
- taxa de abandono da tela após anúncio;
- crashes/ANRs;
- opt-in e completion rate de rewarded.

**Critério de decisão:** manter uma posição somente se o ganho de receita não vier acompanhado de deterioração relevante de retenção, conclusão de tarefas ou confiança do usuário.

## 5. Regras específicas do FocusGuard

1. **Nunca** mostrar fullscreen enquanto um bloqueio crítico ainda não foi persistido/encerrado. Primeiro salvar estado, depois anúncio.
2. **Nunca** interromper uma sessão de Pomodoro com anúncio; somente término natural do plano ou encerramento manual.
3. Se o Pomodoro terminar com app fechado, manter a pendência e mostrar somente quando o FocusGuard estiver visível e `RESUMED`.
4. Não mostrar anúncios sobre a tela de bloqueio, AccessibilityService, Settings do Android ou outro app.
5. Rewarded não pode conceder crédito em `onAdDismissed`; somente no callback de recompensa.
6. Pacotes (ex.: 3 anúncios) devem preservar progresso após rotação/process death.
7. Falha/no-fill não pode quebrar uma função já concluída nem prender navegação.
8. UMP/consentimento deve ser respeitado antes de qualquer request.
9. Não criar novos IDs hardcoded espalhados. Todas as unidades passam por `FocusGuardAds`/configuração central.
10. Enquanto o projeto estiver em modo de testes, **manter IDs oficiais de teste em Release**, conforme decisão atual do projeto.

## 6. Estratégia de expansão recomendada

Prioridade para novas oportunidades:
1. rewarded em benefícios extras com valor percebido alto;
2. otimização/prefetch das posições existentes para elevar show rate;
3. native em novas telas de estatística/conteúdo longo;
4. adaptive banner em telas de permanência longa;
5. novos intersticiais somente em novas transições naturais e com frequency cap;
6. mediação/bidding quando houver IDs reais e tráfego suficiente;
7. App Open somente depois de análise do padrão de retorno ao app.

**Não** tentar maximizar lucro adicionando intersticiais a cada navegação. Isso aumenta atividade acidental, risco de política e churn e pode diminuir LTV.

## 7. Checklist obrigatório antes de mergear um novo anúncio

- [ ] A posição é uma transição natural ou um opt-in explícito?
- [ ] O anúncio não bloqueia uso normal nem aparece sobre outro app?
- [ ] Consentimento UMP é checado?
- [ ] Existe proteção contra anúncio fullscreen duplicado?
- [ ] Existe tratamento de load failure/show failure?
- [ ] O estado funcional do app é persistido antes do anúncio?
- [ ] Rewarded entrega exatamente a recompensa prometida e só no callback correto?
- [ ] Pacotes rewarded preservam progresso?
- [ ] Há frequency cap/critério de elegibilidade?
- [ ] Há eventos/métricas para avaliar receita **e retenção**?
- [ ] IDs continuam centralizados?
- [ ] Testes/Lint/CI estão verdes?
- [ ] A documentação deste playbook continua verdadeira após a mudança?

## 8. Fontes oficiais — revisar antes de mudanças relevantes

As políticas podem mudar. Antes de uma alteração grande de monetização, conferir novamente:

- Interstitial guidance: https://support.google.com/admob/answer/6066980
- Interstitial implementations not allowed: https://support.google.com/admob/answer/6201362
- Rewarded ad-unit policies: https://support.google.com/admob/answer/7313578
- Ad formats overview: https://support.google.com/admob/answer/6128738
- App Open best practices: https://support.google.com/admob/answer/9341964
- AdMob Mediation guide: https://support.google.com/admob/answer/13420272
- Waterfall optimization: https://support.google.com/admob/answer/7374110

> **Regra de atualização:** se uma política oficial nova conflitar com este documento, a política oficial prevalece e este playbook deve ser atualizado no mesmo PR/commit.
