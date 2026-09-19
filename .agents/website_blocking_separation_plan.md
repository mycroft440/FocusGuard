# Separação do fluxo de bloqueio de sites

## Objetivo

Deixar o fluxo web legível como uma pipeline com responsabilidades independentes, sem misturar identificação, apresentação e navegação segura.

## Pipeline

1. **Reconhecimento do navegador**
   - Confirma pacote/janela e capacidade HTTPS.
   - Código principal: `accessibility/website/compatibility`.

2. **Identificação do site atual**
   - Lê somente evidência certificável da barra de endereço.
   - Não decide UI e não navega.
   - Código principal: `accessibility/website/identification`.

3. **Decisão de bloqueio**
   - Normaliza e compara regras, resolve PASSWORD vs proteção forte e limites.
   - Não manipula árvore de acessibilidade para redirecionar.
   - Código principal: `WebsiteBlocker` + `WebsiteProtectionHierarchyPolicy`.

4. **Proteção visual imediata**
   - Cortina opaca impede que a página bloqueada volte a ficar utilizável durante a transição.
   - É uma proteção transitória, não a tela final de bloqueio.

5. **Tela de site bloqueado**
   - `WebsiteBlockNoticeActivity` é dona exclusivamente da apresentação de bloqueio web.
   - `GenericBlockNoticeActivity` permanece dona da apresentação de bloqueio de apps/estado genérico.
   - O router `BlockNoticeActivity` escolhe a Activity correta sem renderizar UI.

6. **Redirecionamento**
   - Somente o pacote `accessibility/website/redirection` manipula a barra de endereço.
   - Prioriza substituição na mesma aba.
   - Não usa `ACTION_VIEW` como fallback quando isso não pode provar a neutralização da aba original.
   - Destino inicial: `https://www.google.com`.

7. **Confirmação e fail-closed**
   - A cortina só é liberada depois de confirmar a superfície segura.
   - Falhas de identificação, escrita, envio ou confirmação nunca reexibem a página bloqueada.

## Invariantes

- Identificação nunca abre tela e nunca redireciona.
- A tela de bloqueio nunca tenta descobrir a URL pela árvore de acessibilidade.
- Redirecionamento nunca decide se uma regra está bloqueada.
- Apps e sites não compartilham a mesma Activity visual quando o alvo web é conhecido.
- `AccessibilityNodeInfo` não atravessa fases assíncronas; cada fase reacquire a raiz.
- O redirecionamento continua sendo na mesma aba sempre que certificável.
- Google é apenas o destino seguro inicial; a escolha do destino deve permanecer isolada da identificação.

## Mudanças desta revisão

- Criar `WebsiteBlockNoticeActivity` dedicada a sites.
- Fazer `BlockNoticeActivity` rotear `EXTRA_BLOCKED_DOMAIN` para a Activity web quando a proteção não for PASSWORD.
- Simplificar `GenericBlockNoticeActivity` para apps/estado genérico, removendo responsabilidade visual de site conhecido.
- Registrar a Activity web no Manifest.
- Adicionar testes de roteamento para impedir regressão de mistura entre UI de app e UI web.
- Atualizar `docs/WEBSITE_BLOCKING.md` com o mapa da pipeline.
