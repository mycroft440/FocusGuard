# Plano de implementação — sugestões e métricas mensais

## Objetivos

1. Não exibir o convite do Instagram durante a primeira hora após a instalação.
2. Apresentar o card uma única vez quando a tela Proteção estiver visível.
3. Remover automaticamente o card após 15 segundos ou depois do toque.
4. Manter o atalho disponível no menu do canto superior após a apresentação.
5. Alinhar o aviso de permissões sem sobrepor o cabeçalho da tela.
6. Exibir permanentemente na tela Proteção um botão discreto de sugestões que leva ao Instagram do criador.
7. Calcular os horários de maior e menor uso sobre os últimos 30 dias completos.
8. Fazer o HardBlock retirar imediatamente qualquer app bloqueado do primeiro plano.
9. Impedir acesso às Informações do app, desinstalação e controles destrutivos do próprio FocusGuard enquanto uma proteção estiver ativa.
10. Fazer a primeira tentativa de abertura de um app bloqueado ser tão rápida quanto as tentativas seguintes.
11. Bloquear a entrada em Apps de administrador do dispositivo durante uma proteção ativa, impedindo chegar à remoção da permissão do FocusGuard.
12. Bloquear a entrada em Aplicativos/Serviços instalados dentro de Acessibilidade durante uma proteção ativa, impedindo chegar ao interruptor que desativa o serviço do FocusGuard.
13. Proteger o menu de energia sem exigir Device Owner, bloqueando o gesto prolongado que aciona Modo Seguro e preservando desligar, reiniciar, emergência e informações médicas por cliques simples encaminhados ao System UI.
14. Bloquear o atalho de Apps do administrador do dispositivo exibido pelo System UI após uma tentativa de desinstalação enquanto houver proteção ativa.
15. Manter “Siga o criador no Instagram” como o último item acionável do menu de Configurações.
16. Aplicar o efeito de sumir gradualmente e reaparecer instantaneamente somente ao botão “Sugestões de melhorias ou funções” da tela Proteção.
17. Reduzir ao mínimo a latência da primeira tentativa de remover/desinstalar o HardBlock, priorizando decisão e resposta visual antes de Binder, árvore de acessibilidade, refresh assíncrono e navegação.
18. Unificar a saída autenticada do HardBlock na senha mestre global, com mínimo de 4 caracteres, removendo todas as fontes de bloqueio e liberando as proteções essenciais antes da desinstalação.
19. Reduzir ao limite da plataforma a latência ao abrir apps bloqueados, Acessibilidade, Informações do app, Apps administradores do aparelho e o menu de energia, sem introduzir falsos positivos nem janelas sem proteção.
20. Avaliar se um launcher próprio/default do FocusGuard reduziria ainda mais a latência sem tentar clonar recursos privados do launcher OEM e, depois da decisão técnica e validação, publicar a implementação aprovada na `main`.

## Estado da solicitação atual

