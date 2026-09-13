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
- [ ] Adicionar uma regra explícita em `MasterCredentialPolicy` para autorizar configuração da senha mestre somente sem sessão `TIME` e sem limite de uso habilitado; `PASSWORD` e `POMODORO` ficam fora desta nova restrição.
- [ ] Fazer `BlockingSessionManager` calcular o gate com o estado persistido e fornecer um caminho de configuração que revalide imediatamente antes de gravar a credencial.
- [ ] Alterar `MasterPasswordActivity`/`DeactivationCredentialDialog` para consumir o gate e salvar somente através do manager, removendo o bypass atual de UI.
- [ ] Cobrir a política por testes para estado vazio, apenas `PASSWORD`, `TIME`, limite de uso e combinação de proteções.

## Riscos e critérios de segurança
- Limites desabilitados não podem bloquear a configuração.
- Sessões `PASSWORD` não podem bloquear a configuração.
- Uma sessão `TIME` ativa deve bloquear criação e troca da senha mestre.
- A validação precisa acontecer também no clique de salvar para evitar a corrida de abrir a tela antes de criar um bloqueio.
- Nenhuma regra de criação/remoção de bloqueios existentes deve ser alterada.

## Validação
- [ ] Executar os testes unitários relacionados à política da senha mestre.
- [ ] Executar a suíte unitária disponível e Android Lint, se o ambiente permitir.
- [ ] Revisar o diff para confirmar que não houve mudanças fora do escopo.
- [ ] Verificar o status de CI/build da branch/PR quando disponível.

## Critério de conclusão
A senha mestre pode ser criada ou trocada quando não há bloqueio `TIME` nem limite de uso habilitado, inclusive se houver bloqueios `PASSWORD`; depois que um `TIME` ativo ou um limite habilitado existir, a tela não permite a alteração e a camada de negócio recusa a gravação mesmo em caso de estado concorrente.
