# Plano de implementação — fórum preparado para armazenamento remoto

## Diagnóstico
- [x] O fórum atual persiste posts, curtidas e comentários em um único JSON no SharedPreferences.
- [x] Comentários são carregados junto com todos os posts, mesmo quando a seção está recolhida.
- [x] Curtidas usam apenas `likedByMe`, sem identidade estável de usuário.
- [x] O perfil local possui nome/avatar, mas ainda não possui `userId` persistente.
- [x] A UI chama diretamente `ForumPostStore`, acoplando apresentação e persistência.
- [x] A integração Google Drive ainda não pode ser implementada sem credenciais/configuração do projeto.

## Implementação
- [ ] Criar identidade estável `userId` no perfil local, mantendo compatibilidade com perfis existentes.
- [ ] Introduzir contrato de repositório do fórum para desacoplar UI do mecanismo de armazenamento.
- [ ] Separar comentários da lista principal de posts no contrato e carregá-los somente ao expandir.
- [ ] Manter `commentsCount` no resumo do post.
- [ ] Tornar curtidas únicas por `postId + userId` no armazenamento local.
- [ ] Adicionar convenções determinísticas de pastas/arquivos compatíveis com o futuro adaptador Google Drive.
- [ ] Preservar leitura dos posts/comentários legados já salvos no SharedPreferences.
- [ ] Manter a experiência visual e as strings atuais do fórum.

## Validação
- [ ] Cobrir identidade estável, curtidas únicas, comentários separados e compatibilidade legada com testes.
- [ ] Revisar diff por mudanças não solicitadas.
- [ ] Executar unit tests.
- [ ] Executar Android Lint.
- [ ] Executar compilação debug/performance aplicável.
