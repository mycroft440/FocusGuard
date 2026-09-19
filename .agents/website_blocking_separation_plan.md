# Separação do fluxo de bloqueio de sites

## Objetivo

Deixar o fluxo web legível como uma pipeline com responsabilidades independentes, sem misturar identificação, apresentação e navegação segura e sem retirar o navegador do foreground antes da reescrita da aba.

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
   - Código principal: `WebsiteBlocker` + `accessibility/website/blocking/WebsiteBlockDecisionPolicy`.

4. **Apresentação HARD normal**
   - A cortina opaca de Accessibility é a superfície visual durante o bloqueio normal.
   - Ela impede que a página fique utilizável e mantém o navegador como janela ativa.
   - Pode exibir o domínio bloqueado sem abrir uma Activity antes do redirecionamento.

5. **Destino seguro**
   - `WebsiteRedirectDestination` é a única fonte de verdade do endereço e da certificação do destino.
   - Destino inicial: `https://www.google.com`.
   - O Service não possui a URL segura.

6. **Coordenação e redirecionamento**
   - `WebsiteRedirectionCoordinator` é dono da transação após a apresentação: tentativas na mesma aba, retry limitado, confirmação, terminal estrito e fail-closed.
   - `WebsiteTabNeutralizationPolicy` pertence à mesma camada.
   - `AddressBarRedirectionActions`, `ClipboardPasteFallback` e `WebsiteRedirectionPlan` executam/definem as primitivas e os limites certificáveis.
   - O `BlockingAccessibilityService` fornece somente o adaptador Android: roots/janelas, ações, eventos, terminal estrito e handoff da cortina.
   - O fluxo prioriza substituição na mesma aba e não usa `ACTION_VIEW` quando não pode provar a neutralização da aba original.

7. **Confirmação e fail-closed**
   - A cortina só é liberada depois de confirmar a superfície definida por `WebsiteRedirectDestination`.
   - Falhas de identificação, escrita, envio ou confirmação nunca reexibem a página bloqueada.

8. **Terminal web**
   - `WebsiteBlockNoticeActivity` é somente a superfície terminal/fail-closed quando o alvo web é conhecido.
   - Ela não identifica URL e não tenta uma segunda navegação.
   - `GenericBlockNoticeActivity` permanece para apps e estados genéricos sem alvo web conhecido.
   - `PasswordUnlockActivity` permanece exclusiva para PASSWORD.

## Invariantes

- Identificação nunca abre tela e nunca redireciona.
- A apresentação HARD normal não rouba o foreground do navegador.
- A Activity terminal de website nunca tenta descobrir URL nem manipular a barra.
- Redirecionamento nunca decide se uma regra está bloqueada.
- O destino seguro não pertence ao `BlockingAccessibilityService`.
- A sequência/retry/fail-closed do redirecionamento não pertence ao `BlockingAccessibilityService`.
- Apps e sites não compartilham a mesma Activity visual quando o alvo web é conhecido.
- `AccessibilityNodeInfo` não atravessa fases assíncronas; cada fase reacquire a raiz.
- O redirecionamento continua sendo na mesma aba sempre que certificável.
- Google é apenas o destino seguro inicial; a escolha do destino permanece isolada da identificação.

## Mudanças desta revisão

- Criar `WebsiteBlockDecisionPolicy` dedicada à decisão HARD/PASSWORD/NONE.
- Usar a cortina opaca como apresentação HARD normal do website, preservando a janela ativa do navegador.
- Manter `WebsiteBlockNoticeActivity` como terminal/fail-closed conhecido, sem navegação própria.
- Criar `WebsiteRedirectDestination` como fonte de verdade do destino seguro.
- Criar `WebsiteRedirectionCoordinator`, extrair `WebsiteTabNeutralizationPolicy` e mover para o coordinator a sequência de tentativas/confirmação/fail-closed.
- Simplificar `GenericBlockNoticeActivity` para apps/estado genérico.
- Adicionar testes de roteamento, destino e execução do coordinator.
- Atualizar o mapa e `docs/WEBSITE_BLOCKING_ARCHITECTURE.md`.


## Hardening pós-revisão

- O coordinator executa explicitamente `prepare -> submit -> confirmação -> restore/retry`; não existe mais uma lista de fases apenas documental.
- A confirmação tem um único timeout por tentativa e uma falha de confirmação pode consumir o único retry same-tab.
- `Outcome` é a fonte explícita da telemetria de sucesso/falha/abort; supersessão não penaliza compatibilidade.
- Métodos de submit são candidatos transitórios e só viram preferência após navegação confirmada.
- Navegadores que recriam a janela de Accessibility podem fazer um único rebind, exclusivamente após dupla prova da superfície segura configurada.
- O editor é revalidado imediatamente antes da escrita e a política rejeita transições de janela posteriores ao início da fase.
- Exceções de runtime sob cortina viram fail-closed; cancelamento de coroutine continua sendo propagado.
- Aliases `www` de destinos customizados são explícitos; não existe colapso global de host.
- `UsageAccessStateMonitor` fecha a corrida pré-dispatch e o `Application` encerra monitores no teardown Robolectric.
