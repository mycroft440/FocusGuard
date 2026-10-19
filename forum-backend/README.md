# Backend do fórum (Google Apps Script)

O fórum do HardBlock fica numa única conta Google: um script do Apps Script, publicado
como App da Web, grava tudo numa planilha do Google Sheets no Drive dessa conta. O app
nunca recebe senha nem token da conta. Ele só conversa com o script por HTTPS.

```
App (login com Google) ──HTTPS──▶ Apps Script (executa como você) ──▶ Planilha no seu Drive
```

- **Identidade:** cada pessoa entra com a própria conta Google. O script confere o login
  com o Google uma vez e guarda só um código derivado da conta (HMAC com sal secreto).
  E-mail e nome do Google nunca são gravados. No fórum aparecem o nome e o avatar do
  perfil do app.
- **Sessão:** depois do login, o app recebe uma sessão assinada válida por 90 dias,
  guardada no cofre criptografado do aparelho.
- **Concorrência:** toda escrita passa por uma trava (`LockService`). Duas curtidas ao
  mesmo tempo nunca se sobrescrevem, e os contadores são recalculados a partir das linhas.
- **Texto seguro:** o texto digitado vai para a planilha como string JSON (`"..."`), então
  nada vira fórmula (`=IMPORTXML(...)`), número ou data.

## Quanto cabe e até onde aguenta

O espaço não é o limite que importa. Uma postagem tem no máximo 1.000 caracteres e ocupa
alguns KB, e 15 GB cabem milhões delas. Os limites reais são outros dois:

1. **Células da planilha.** O Google Sheets aceita até 10 milhões de células por arquivo.
   Um post com algumas curtidas, um comentário e as notificações usa umas 50 células. Isso
   dá algo entre 150 e 200 mil posts com atividade normal. Quando chegar perto, rode
   `setup()` num projeto novo (ou apague a propriedade `SPREADSHEET_ID` e rode de novo):
   uma planilha nova começa vazia, e a antiga fica guardada como arquivo.
2. **Execuções simultâneas.** Uma conta pessoal roda cerca de 30 execuções do Apps Script
   ao mesmo tempo, e as escritas entram em fila pela trava (cada uma leva perto de meio
   segundo). Isso aguenta bem algumas centenas de pessoas ativas. Com milhares publicando
   no mesmo minuto, as pessoas começam a ver "Algo deu errado, tente de novo".

Se o fórum crescer além disso, o app já está pronto para trocar de backend: a tela fala
com a interface `ForumRepository`, e só o adaptador (`RemoteForumRepository`) muda.

## Instalação passo a passo

### 1. Crie o projeto do Apps Script

1. Entre em <https://script.google.com> com a conta que vai guardar o fórum e clique em
   **Novo projeto**.
2. Apague o conteúdo de `Código.gs` e cole o conteúdo de [`Code.gs`](Code.gs).
3. Em **Configurações do projeto** (engrenagem), marque **Mostrar o arquivo de manifesto
   "appsscript.json" no editor**. Volte ao editor e troque o conteúdo de `appsscript.json`
   pelo de [`appsscript.json`](appsscript.json).

### 2. Crie os IDs de cliente do login com Google

