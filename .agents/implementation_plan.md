# Plano de implementação — auditoria crítico/executor

## Snapshot e regras

- Base: `main` em `cc1031c`.
- Branch de trabalho: `audit/critic-executor-20260821`.
- O agente crítico apenas inspeciona; o agente principal é o único executor.
- Cada área precisa ser aprovada antes da seguinte.
- Não executar Gradle nem compilar localmente. Lint, testes e APK são validados
  exclusivamente pelo GitHub Actions.
- A Área Dev deve ser removida integralmente, inclusive ações, rotas, textos e
  caminhos internos de desativação.
- A remoção normal do app deve continuar obedecendo a todas as proteções ativas.

## Parte 1 — arquitetura e estrutura

### 1. Remoção integral da Área Dev

- Remover a ação de relinquish do AccessibilityService, receiver, intent factory,
  constantes e métodos de limpeza de desenvolvimento.
- Remover strings e textos que ainda descrevem a Área Dev como saída técnica.
- Preservar exclusivamente o fluxo legítimo e autenticado de desinstalação.

### 2. Persistência e estado da apresentação

- Remover acesso direto ao Room de Composables.
- Centralizar limites e detalhes de sessão em repositories/casos de uso.
- Expor `UiState` por ViewModels e coletar com lifecycle.
- Tornar navegação e rascunhos restauráveis por `SavedStateHandle` ou estado
  saveable apropriado.

### 3. Composição de dependências

- Usar Hilt como única composição do fluxo normal.
- Remover construções manuais de managers e fallbacks que criam grafos paralelos.
- Injetar componentes Android; Direct Boot terá uma porta explícita e restrita.

### 4. Fronteiras e decomposição

- Remover dependências manager -> UI/service por portas de plataforma.
- Dividir `BlockingAccessibilityService`, `BlockingSessionManager` e
  `DeviceOwnerManager` em colaboradores com responsabilidade única.
- Eliminar rotas/telas concorrentes sem entrada ou integrar a implementação
  canônica.
- Fechar tipos persistidos e adicionar integridade às referências Room.
- Criar módulos que imponham domínio, dados, plataforma e apresentação sem ciclo.
- Remover dependências Gradle sem consumidor.

## Critérios para devolver ao crítico

- Buscas por símbolos da Área Dev retornam zero.
- UI não importa `AppDatabase` nem DAOs.
- Managers stateful têm uma identidade por processo e não localizam dependências.
- Camada de aplicação não importa UI nem services concretos.
- As três antigas classes monolíticas ficam abaixo dos limites estruturais do
  projeto ou são substituídas por shells finos e colaboradores testáveis.
- Rotas e rascunhos sobrevivem à recriação.
- Não existem rotas públicas sem caller nem implementações concorrentes.
- Modelo Room usa tipos fechados, FKs e índices.
- Dependências entre módulos são acíclicas e verificadas automaticamente.
- Branch publicada e todos os jobs do GitHub Actions verdes.

## Partes seguintes

Somente após aprovação integral desta parte:

1. bloqueios, desinstalação, segurança e persistência;
2. UI, acessibilidade, desempenho e UX;
3. testes, CI, documentação e entrega;
4. revisão final integral do crítico.
