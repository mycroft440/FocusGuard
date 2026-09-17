# PR #170 — inspeção e fechamento

Base inspecionada: `601f93e3` (main). Head inicial: `a855159d`.

## Diagnóstico antes das alterações

- Parte 1: coordenador serial, snapshots, geração, sequence e coalescência existentes.
- Parte 2: contrato imutável completo, mas serviço usa o construtor/propriedades antigos e não compila. Há entradas síncronas legadas; editor faz travessia separada.
- Parte 3: Recovery já verifica validade entre fases, mas deduplicação por pacote perde nova geração. Fallbacks do WebsiteBlocker ainda usam predicados permissivos e podem alterar o cache.
- Parte 4: geração na transição e guardas nas ações principais existentes. Back, Intent, timeout, fail-closed e aprendizado ainda precisam de proteção completa.
- CI inicial: compileDebugKotlin falha; testes, lint e harness não chegam ao gate final.
- `complete-pr170.yml` já removido; `part2-browser-inspection.yml` ainda reescreve o serviço e deve ser removido.

## Checklist de implementação

- [x] Integrar BrowserInspectionOutcome como único contrato de aplicação.
- [x] Usar raízes temporárias e uma sessão/orçamento por inspeção, incluindo editor e aprendizado.
- [x] Remover entradas síncronas de navegador sem uso e encaminhar atualização de regras à fila.
- [x] Serializar Recovery com pending mais recente; invalidar efeitos e cache quando stale.
- [x] Exigir isCurrent em toda a cadeia de ações/fallbacks.
- [x] Proteger transições, Back, Intent, confirmação, falhas e limpeza por identidade.
- [x] Aposentar o caminho de fechamento de abas não utilizado; serviço permanece dono da máquina de estados.
- [x] Adicionar regressões de coalescência, troca de pacote/janela, Recovery e redirecionamento.
- [x] Remover workflow temporário.
- [x] Revisar diff e validar compileDebugKotlin, testDebugUnitTest, lintDebug e performance harness.
- [x] Mesclar #170 na main somente após CI aprovado.

PR #169 fora do escopo: nenhuma melhoria exclusiva dele é necessária para corrigir estes problemas; os budgets já estão na main.

Validação final: CI aprovado em `compileDebugKotlin`, `testDebugUnitTest`, `lintDebug` e performance harness. O PR #170 foi mesclado na `main` como `5c2406f7674a2d65aa0e3ab5a31326d74047da78`.
