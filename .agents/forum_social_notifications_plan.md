# Plano de implementação — perfil social e notificações do fórum

## Diagnóstico
- [x] O fórum já possui `userId` estável, posts resumidos, likes únicos e comentários separados.
- [x] A UI ainda possui uma única visão de feed e não tem cabeçalho social.
- [x] O contrato do repositório ainda não expõe notificações nem filtro de posts por autor.
- [x] Likes recebem somente `userId`, portanto ainda não carregam nome/avatar do ator para uma notificação rica.
- [x] Sem backend/API remota ainda não existe um evento externo capaz de disparar notificação do sistema em outro aparelho.

## Implementação
- [ ] Adicionar modelo `ForumNotification` com tipo, destinatário, ator, post, data e estado lido.
- [ ] Criar notificações de like e comentário somente quando ator e autor forem usuários diferentes.
- [ ] Remover a notificação de like ao desfazer a curtida para evitar atividade obsoleta.
- [ ] Expor carregamento e marcação de notificações no `ForumRepository`.
- [ ] Expor carregamento de posts do próprio usuário sem duplicar persistência.
- [ ] Adicionar layout lógico `users/{userId}/notifications/` para o futuro adaptador Drive.
- [ ] Adicionar cabeçalho do fórum com avatar, nome e menu de três barras.
- [ ] Adicionar aba interna Fórum / Notificações com contador de não lidas.
- [ ] Adicionar visão Meus posts acessível pelo menu do perfil.
- [ ] Preservar feed, curtidas, comentários sob demanda e dados legados.
- [ ] Adicionar strings mantendo paridade de recursos suportados.

## Validação
- [ ] Cobrir notificações de like/comentário, autoações, desfazer like, leitura e filtro Meus posts.
- [ ] Revisar diff completo e mudanças fora de escopo.
- [ ] Executar Unit Tests.
- [ ] Executar Android Lint.
- [ ] Executar Compile Performance Harness.