- CUMPRIDO: tornar permanente o botão discreto de sugestões/Instagram.
- CUMPRIDO: identificar que o bloqueio comum apenas abria a tela do HardBlock sobre o app bloqueado.
- CUMPRIDO: fazer o fluxo de app bloqueado enviar o usuário para a Home antes de abrir a tela de bloqueio, com fallback explícito para o launcher se a ação global falhar.
- CUMPRIDO: manter o bloqueio de sites fora dessa mudança.
- CUMPRIDO: persistir em armazenamento protegido a lista de apps/sites atualmente bloqueados e o estado do Pomodoro rigoroso.
- CUMPRIDO: restaurar os alvos do HardBlock sincronamente quando o serviço de acessibilidade inicia, sem aguardar o Room.
- CUMPRIDO: manter o fluxo normal de `TYPE_WINDOW_STATE_CHANGED`, que já usa diretamente o pacote informado pelo evento, evitando um atalho redundante que poderia interferir no Pomodoro ou no Modo Foco.
- CUMPRIDO: fazer toda tentativa protegida nas Configurações expulsar imediatamente o app Configurações para a Home; o debounce agora limita apenas aviso visual/toast.
- CUMPRIDO: reconhecer também as telas modernas de Informações do app baseadas em `SpaActivity`/`SpaAppBridgeActivity`, mantendo a exigência de identidade do FocusGuard.
- CUMPRIDO: revisar a alteração com o Crítico e remover o fast path que poderia alterar fluxos especiais, preservando apenas a correção da causa da primeira tentativa lenta.
- CUMPRIDO: bloquear o gateway de Apps de administrador do dispositivo sem esperar o usuário chegar ao botão de desativação.
- CUMPRIDO: preservar a exceção de ativação inicial do administrador quando ela é aberta e identificada como sendo do próprio FocusGuard.
- CUMPRIDO: bloquear o clique em Aplicativos/Serviços instalados dentro do contexto de Acessibilidade e a transição identificada para essa lista.
- CUMPRIDO: manter uma opção genérica de “Apps instalados” fora de Acessibilidade livre para evitar bloqueio excessivo das Configurações.
- CUMPRIDO: classificar o menu de energia somente no System UI, usando classe conhecida ou assinatura real de ações para evitar falsos positivos.
- CUMPRIDO: criar menu de energia protegido com `TYPE_ACCESSIBILITY_OVERLAY` opaco e tocável para impedir que o usuário toque ou segure os botões nativos.
- CUMPRIDO: encaminhar Desligar, Reiniciar, Emergência e Informações médicas exclusivamente por `ACTION_CLICK` nos controles nativos, nunca `ACTION_LONG_CLICK`.
- CUMPRIDO: consumir long-press nos botões do menu HardBlock e manter Cancelar para fechar o menu nativo com segurança.
- CUMPRIDO: manter `DISALLOW_SAFE_BOOT` como segunda camada quando Device Owner estiver disponível.
- CUMPRIDO: ligar o controlador ao `BlockingAccessibilityService` antes das demais decisões do System UI e removê-lo ao desarmar a proteção, desligar a tela, interromper ou destruir o serviço.
- CUMPRIDO: revisar com o Crítico e corrigir a seleção de janela para priorizar o `windowId` do evento e aceitar apenas janelas do System UI que realmente correspondam ao menu de energia.
- CUMPRIDO: bloquear no System UI o clique do atalho de administrador do dispositivo exibido após falha de desinstalação.
- CUMPRIDO: manter “Siga o criador no Instagram” no fim das Configurações e ocultar seu antigo card temporário da tela Proteção.
- CUMPRIDO: aplicar exclusivamente ao botão de sugestões um ciclo de fade-out seguido de reaparecimento instantâneo.
- CUMPRIDO: mover o refresh assíncrono para depois do fast path de autoproteção, eliminando trabalho não crítico antes da decisão.
- CUMPRIDO: usar primeiro o snapshot `@Volatile` já restaurado e evitar consultas DevicePolicyManager/SharedPreferences no caminho comum quando a proteção já está ativa.
- CUMPRIDO: consumir eventos de transição cobertos pelo guard sem reler classe, texto, source ou root da árvore de acessibilidade.
- CUMPRIDO: reduzir as buscas de nós no clique a poucos localizadores e só expandir o contexto quando o próprio nó não contém marcador útil.
- CUMPRIDO: classificar textos em lote, pré-normalizar dicionários e reutilizar regex para reduzir alocações e CPU por evento.
- CUMPRIDO: pré-construir a cortina de bloqueio e exibi-la antes de solicitar HOME, cobrindo os frames da transição do sistema.
- CUMPRIDO: adicionar telemetria assíncrona evento→cortina→HOME para medir a latência real sem atrasar a resposta.
- CUMPRIDO: pré-carregar e manter em memória a janela de desinstalação autenticada, retirando SharedPreferences/Settings.Global do caminho comum da primeira tentativa bloqueada.
- CUMPRIDO: revisão do Crítico manteve “admin” apenas como localizador, reutilizou o cache de Modo Foco/Device Owner e evitou falsos negativos por contexto incompleto.
- CUMPRIDO: CI #759 detectou uma dependência de ordem na inicialização estática dos classificadores; os regex reutilizados agora são inicializados antes dos dicionários pré-normalizados, eliminando o `ExceptionInInitializerError` sem mudar as regras de classificação.
- CUMPRIDO: adicionar Baseline Profile conservador e ProfileInstaller 1.4.1 para otimizar o hot path também em APKs release instalados por sideload.
- CUMPRIDO: senha mestre aceita a partir de 4 caracteres e continua armazenada como verificador PBKDF2 com salt.
- CUMPRIDO: a opção interna de remoção/desinstalação usa a mesma senha mestre global em vez de senha, padrão ou biometria de bloqueios individuais.
- CUMPRIDO: a saída mestre remove sessões, limites, Pomodoro, filtro adulto e autoproteção; libera Device Owner/Device Admin; encerra Modo Foco; desativa o serviço de Acessibilidade; e só então abre a superfície Android solicitada.
- CUMPRIDO: senha incorreta ou cancelamento não liberam nenhuma proteção; ausência de senha configurada é reportada separadamente.
- CUMPRIDO: código funcional validado por Unit Tests, Android Lint, APK/AAB Release e APK Debug no CI #793; a alteração posterior neste arquivo é somente documentação do resultado.
- CUMPRIDO: encerrar o ciclo anterior validado no CI #793; integração em `main` não faz parte desta iteração.

