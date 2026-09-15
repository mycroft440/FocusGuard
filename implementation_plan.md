# Plano de implementação — gerenciamento de sites protegidos por senha

## Objetivo
Corrigir a tela **Proteja apps com senha** para que sites protegidos possam ser removidos com a própria credencial do alvo e para que a lista use ícones de site equivalentes aos exibidos no seletor.

## Diagnóstico
- [x] Confirmar que as linhas de aplicativos PASSWORD recebem a ação de remoção, enquanto as linhas de sites são renderizadas sem `onRemove`.
- [x] Confirmar que o diálogo de remoção atual trata qualquer entrada como pacote de aplicativo (`blockedPackage`) e consulta a credencial usando a chave de app, portanto apenas exibir a lixeira no site não seria suficiente.
- [x] Confirmar que `BlockingSessionManager.unlockPasswordSessionTarget` já aceita `blockedDomain` e remove somente o alvo responsável, preservando outras camadas e outros alvos da sessão.
- [x] Confirmar que o seletor de sites usa o catálogo `PredefinedWebsites` e favicons, enquanto a lista de bloqueados reduz sites à primeira letra do rótulo.

## Implementação
- [ ] Expor a ação de remoção também nas linhas de sites quando o tipo da tela for PASSWORD.
- [ ] Tornar o fluxo de autenticação/remoção consciente do tipo de alvo, usando a chave de credencial de website e `blockedDomain` para sites, sem alterar o caminho existente de apps.
- [ ] Reutilizar o domínio de ícone dos presets e o mesmo serviço de favicon do seletor; manter fallback local para categorias, palavras-chave e falha de rede.
- [ ] Cobrir por testes a resolução do ícone e o roteamento app/site da remoção.

## Validação
- [ ] Executar testes unitários.
- [ ] Executar Android Lint.
- [ ] Revisar o diff para garantir que a precedência TIME > limite esgotado > PASSWORD e a remoção isolada de alvos não foram alteradas.
- [ ] Confirmar por CI/build que os novos composables e imports compilam.

## Critério de conclusão
Na tela de bloqueio por senha, um site deve mostrar a ação de remoção, exigir a credencial configurada para aquele site e ser removido sem afetar proteções independentes. Sites predefinidos devem exibir seu favicon/ícone de marca; regras sem favicon significativo devem continuar com fallback seguro e legível.

---

# Plano de implementação — senha mestre antes de bloqueios irreversíveis

## Objetivo
Impedir a criação ou troca da senha mestre depois que já existir um bloqueio `TIME` ativo ou um limite de uso habilitado, sem impedir a configuração quando existirem apenas bloqueios `PASSWORD`.

## Diagnóstico
- [x] Confirmar que `MasterPasswordActivity` atualmente força `managementLocked = false`, permitindo configurar a senha mestre mesmo com bloqueios existentes.
- [x] Confirmar que `DeactivationCredentialDialog` grava diretamente em `DeactivationCredentialManager.configure`.
- [x] Confirmar que `BlockSession.sessionType` separa `PASSWORD` de `TIME` e que limites de app/site têm `isEnabled` próprio.
- [x] Confirmar que `BlockingSessionManager` já centraliza acesso às sessões e aos limites de uso.
- [x] Confirmar que `MasterCredentialPolicyTest` é o ponto unitário existente para regras da senha mestre.

## Implementação
- [x] Adicionar uma regra explícita em `MasterCredentialPolicy` para autorizar configuração da senha mestre somente sem sessão `TIME` e sem limite de uso habilitado; `PASSWORD` e `POMODORO` ficam fora desta nova restrição.
- [x] Adicionar `MasterCredentialConfigurationManager` para calcular o gate com o estado persistido e revalidar imediatamente antes de gravar a credencial, sem ampliar o `BlockingSessionManager`.
- [x] Alterar `MasterPasswordActivity`/`DeactivationCredentialDialog` para consumir o gate e salvar somente através do coordenador, removendo o bypass atual de UI.
- [x] Cobrir a política por testes para estado vazio, apenas `PASSWORD`, `TIME`, limite de uso e combinação de proteções.

## Riscos e critérios de segurança
- Limites desabilitados não podem bloquear a configuração.
- Sessões `PASSWORD` não podem bloquear a configuração.
- Uma sessão `TIME` ativa deve bloquear criação e troca da senha mestre.
- A validação precisa acontecer também no clique de salvar para evitar a corrida de abrir a tela antes de criar um bloqueio.
- Nenhuma regra de criação/remoção de bloqueios existentes deve ser alterada.

