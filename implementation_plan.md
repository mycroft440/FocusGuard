# Plano de implementação — integridade permanente dos ZIPs de artefatos GitHub

## Objetivo
Garantir que todo artefato ZIP produzido pelos workflows do repositório seja criado por uma versão atual do uploader, substitua cópias antigas em reruns e seja baixado novamente e validado antes de ser aceito como publicação válida.

## Diagnóstico
- [x] Confirmar que a recompilação do mesmo commit produziu um novo ZIP íntegro e um APK cujo conteúdo pôde ser verificado byte a byte.
- [x] Mapear todos os usos de `actions/upload-artifact` nos workflows `release.yml`, `fresh-apk-aab.yml` e `android-ci.yml`.
- [x] Confirmar que reruns do workflow podem deixar artefatos antigos e novos com o mesmo nome, criando risco de baixar uma cópia anterior.
- [x] Confirmar que o uploader atual expõe ID e SHA-256 do arquivo de artefato, permitindo validar exatamente o ZIP servido pelo GitHub após o upload.

## Implementação
- [x] Atualizar os uploads de artefatos para a versão atual do `actions/upload-artifact`, mantendo criação explícita de ZIP e substituição em reruns.
- [x] Adicionar um verificador local que valide SHA-256 do ZIP, estrutura/CRC, nomes seguros e, para binários de release, igualdade byte a byte com APK/AAB/relatórios de origem.
- [x] Baixar novamente cada ZIP recém-enviado pelo endpoint de artefatos do GitHub e falhar o job se o ZIP baixado não passar na validação.
- [x] Impedir a publicação de uma GitHub Release quando o ZIP do APK/AAB/identidade não tiver sido validado.
- [x] Aplicar a mesma validação aos ZIPs de diagnóstico do CI para que nenhum `upload-artifact` do repositório fique sem teste de integridade.

## Validação
- [x] Executar o self-test do verificador, incluindo ZIP truncado e conteúdo divergente.
- [x] Revisar sintaxe dos workflows e o diff agregado para evitar alterações fora do escopo.
- [ ] Executar os jobs do Android CI na branch/PR.
- [ ] Integrar somente com CI verde e confirmar uma nova execução de Release na `main`, incluindo download e verificação pós-upload dos ZIPs reais.

## Critério de conclusão
Nenhum ZIP de artefato deve ser considerado válido apenas porque o upload terminou. O workflow precisa baixar o arquivo servido pelo GitHub, conferir o digest e a integridade do ZIP e, quando houver pacote de release, provar que o conteúdo arquivado é exatamente o mesmo binário verificado antes do upload. Em reruns, a cópia anterior com o mesmo nome deve ser substituída para evitar ambiguidade.

---

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

## Complemento — recuperação completa e memória do método de URL

- Executar releituras, ativação por clique/foco e revelação da barra recolhida antes de concluir que uma página não tem URL observável.
- Manter cada recuperação vinculada ao pacote/janela, com número finito de tentativas e cancelamento ao mudar de contexto.
- Separar a memória persistida de leitura de URL da memória de edição; aprender apenas após obter uma URL válida e priorizar o método bem-sucedido nas próximas visitas.
- Remover gates globais de API 30 do redirecionamento; tentar envio IME quando disponível, ação anunciada e botão nativo certificado.
- Revalidar a escrita antes do envio e confirmar navegação após envio; tentar a ativação alternativa mesmo quando a primeira ação foi aceita sem produzir editor.
- Publicar na main, sem testes ou build, conforme orientação do usuário.

---

# Plano de implementação — bloqueio recorrente por faixa de horário

## Objetivo
Permitir que o bloqueio por tempo use uma faixa diária configurável, aplicada somente nos dias selecionados, mantendo a duração total de vigência definida pelo usuário.

