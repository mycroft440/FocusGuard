# Plano de implementação — fórum preparado para armazenamento remoto

## Diagnóstico
- [x] O fórum atual persiste posts, curtidas e comentários em um único JSON no SharedPreferences.
- [x] Comentários são carregados junto com todos os posts, mesmo quando a seção está recolhida.
- [x] Curtidas usam apenas `likedByMe`, sem identidade estável de usuário.
- [x] O perfil local possui nome/avatar, mas ainda não possui `userId` persistente.
- [x] A UI chama diretamente `ForumPostStore`, acoplando apresentação e persistência.
- [x] A integração Google Drive ainda não pode ser implementada sem credenciais/configuração do projeto.

## Implementação
- [x] Criar identidade estável `userId` no perfil local, mantendo compatibilidade com perfis existentes.
- [x] Introduzir contrato de repositório do fórum para desacoplar UI do mecanismo de armazenamento.
- [x] Separar comentários da lista principal de posts no contrato e carregá-los somente ao expandir.
- [x] Manter `commentsCount` no resumo do post.
- [x] Tornar curtidas únicas por `postId + userId` no armazenamento local.
- [x] Adicionar convenções determinísticas de pastas/arquivos compatíveis com o futuro adaptador Google Drive.
- [x] Preservar leitura dos posts/comentários legados já salvos no SharedPreferences.
- [x] Manter a experiência visual e as strings atuais do fórum.

## Validação
- [x] Cobrir identidade estável, curtidas únicas, comentários separados e compatibilidade legada com testes.
- [x] Revisar diff por mudanças não solicitadas.
- [x] Executar unit tests.
- [x] Executar Android Lint.
- [x] Executar compilação debug/performance aplicável.