No [Google Cloud Console](https://console.cloud.google.com), com um projeto seu
selecionado:

1. **APIs e serviços → Tela de consentimento OAuth:** tipo **Externo**, nome do app,
   e-mail de suporte. Só os escopos básicos são usados (e-mail e perfil), que não exigem
   verificação. Depois clique em **Publicar app**: em modo "Teste", só os usuários de teste
   conseguem entrar.
2. **Credenciais → Criar credenciais → ID do cliente OAuth → Aplicativo da Web.** Copie o
   **ID do cliente** (termina em `.apps.googleusercontent.com`). É esse valor que vai no
   script e no app.
3. **Credenciais → ID do cliente OAuth → Android**, uma vez para cada assinatura:
   - pacote `com.focusguard.v2` com o SHA-1 da chave de release;
   - se usar a Assinatura de apps do Google Play, também `com.focusguard.v2` com o SHA-1 da
     chave de assinatura que aparece no Play Console;
   - pacote `com.focusguard.v2.debug` com o SHA-1 da chave de debug
     (`keytool -list -v -keystore ~/.android/debug.keystore -storepass android`).

   O código não usa os IDs Android. Eles só autorizam o Google a entregar o login para um
   APK com aquele pacote e aquela assinatura.

### 3. Configure e prepare a planilha

1. No topo do `Code.gs`, preencha `GOOGLE_WEB_CLIENT_ID` com o ID do cliente da Web. Outra
   opção: em **Configurações do projeto → Propriedades do script**, crie a propriedade
   `GOOGLE_WEB_CLIENT_ID`.
2. No editor, escolha a função `setup` e clique em **Executar**. Autorize o acesso pedido:
   planilhas e chamadas externas, que servem para conferir o login no Google.
3. O registro de execução mostra o endereço da planilha criada, **HardBlock Fórum — dados**.

### 4. Publique o App da Web

1. **Implantar → Nova implantação → Tipo: App da Web.**
2. **Executar como:** Eu. **Quem pode acessar:** Qualquer pessoa.
3. Copie a URL que termina em `/exec`.
4. Abra essa URL no navegador. A resposta deve ser
   `{"ok":true,"data":{"service":"hardblock-forum","version":1,"configured":true}}`.
   Se aparecer `"configured":false`, falta o `GOOGLE_WEB_CLIENT_ID` ou o `setup()`.

Para atualizar o script depois, use **Implantar → Gerenciar implantações → editar
(lápis) → Versão: Nova versão**. Assim a URL continua a mesma. Uma "Nova implantação"
gera outra URL, e os apps já instalados continuariam apontando para a antiga.

### 5. Ligue o app

No `gradle.properties` da raiz do projeto:

```properties
focusguard.forumBackendUrl=https://script.google.com/macros/s/SEU_ID/exec
focusguard.forumGoogleWebClientId=123456-abc.apps.googleusercontent.com
```

Os dois valores não são segredos: os dois acabam dentro do APK. Também podem vir das
variáveis de ambiente `FORUM_BACKEND_URL` e `FORUM_GOOGLE_WEB_CLIENT_ID`. Com qualquer um
dos dois vazio, o fórum continua funcionando só no aparelho, como antes.

## Moderação

Tudo é feito direto na planilha:

| O que fazer | Onde |
| --- | --- |
| Esconder um post | aba `posts`, coluna `status` = `hidden` |
| Esconder um comentário | aba `comments`, coluna `status` = `hidden` |
| Impedir uma conta de escrever | aba `users`, coluna `status` = `banned` (vale em até 2 minutos) |
| Ver denúncias | aba `reports` (`targetId` é o `id` do post ou comentário) |
| Desconectar todo mundo | rode `rotateSessionSecret()` no editor |

Três denúncias de pessoas diferentes escondem o post ou comentário sozinhas. Para
reverter, apague o `hidden` da coluna `status`.

**Não apague nem insira linhas.** A paginação e as buscas dependem da ordem das linhas.
Para tirar algo do ar, use a coluna `status`. Também não troque a propriedade
`USER_ID_SALT`: todos os usuários virariam pessoas novas e perderiam os próprios posts.

## Privacidade

Ficam guardados: o código da conta (não o e-mail), o nome e o avatar do perfil, posts,
comentários, curtidas, notificações e denúncias. Quando alguém apaga um post ou
comentário, o texto é limpo da planilha. Se uma pessoa pedir a exclusão dos dados, procure
o `userId` dela na aba `users` e limpe as linhas com esse código nas demais abas.

## Testes

O `Code.gs` roda no Node com uma imitação do Apps Script e do Sheets
([`test/apps-script-fake.js`](test/apps-script-fake.js)). A imitação reproduz o que o
Sheets faz com valores, como transformar texto em número ou fórmula:

```sh
node --test forum-backend/test/forum.test.js
```

## O que ainda não existe

- Notificação no sistema (push). As notificações aparecem ao abrir ou atualizar o fórum.
- Editar um post já publicado.
- Silenciar ou bloquear outra pessoa só para si.
