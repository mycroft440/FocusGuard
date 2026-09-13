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
