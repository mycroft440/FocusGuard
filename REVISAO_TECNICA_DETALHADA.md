# Relatório de Revisão Técnica - FocusGuard

Este relatório detalha os bugs identificados, vulnerabilidades de segurança e pontos de melhoria no código do FocusGuard.

---

## 1. Bugs Críticos (Alta Prioridade)

### 1.1. Falha na Restauração de Bloqueios Pós-Boot
- **Arquivo:** `BootReceiver.kt`
- **Problema:** O `BootReceiver` detecta o reinício do dispositivo, mas apenas loga se a sessão é ativa. Ele **não chama** os métodos de re-enforcement do `DeviceOwnerManager`.
- **Impacto:** O usuário pode burlar todos os bloqueios simplesmente reiniciando o celular.
- **Correção Sugerida:** Chamar `sessionManager.checkAndEnforce()` dentro do escopo da coroutine no `BootReceiver`.

### 1.2. Dessincronização de Senhas (Auth Leak)
- **Arquivo:** `AuthManager.kt`
- **Problema:** O método `removePassword(passwordToRemove)` remove o hash da lista `app_password_hashes`, mas **não remove** a entrada correspondente em `app_password_entries`.
- **Impacto:** A interface continuará exibindo rótulos de senhas que não funcionam mais, causando confusão e inconsistência no banco de dados de preferências.
- **Correção Sugerida:** Unificar a lógica de remoção para sempre atualizar ambas as listas simultaneamente.

---

## 2. Segurança e Robustez (Média Prioridade)

### 2.1. Hashing de Senha Vulnerável
- **Arquivo:** `AuthManager.kt`
- **Problema:** O uso de SHA-256 puro sem *Salt* torna as senhas vulneráveis a ataques de dicionário ou Rainbow Tables. Além disso, não há limite real de tentativas de senha implementado (apenas o contador é incrementado).
- **Correção Sugerida:** Adicionar um *Salt* aleatório por senha e implementar um delay progressivo após falhas consecutivas.

### 2.2. Falta de Validação na Criação de Sessão
- **Arquivo:** `CreateSessionWizard.kt`
- **Problema:** O usuário pode criar uma sessão de tempo com 0 dias e 0 horas. O app permite prosseguir, criando uma sessão que expira instantaneamente ou se comporta de forma imprevisível.
- **Correção Sugerida:** Adicionar uma validação no botão "Confirmar" para garantir que ao menos um app/site esteja selecionado e que o tempo seja maior que zero.

---

## 3. Performance e Melhorias (Baixa Prioridade)

### 3.1. Vazamento de Memória no Accessibility Service
- **Arquivo:** `BlockingAccessibilityService.kt`
- **Problema:** Embora o `source.recycle()` seja chamado, em fluxos de exceção ou retornos antecipados, alguns nós podem não ser reciclados corretamente.
- **Melhoria:** Utilizar blocos `use` ou garantir a reciclagem em um bloco `finally` mais abrangente para todos os `AccessibilityNodeInfo`.

### 3.2. Otimização de Busca de Domínios
- **Arquivo:** `WebsiteBlocker.kt`
- **Melhoria:** A lógica de "Domain Walking" (ex: m.facebook.com -> facebook.com) pode ser otimizada usando uma estrutura de dados de busca em árvore (Trie) para lidar com grandes listas de sites bloqueados sem perda de performance.

---

## Próximos Passos Recomendados
1. Aplicar a correção no `BootReceiver.kt` para garantir a persistência.
2. Refatorar o `AuthManager.kt` para garantir integridade das senhas.
3. Adicionar validações de UI no `CreateSessionWizard.kt`.