## Validação
- [ ] Executar os testes unitários relacionados à política da senha mestre.
- [ ] Executar a suíte unitária disponível e Android Lint, se o ambiente permitir.
- [x] Revisar o diff para confirmar que não houve mudanças fora do escopo.
- [ ] Verificar o status de CI/build da branch/PR quando disponível.

## Critério de conclusão
A senha mestre pode ser criada ou trocada quando não há bloqueio `TIME` nem limite de uso habilitado, inclusive se houver bloqueios `PASSWORD`; depois que um `TIME` ativo ou um limite habilitado existir, a tela não permite a alteração e a camada de negócio recusa a gravação mesmo em caso de estado concorrente.

---

# Plano de implementação — detecção mais rápida de sites bloqueados

## Objetivo
Reduzir a latência entre a navegação para um site configurado (especialmente YouTube) e a exibição da proteção, preservando a identificação fail-closed da barra de endereço e o redirecionamento seguro existente.

## Diagnóstico
- [x] Confirmar que `youtube.com` já cobre subdomínios e aliases como `youtu.be` e `youtube-nocookie.com`; o matcher de domínio não é a origem do atraso.
- [x] Confirmar que o caminho imediato só decide sem árvore quando `event.source` já é reconhecido como barra de endereço.
- [x] Confirmar que o fallback de `handleBrowserEvent` pode percorrer a árvore para obter a URL e depois percorrê-la novamente para obter o texto cru antes de decidir um bloqueio de domínio.
- [x] Confirmar que `startWebsiteBlockTransition` revalida `windows/window.root` antes de mostrar a cortina mesmo quando a janela já foi validada pela fonte/root usada na detecção.
- [x] Confirmar que eventos relevantes já têm `notificationTimeout = 0` e `browserDebounceMillis = 0`, portanto não há debounce configurado a remover.

## Implementação
- [ ] Ampliar somente a classificação read-only de barras de endereço para nós visíveis, pertencentes a navegadores HTTPS verificados, com IDs browser-owned de semântica URL/URI/omnibox/address, sem ampliar capacidades de automação.
- [ ] Em `handleBrowserEvent`, decidir e bloquear imediatamente quando a URL já encontrada casar uma regra, antes de uma segunda busca de texto na árvore.
- [ ] Propagar explicitamente quando o `windowId` foi validado pela barra/root e, nesses casos, evitar a leitura síncrona redundante de `windows/window.root` antes de exibir a cortina.
- [ ] Manter a resolução atual de janela como fallback para bloqueios disparados sem janela validada, como limite de uso.
- [ ] Cobrir a identificação read-only e o caminho de YouTube por testes de regressão.

## Riscos e critérios de segurança
- Nós de conteúdo comuns nunca podem virar barras acionáveis.
- Uma identificação read-only expandida exige pacote/janela corretos, navegador HTTPS reconhecido, nó visível e recurso pertencente ao próprio browser.
- O texto identificado ainda precisa ser uma URL/domínio válido e casar uma regra configurada antes de bloquear.
- O redirecionamento seguro, a confirmação de Google e a hierarquia PASSWORD/HARD/POMODORO permanecem inalterados.

## Validação
- [ ] Executar testes unitários de `BrowserUiCapabilityPolicy`, `WebsiteBlocker` e `WebsiteBlockNavigation`.
- [ ] Executar a suíte unitária, Android Lint e compilação do harness pelo CI.
- [ ] Revisar o diff agregado para confirmar que não houve alteração no matcher de aliases, duração da cortina ou configuração global de eventos.

## Critério de conclusão
Um domínio bloqueado já visível na barra de endereço deve acionar a cortina no primeiro evento/árvore em que a URL puder ser identificada, sem uma segunda varredura ou revalidação de janela redundante; YouTube e seus aliases continuam sendo reconhecidos pela mesma regra e navegadores sem evidência suficiente continuam falhando de forma segura.

---

# Correção — interface nativa versus página web dos navegadores

- Classificar a janela atual como interface nativa, conteúdo web ou desconhecida, sem reutilizar evidência global de menus.
- Excluir WebViews e seus descendentes da leitura e automação da barra de endereço.
- Reavaliar a janela antes do bloqueio por URL não observável; interfaces nativas e árvores indisponíveis não são prova de site bloqueado.
- Aprimorar ativação da barra em Chrome, Samsung Internet, Via e Yandex; manter confirmação real de navegação.
- Revisar o código e enviar diretamente para main. Não executar testes nem builds, conforme solicitado.