## Iteração atual — bloqueio no primeiro sinal disponível

- CUMPRIDO: confirmar na documentação oficial que somente a suspensão por Device Owner impede nativamente o início da Activity; no modo consumidor, Acessibilidade permanece best effort a partir do primeiro evento entregue.
- CUMPRIDO: criar índice pré-calculado de labels de Activities do launcher, com unicidade global, invalidação por pacote/locale/launcher e refresh periódico separado do cache de Room.
- CUMPRIDO: limitar o fast path a classes prováveis de ícone, rejeitar Folder/Widget/Shortcut, impedir colapso de labels permitidos/ambíguos e aceitar sufixo apenas quando há contador numérico.
- CUMPRIDO: pré-anexar a cortina de bloqueio inerte e torná-la tocável por `updateViewLayout` antes de HOME/navegação, sem cooldown que deixe uma tentativa repetida descoberta.
- CUMPRIDO: manter tokens de geração e confirmação de frame/tela segura estritamente dentro do processo; callbacks antigos ou externos não podem ocultar uma cortina nova.
- CUMPRIDO: validar janelas visíveis após o ACK para manter a cortina quando app bloqueado ou Settings protegido ainda estiver visível em split-screen/freeform ou surgir atrasado.
- CUMPRIDO: antecipar Acessibilidade, Informações do app e Apps administradores por campos diretos do evento, usando árvore somente nos casos ambíguos e preservando identidade exata do HardBlock.
- CUMPRIDO: remover o fluxo que reabria deliberadamente o app bloqueado para limpar sua task, pois contradizia o requisito de impedir sua abertura.
- CUMPRIDO: pré-anexar o overlay do menu de energia, reconhecer classes confiáveis antes da árvore e fechar com estado BACK→HOME→hard cap sem ocultar no mesmo tick nem permitir storm adiar rechecks.
- CUMPRIDO: atualizar o Baseline Profile com o índice imediato e o controlador/política do menu de energia.
- CUMPRIDO: concluir a revisão estática em loop com aprovação condicionada do supervisor, incluindo falhas fail-closed, gerações stale, SystemUI parcial/textless, refresh resiliente do launcher, SCREEN_OFF/onInterrupt e o state machine completo do menu de energia.
- CUMPRIDO: manter o clique de app bloqueado como primeiro classificador do launcher e reutilizar a mesma extração direta de valores antes de qualquer classificação de App info.
- CUMPRIDO: consolidar a implementação e os testes de regressão na branch local `perf/instant-blocking`, sem publicar alterações externas.
- EM ANDAMENTO: autorização explícita recebida; publicar a branch em PR draft e validar Unit Tests, Android Lint, baseline merge, APK/AAB Release e APK Debug no CI; não marcar validação como cumprida antes dos jobs verdes.
- PRÓXIMO OBJETIVO: executar matrix física Pixel/AOSP + One UI para launcher/badges, App info/Admin/Acessibilidade/disclosure, split-screen/freeform, screen-off/unlock e power menu; medir p50/p95 de evento→cortina e observar o destino fail-closed de eventos isolados `HardBlock`.

## Iteração atual — decisão sobre launcher e publicação

- CUMPRIDO: confirmar na documentação oficial e na arquitetura local que um launcher próprio poderia negar o `startActivity` antes do lançamento somente para interações originadas nele, sem cobrir Recentes, notificações, links, outros apps, Configurações ou menu de energia.
- CUMPRIDO: concluir que não existe cópia portátil do launcher OEM: a função HOME exige consentimento no modo comum, o intercâmbio de workspace é opcional e UI, gestos, widgets configurados e integrações privadas não são reproduzidos pelas APIs públicas.
- CUMPRIDO: revisão crítica do supervisor aprovou excluir o launcher deste checkpoint; se solicitado depois, a alternativa correta é um FocusGuard Launcher próprio, opt-in, isolado em outra branch e medido como subsistema separado.
- CUMPRIDO: o primeiro CI do PR #65 detectou que enums de decisão declarados no `companion object` não eram acessíveis pelos nomes de classe usados nos testes; movê-los para o escopo da classe preserva a lógica e torna o contrato interno testável.
- EM ANDAMENTO: publicar a branch existente em PR draft, executar CI completo e integrar na `main` somente após todos os jobs obrigatórios aprovarem; confirmar também o workflow disparado pelo push em `main` antes de encerrar.

