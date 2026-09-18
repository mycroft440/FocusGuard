# Plano de implementação — curtidas e comentários no Fórum

## Diagnóstico
- [x] ForumPost persiste hoje apenas autor, avatar, corpo e data.
- [x] ForumPostStore usa JSON em SharedPreferences e precisa continuar lendo posts antigos.
- [x] ForumPostCard termina após o corpo e é o ponto correto para ações sociais.
- [x] O projeto ainda não possui backend; interações desta versão permanecem locais.

## Implementação
- [ ] Adicionar contador/estado de curtida ao ForumPost com defaults retrocompatíveis.
- [ ] Adicionar ForumComment persistido por post.
- [ ] Implementar curtir/descurtir no store.
- [ ] Implementar publicação de comentário no store.
- [ ] Adicionar barra Curtir/Comentar abaixo de cada post.
- [ ] Expandir comentários e editor ao tocar em Comentar.
- [ ] Mostrar autor, avatar, horário e texto de cada comentário.
- [ ] Adicionar limite e normalização de comentários.
- [ ] Atualizar aviso de persistência local e strings pt/en.
- [ ] Cobrir curtidas, comentários e leitura de JSON legado com testes.

## Validação
- [ ] Revisar diff e compatibilidade com posts já salvos.
- [ ] Verificar que navegação e criação de posts não mudaram.
- [ ] Executar Unit Tests.
- [ ] Executar Android Lint.
- [ ] Executar Compile Performance Harness.
