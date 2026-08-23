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
- PRÓXIMO OBJETIVO: executar testes, lint e compilação de release; revisar o diff final e corrigir qualquer falha antes de integrar na `main`.

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
- Conferir que Pomodoro e Modo Foco continuam seguindo seus fluxos próprios.
- Conferir paridade dos recursos em português e inglês.
- Executar testes unitários, lint e compilação dos APKs.