## Regras de apresentação

- A idade da instalação usa `PackageInfo.firstInstallTime` e conserva o prazo em
  atualizações do aplicativo.
- O card não é consumido em segundo plano, em outra aba ou durante uma sessão de
  foco; ele só é registrado quando Proteção está aberta em primeiro plano.
- A apresentação concluída é persistida no aparelho para não se repetir.
- Após a apresentação, o acesso em Configurações permanece disponível.
- O botão de sugestões permanece disponível na tela Proteção independentemente
  de o convite inicial já ter sido apresentado ou estar visível.
- O botão de sugestões usa aparência neutra, sem gradiente, logotipo ou cores
  fortes do Instagram.
- O destino tenta abrir o app do Instagram e mantém o fallback para navegador.
- Cabeçalho, aviso de permissões e cards usam o mesmo fluxo vertical rolável,
  sem deslocamentos absolutos dependentes do tamanho da tela.
- A análise mensal mantém blocos de 3 horas, usa uma janela móvel de 30 dias
  completos e não deixa o dia atual parcial distorcer a média.

## Validação

- Testar o limite exato de uma hora e a duração da apresentação.
- Testar que a apresentação persiste e não pode se repetir.
- Testar que o botão de sugestões permanece disponível antes, durante e depois do convite inicial.
- Testar que a média mensal inclui exatamente 30 dias completos.
- Testar que o snapshot persistido restaura os pacotes bloqueados antes da atualização assíncrona do banco.
- Testar que um snapshot inativo nunca conserva alvos antigos.
- Testar que Informações do app modernas do FocusGuard são reconhecidas sem afetar outros apps.
- Testar que Apps de administrador do dispositivo é barrado durante proteção ativa.
- Testar que a ativação legítima do administrador do FocusGuard continua permitida durante a janela autorizada.
- Testar que Aplicativos/Serviços instalados de Acessibilidade é barrado, mas “Apps instalados” fora de Acessibilidade não é.
- Testar reconhecimento do menu AOSP/One UI em português e inglês e rejeição de notificações comuns do System UI.
- Testar que as ações protegidas são classificadas como clique simples e que nenhum caminho usa `ACTION_LONG_CLICK`.
- Conferir que Pomodoro e Modo Foco continuam seguindo seus fluxos próprios.
- Conferir paridade dos recursos em português e inglês.
- Executar testes unitários, lint e compilação dos APKs.

## Correção de regressão — senha mestre e AntiPorn
- CUMPRIDO: Restaurar exatamente o AntiPorn fixo sem rolagem e com frases rotativas da versão aprovada.
- CUMPRIDO: reconhecer “Informações do aplicativo” e “Apps administradores do sistema” como gateways protegidos.
- CUMPRIDO: endurecer “Acessibilidade → Aplicativos instalados” para detectar rótulos em nós filhos do One UI, confirmar o contexto de Acessibilidade e abrir a senha mestre sem bloquear “Aplicativos instalados” genérico fora dessa área.
- CUMPRIDO: ao tentar remover/desinstalar/perder permissões, bloquear instantaneamente e abrir o gate da senha mestre.
- CUMPRIDO: senha mestre autorizada remove todas as fontes de bloqueio e libera Device Owner/Device Admin/Acessibilidade antes de devolver o usuário ao Android.
- CUMPRIDO: mínimo da senha mestre reduzido de 8 para 4 caracteres.
- CUMPRIDO: desinstalação interna unificada com o gate de senha mestre global.
- CUMPRIDO: Modo Foco incluído na remoção mestre após a liberação das políticas administrativas.
- CUMPRIDO: CI funcional #793 completo aprovado; último commit é documentação.
- PRÓXIMO OBJETIVO: concluir a iteração atual de bloqueio imediato e seu CI antes de qualquer decisão separada sobre integração em `main`.
