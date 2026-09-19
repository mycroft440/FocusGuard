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

- `utils/WebsiteBlocker.kt`
- `service/WebsiteProtectionHierarchyPolicy.kt`
- regras e limites fornecidos por `BlockingSessionManager`

Esta camada resolve domínio/subdomínio, aliases, categoria Pornografia, concessões PASSWORD e prioridade de proteção. Ela não manipula a barra do navegador.

## 4. Proteção visual imediata

**Objetivo:** impedir que a página bloqueada fique visível/utilizável durante a transição.

A cortina de acessibilidade é uma proteção transitória. Ela é mostrada antes de tocar na barra de endereço e permanece até existir uma superfície segura confirmada.

A cortina não é a tela final de bloqueio.

## 5. Tela de site bloqueado

**Objetivo:** apresentar exclusivamente o estado visual de um alvo web conhecido.

Código principal:

- `ui/WebsiteBlockNoticeActivity.kt`
- `ui/BlockNoticeActivity.kt` como router UI-free

Regras:

- `WebsiteBlockNoticeActivity` não identifica URL.
- `WebsiteBlockNoticeActivity` não executa automação de barra de endereço.
- `GenericBlockNoticeActivity` fica restrita a apps e estados fail-closed sem alvo web conhecido.
- `PasswordUnlockActivity` continua sendo a única proprietária das credenciais e da concessão PASSWORD.

## 6. Redirecionamento

**Objetivo:** substituir o endereço bloqueado por um destino seguro na mesma aba.

Código principal:

- `accessibility/website/redirection/AddressBarRedirectionActions.kt`
- `accessibility/website/redirection/ClipboardPasteFallback.kt`
- `accessibility/website/redirection/WebsiteRedirectionPlan.kt`
- orquestração da transição em `BlockingAccessibilityService`

Destino inicial: `https://www.google.com`.

Ordem conceitual:

1. ativar a barra de endereço certificada;
2. reacquirir a árvore;
3. selecionar/substituir o conteúdo;
4. escrever o destino seguro;
5. reacquirir a árvore;
6. enviar com uma ação certificável;
7. aguardar confirmação da superfície segura.

O fluxo não deve abrir outra aba como substituto do redirecionamento na aba bloqueada.

## 7. Confirmação e fail-closed

**Objetivo:** só liberar a proteção depois de provar que o destino seguro realmente assumiu a superfície.

Se identificação, escrita, envio ou confirmação falhar, a página bloqueada não volta a ser revelada. O fluxo deve permanecer fail-closed e cair numa superfície segura do FocusGuard.

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
        └── bloqueado
               │
               ▼
        Mostrar cortina imediata
               │
               ▼
        Redirecionar na mesma aba
               │
               ▼
        Confirmar destino seguro
               │
        ┌───────┴────────┐
        │                │
     sucesso           falha
        │                │
        ▼                ▼
 liberar cortina   manter fail-closed
                         │
                         ▼
              tela segura de bloqueio
```

## Regra de manutenção

Novas estratégias de leitura de URL entram em `identification`. Novos métodos de escrita/envio entram em `redirection`. Mudanças visuais do bloqueio web entram em `WebsiteBlockNoticeActivity`. Regras de domínio entram no matcher/políticas. O `BlockingAccessibilityService` deve apenas coordenar essas etapas, sem criar um segundo mecanismo paralelo para a mesma responsabilidade.
