# Arquitetura do bloqueio de sites

O bloqueio web do FocusGuard é uma pipeline. Cada etapa tem uma responsabilidade única e não deve assumir o trabalho da seguinte.

## 1. Reconhecimento do navegador

**Objetivo:** provar que o pacote/janela atual é um navegador que pode expor uma URL.

Código principal:

- `accessibility/website/compatibility/BrowserDetector.kt`
- `accessibility/website/compatibility/BrowserRecognitionPolicy.kt`
- `accessibility/website/compatibility/BrowserCompatibilityStore.kt`

Esta camada não decide se uma URL deve ser bloqueada.

## 2. Identificação da URL/site atual

**Objetivo:** extrair uma URL candidata usando somente evidência certificável da interface do navegador.

Código principal:

- `accessibility/website/identification/WebsiteIdentificationEngine.kt`
- `accessibility/website/identification/WebsiteIdentificationRecovery.kt`
- `service/BrowserInspectionCoordinator.kt`
- `service/WebsiteTreeWorker.kt`

Esta camada não abre tela de bloqueio e não navega.

## 3. Decisão de bloqueio

**Objetivo:** comparar a URL identificada com regras ativas e decidir qual proteção possui o alvo.

Código principal:

- `utils/WebsiteBlocker.kt` para normalização/matching de regras;
- `accessibility/website/blocking/WebsiteBlockDecisionPolicy.kt` para propriedade HARD/PASSWORD/NONE;
- `service/WebsiteProtectionHierarchyPolicy.kt` somente como facade de compatibilidade do orquestrador atual;
- regras e limites fornecidos por `BlockingSessionManager`.

Esta camada resolve domínio/subdomínio, aliases, categoria Pornografia, concessões PASSWORD e prioridade de proteção. Ela não manipula a barra do navegador.

## 4. Apresentação normal do site bloqueado

**Objetivo:** impedir imediatamente a visualização e interação com a página bloqueada sem retirar o navegador do foreground.

No caminho HARD normal, a tela de bloqueio é a **cortina opaca de Accessibility** (`TYPE_ACCESSIBILITY_OVERLAY`). Ela é mostrada antes de qualquer ação na barra de endereço e permanece visível durante toda a transação.

Essa escolha é intencional: abrir uma `Activity` nesse ponto faria o navegador deixar de ser a janela ativa e quebraria o redirecionamento na mesma aba.

A cortina exibe o alvo bloqueado quando ele é conhecido e continua consumindo interação até que o destino seguro seja confirmado ou o fluxo seja entregue a uma superfície fail-closed.

## 5. Destino de redirecionamento

**Objetivo:** ser a única fonte de verdade do endereço seguro e da regra que certifica sua chegada.

Código principal:

- `accessibility/website/redirection/WebsiteRedirectDestination.kt`

Destino inicial: `https://www.google.com`.

O `BlockingAccessibilityService` não possui a URL escolhida. Uma futura seleção de outro site deve alterar essa camada/configuração, não o mecanismo de identificação nem a lógica de manipulação da barra.

## 6. Coordenação e execução do redirecionamento

**Objetivo:** substituir o endereço bloqueado pelo destino configurado na mesma aba, mantendo estado e ordem de fases fora da regra de negócio de identificação.

Código principal:

- `accessibility/website/redirection/WebsiteRedirectionCoordinator.kt`
- `accessibility/website/redirection/WebsiteRedirectionPlan.kt`
- `accessibility/website/redirection/AddressBarRedirectionActions.kt`
- `accessibility/website/redirection/ClipboardPasteFallback.kt`

`WebsiteRedirectionCoordinator` é dono da transação após a apresentação: controla tentativas na mesma aba, retry limitado, confirmação do destino, terminal estrito e fail-closed. `WebsiteTabNeutralizationPolicy` também vive nessa camada.

O `BlockingAccessibilityService` funciona como adaptador Android: entrega ao coordinator operações concretas para obter janelas/raízes frescas, executar `AccessibilityAction`s, aguardar eventos/confirmar a navegação, abrir o terminal estrito e liberar/manter a cortina. Ele não decide a sequência da transação.

Ordem conceitual:

