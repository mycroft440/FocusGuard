# Plano de implementação — fórum compartilhado em Apps Script

## Diagnóstico
- [x] O fórum era 100% local (SharedPreferences): cada aparelho via só os próprios posts.
- [x] O plano "drive ready" deixou o contrato `ForumRepository`, mas nenhum adaptador remoto.
- [x] Uma conta única do Drive acessada direto pelo app exigiria credencial dentro do APK.
- [x] O `userId` era um UUID local, forjável e perdido ao reinstalar (backup desativado).
- [x] A tela chamava o repositório em `scope.launch` sem `catch`: erro de rede fecharia o app.
- [x] Não havia paginação, exclusão, denúncia nem moderação (exigências do Google Play para UGC).

## Implementação
- [x] Backend `forum-backend/Code.gs`: App da Web executado como a conta dona, dados numa planilha.
- [x] Login com Google verificado no servidor; sessão HMAC de 90 dias; `sub` nunca exposto.
- [x] Escritas sob `LockService`; contadores recalculados a partir das linhas.
- [x] Texto gravado como string JSON (sem fórmula, número ou data por engano).
- [x] Paginação por cursor, curtidas únicas, comentários, notificações, exclusão, denúncias.
- [x] Moderação pela planilha (`status` hidden/banned) e ocultação automática com 3 denúncias.
- [x] Limite de requisições por usuário (CacheService).
- [x] `RemoteForumRepository` + `HttpForumTransport` (redirecionamento do Apps Script tratado à mão).
- [x] Sessão no cofre criptografado (`SecureForumSessionStore`).
- [x] `ForumGoogleSignIn` com Credential Manager; regra R8 para o provedor do Play Services.
- [x] `ForumViewModel`: estados de erro, nova tentativa, curtida otimista, carregar mais, sair.
- [x] Tela: cartão de login, atualizar, carregar mais, excluir e denunciar posts e comentários.
- [x] `ForumPostStore` (modo local) ganhou exclusão de post e comentário.
- [x] Configuração em `gradle.properties` (`focusguard.forumBackendUrl`, `focusguard.forumGoogleWebClientId`); vazia mantém o modo local.
- [x] Strings novas em todos os idiomas (pt e en traduzidas; demais em inglês, como o resto do fórum).
- [x] Guia de instalação em `forum-backend/README.md` e seção do fórum na política de privacidade.

## Validação
- [x] Testes do backend no Node com imitação do Apps Script/Sheets (`node --test forum-backend/test/forum.test.js`).
- [x] Testes de `RemoteForumRepository`, `ForumViewModel`, `ForumBackendConfig` e exclusão local.
- [x] Executar unit tests (1.100 testes, 0 falhas) e `compileDebugKotlin`.
- [ ] Executar Android Lint (a rodada local parou no 429 do Maven Central; fica para a CI).
- [ ] Executar compilação do harness de performance (idem).

## Pendências
- [ ] Publicar o script e preencher `gradle.properties` (exige a conta Google do dono).
- [ ] Notificação push (FCM) — o Apps Script não tem como acordar outro aparelho.
- [ ] Silenciar/bloquear outra pessoa só para si.
