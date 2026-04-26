# Simulação de Funcionamento: FocusGuard

Este documento descreve o fluxo passo a passo de como o FocusGuard processa um bloqueio, desde a criação da sessão até a execução do bloqueio no sistema Android.

## Passo 1: Criação da Sessão (Interface do Usuário)
O usuário interage com a `CreateSessionActivity` para definir o que será bloqueado.
- **Entrada**: Seleção de Apps (ex: `com.instagram.android`) e Sites (ex: `facebook.com`).
- **Configuração**: Define se é uma sessão por **Senha** (PASSWORD) ou **Tempo** (TIME), e se é **Fixa 24h** ou **Agendada**.
- **Ação**: O `BlockingSessionManager` é chamado (`startPasswordSession` ou `startTimeSession`).

## Passo 2: Persistência e Ativação
O `BlockingSessionManager` salva os dados no banco de dados SQLite (Room).
- **Banco de Dados**: Cria uma entrada na tabela `block_sessions` e vincula os apps/sites nas tabelas de referência cruzada (`session_app_cross_ref`, `session_website_cross_ref`).
- **Gatilho de Aplicação**: Chama `checkAndEnforce()`.

## Passo 3: Aplicação das Políticas (Device Owner)
Se o modo **Device Owner** (Proteção Nuclear) estiver ativo:
- **Apps**: O sistema chama `dpm.setPackagesSuspended`. O ícone do app fica cinza no launcher e ele não pode ser aberto.
- **Sites (Chrome/Edge)**: O sistema envia uma `Managed Configuration` (`URLBlocklist`) para os navegadores. O bloqueio ocorre nativamente dentro do browser.
- **Restrições**: Desativa Reset de Fábrica, Safe Boot, e alteração de Data/Hora.

## Passo 4: Monitoramento em Tempo Real (Acessibilidade)
O `BlockingAccessibilityService` atua como uma segunda camada ou camada principal (se não houver Device Owner).
- **Evento**: O usuário tenta abrir um app ou digita uma URL.
- **Detecção de App**: O serviço recebe `TYPE_WINDOW_STATE_CHANGED`. Se o `packageName` estiver na lista negra, executa `performGlobalAction(GLOBAL_ACTION_HOME)`.
- **Detecção de Site**: O serviço monitora `TYPE_WINDOW_CONTENT_CHANGED` em navegadores. Ele "escaneia" a árvore de acessibilidade em busca da barra de endereços (IDs conhecidos como `com.android.chrome:id/url_bar`).
- **Extração**: O `WebsiteBlocker` extrai o domínio (ex: `m.facebook.com` -> `facebook.com`).

## Passo 5: Execução do Bloqueio
- **Ação**: Se detectado um item proibido, o serviço envia o comando "HOME" para o sistema, minimizando o app/navegador instantaneamente.
- **Feedback**: Exibe um Toast: *"App/Site bloqueado pelo FocusGuard"*.
- **Anti-Bypass**: O cache de 2 segundos no serviço de acessibilidade garante que a verificação seja rápida sem sobrecarregar o banco de dados.