1. mostrar a apresentação opaca de site bloqueado;
2. ativar a barra de endereço certificada;
3. reacquirir a árvore;
4. selecionar/substituir o conteúdo;
5. escrever `WebsiteRedirectDestination.current.url`;
6. reacquirir a árvore;
7. enviar com uma ação certificável;
8. repetir uma vez na mesma aba somente se a superfície original continuar certificada;
9. confirmar `WebsiteRedirectDestination.current.matchesSurface(...)`;
10. liberar a apresentação somente após confirmação.

O fluxo não abre outra aba como substituto do redirecionamento na aba bloqueada. Não existe fallback externo por `ACTION_VIEW`: ao esgotar as tentativas certificáveis na mesma aba, o fluxo permanece fail-closed.

## 7. Confirmação e fail-closed

**Objetivo:** só liberar a proteção depois de provar que o destino seguro realmente assumiu a superfície.

Se identificação, escrita, envio ou confirmação falhar, a página bloqueada não volta a ser revelada. A cortina permanece até a entrega para uma superfície FocusGuard segura.

Quando o domínio bloqueado é conhecido, `ui/WebsiteBlockNoticeActivity.kt` é a superfície terminal fail-closed. Ela não identifica URLs, não manipula a barra e não tenta iniciar uma segunda navegação. Para um navegador opaco cujo domínio não pode ser provado, o fallback genérico continua sendo usado.

## 8. PASSWORD e outros terminais

`PasswordUnlockActivity` continua sendo a única proprietária de credenciais e da concessão de uma visita PASSWORD. O PASSWORD não usa o redirecionamento HARD enquanto a autenticação estiver pendente.

`GenericBlockNoticeActivity` permanece restrita a apps e estados genéricos sem alvo web conhecido.

## Fluxo resumido

```text
Evento do navegador
        │
        ▼
Reconhecer navegador
        │
        ▼
Identificar URL
        │
        ▼
Comparar regras / hierarquia
        │
        ├── permitido ──► continuar observação
        │
        └── bloqueado HARD
               │
               ▼
   Cortina "site bloqueado"
   (navegador continua ativo)
               │
               ▼
 WebsiteRedirectDestination
               │
               ▼
 WebsiteRedirectionCoordinator
               │
               ├─ tentativa mesma aba
               ├─ retry limitado
               └─ confirmação/fail-closed
               │
               ▼
 confirmar destino configurado
               │
        ┌──────┴─────────┐
        │                │
     sucesso           falha
        │                │
        ▼                ▼
 liberar cortina   manter cortina
                         │
                         ▼
          WebsiteBlockNoticeActivity
          (fail-closed, se alvo conhecido)
```

## Regra de manutenção

Novas estratégias de leitura entram em `identification`. Novas decisões de propriedade entram em `blocking`. O endereço seguro e sua certificação entram em `WebsiteRedirectDestination`. Ordem, retry e estado de redirecionamento entram em `WebsiteRedirectionCoordinator`. Ações de barra entram em `AddressBarRedirectionActions`. A apresentação normal permanece no overlay de acessibilidade; `WebsiteBlockNoticeActivity` é somente terminal/fail-closed. O `BlockingAccessibilityService` é o adaptador Android que conecta essas etapas e não deve voltar a ser a fonte de verdade do destino ou da máquina de estados.


### Hardening de confirmação e aprendizagem

- Ação de submit aceita não é navegação confirmada: a preferência de submit só é persistida depois da confirmação positiva do destino.
- Cada tentativa tem exatamente um timeout de confirmação, controlado pelo `WebsiteRedirectionCoordinator`; dentro dele, eventos e uma releitura estável bounded compartilham o mesmo orçamento. Timeout pode restaurar a superfície e consumir o retry same-tab.
- `Outcome` diferencia sucesso, fail-closed e abort por perda de ownership para evitar penalizar o navegador por supersessão.
- Se a navegação same-tab recriar a janela de Accessibility, o guard aceita no máximo um rebind e apenas depois de duas leituras estáveis da superfície exata de `WebsiteRedirectDestination`, no mesmo pacote e após o submit.
- Antes da escrita, o editor precisa continuar pertencendo à regra bloqueada original ou já conter o próprio destino seguro.
- Runtime exception enquanto a cortina ainda pertence à transação termina em fail-closed; cancelamento estruturado não é convertido em falha funcional.