## Diagnóstico
- [x] `TimeBlockSessionConfigScreen` já seleciona apps/sites, inicia com os sete dias marcados e possui duração total via `BlockDurationPicker`.
- [x] `BlockSession` já persiste horário inicial/final, dias de recorrência e `endTime`.
- [x] `BlockingSessionManager.startTimeSession` já grava esses campos e `BlockingScheduleCalculator`/enforcement já suportam janelas diurnas e noturnas.
- [x] A tela atual ignora essa infraestrutura e grava sempre `00:00–24:00`.

## Implementação
- [ ] Expor horário de início e fim na página de agenda, com seleção explícita e feedback para intervalo inválido.
- [ ] Reutilizar uma validação pura da janela recorrente na UI e na criação da sessão, preservando compatibilidade com sessões legadas `00:00–24:00`.
- [ ] Passar os horários escolhidos a `startTimeSession`, mantendo dias selecionados e duração total existentes.
- [ ] Atualizar strings em inglês/português em paridade.
- [ ] Ajustar testes da agenda para período configurável, incluindo intervalo noturno e compatibilidade de `24:00`.

## Validação
- [ ] Executar testes unitários relacionados ao agendamento.
- [ ] Executar `:app:compileDebugKotlin`, `:app:testDebugUnitTest` e `:app:lintDebug` se disponíveis.
- [ ] Revisar o diff para confirmar ausência de mudanças em PASSWORD, limites de uso e fluxo de seleção.
- [ ] Confirmar que a expiração total continua limitando qualquer próxima janela recorrente.

## Critério de conclusão
Um bloqueio `TIME` deve bloquear somente dentro da faixa diária selecionada, nos dias marcados, até o fim da duração total configurada; fora da faixa ou após a expiração, o alvo não deve ser bloqueado por essa sessão.


---

# Correção de CI — paridade de recursos em inglês

## Diagnóstico
- [x] Confirmar que o `lintRelease` falha por `MissingTranslation` nas cinco novas chaves da faixa de horário.
- [x] Confirmar que `values-en/dopamine_schedule_strings.xml` ainda contém a versão anterior das strings de agendamento.
- [x] Confirmar que as cinco chaves ausentes são `dopamine_time_window_question`, `dopamine_time_window_hint`, `dopamine_start_time`, `dopamine_end_time` e `dopamine_time_window_invalid`.

## Implementação
- [ ] Sincronizar `values-en/dopamine_schedule_strings.xml` com o conteúdo inglês atual de `values/dopamine_schedule_strings.xml`, preservando os mesmos nomes de recurso.
- [ ] Não suprimir `MissingTranslation` e não alterar recursos portugueses ou lógica de bloqueio.

## Validação
- [ ] Revisar o diff para confirmar que a mudança ficou restrita ao plano e ao recurso inglês.
- [ ] Confirmar o resultado de `lintRelease`/CI disparado pelo commit.


---

# Correção de CI — teste legado de redirecionamento externo

## Diagnóstico
- [x] Confirmar que o teste falho cria um `WebsiteBlockTransitionGuard` novo, portanto não depende de estado compartilhado entre testes.
- [x] Confirmar que `confirmGoogle` recusa deliberadamente confirmação após `externalRedirectRequested` enquanto `closeConfirmed` for falso.
- [x] Confirmar no histórico que essa regra foi introduzida por `fix: require blocked-tab neutralization after external redirect` e já possui cobertura dedicada em `ExternalRedirectNeutralizationTest`.
- [x] Identificar `browser intent redirect may confirm on a fresh window from the same browser` como teste legado que contradiz a política atual.

## Implementação
- [ ] Remover somente o caso legado contraditório de `WebsiteBlockNavigationTest`.
- [ ] Preservar `ExternalRedirectNeutralizationTest`, que cobre tanto a recusa com a aba bloqueada aberta quanto a confirmação após fechamento independente.
- [ ] Não alterar o código de produção nem adicionar espera/polling para mascarar a asserção.

## Validação
- [ ] Revisar o diff para confirmar escopo restrito ao plano e ao teste obsoleto.
- [ ] Confirmar `testReleaseUnitTest`, lint e release no CI após o commit.
