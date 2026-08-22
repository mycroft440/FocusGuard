# Plano de implementação — auditoria Crítico/Executor

## Snapshot e regras

- Base original: `main` em `cc1031c`.
- Branch de trabalho: `audit/critic-executor-20260821`.
- PR: `#50 refactor: full critic/executor project audit`.
- O Crítico é somente leitura; o agente principal é o Executor.
- Regras completas do Crítico: `.agents/critic.md`.
- Auditoria web inicial: `docs/CRITIC_WEB_AUDIT_2026-08-22.md`.
- Cada rodada deve consultar novamente fontes atuais da web; não reutilizar política ou benchmark antigo sem verificar.
- Não executar Gradle nem compilar localmente neste fluxo. Lint, testes e artefatos Android são validados pelo GitHub Actions.
- A Área Dev deve permanecer removida integralmente.
- A remoção normal do app deve obedecer às proteções legítimas ativas, sem usar Accessibility para contrariar as políticas atuais do Android/Google Play.

## Parte 1 — arquitetura e estrutura — EM EXECUÇÃO

### 1. Remoção integral da Área Dev

- remover ações, rotas, textos e caminhos de limpeza exclusivos da Área Dev;
- preservar exclusivamente o fluxo legítimo e autenticado de desinstalação.

### 2. Persistência e estado da apresentação

- remover acesso direto ao Room de Composables;
- centralizar limites e detalhes de sessão em repositories/casos de uso;
- expor `UiState` por ViewModels e coletar com lifecycle;
- tornar navegação e rascunhos restauráveis por estado saveable/SavedStateHandle apropriado.

### 3. Composição de dependências

- usar Hilt como composição canônica do fluxo normal;
- remover construções manuais/fallbacks que criem grafos paralelos;
- manter Direct Boot por porta explícita e restrita.

### 4. Fronteiras e decomposição

- remover dependências manager -> UI/service por portas de plataforma;
- decompor `BlockingAccessibilityService`, `BlockingSessionManager` e `DeviceOwnerManager` em colaboradores testáveis;
- eliminar rotas/implementações concorrentes sem caller;
- fortalecer tipos persistidos, FKs e índices Room;
- impor fronteiras acíclicas entre domínio, dados, plataforma e apresentação;
- remover dependências Gradle sem consumidor.

### Critérios para devolver a Parte 1 ao Crítico

- buscas por símbolos da Área Dev retornam zero;
- UI não importa `AppDatabase` nem DAOs;
- managers stateful têm identidade controlada e não localizam dependências;
- camada de aplicação não importa UI nem services concretos;
- classes monolíticas viram shells finos/colaboradores testáveis;
- rotas e rascunhos sobrevivem à recriação;
- não existem rotas públicas sem caller nem implementações concorrentes;
- persistência tem integridade explícita;
- dependências entre módulos/camadas são acíclicas;
- todos os jobs atuais do GitHub Actions ficam verdes.

## Parte 2 — compatibilidade e publicação — P0/P1

### FG-001 — API 36 — P0

- migrar `compileSdk`/`targetSdk` de 35 para 36;
- revisar mudanças comportamentais do Android 16 que afetem FocusGuard;
- validar fluxos principais em Android 16;
- concluir antes de um novo envio/atualização ao Google Play após 31/08/2026.

### FG-002 — otimização de bateria — P1

- medir bloqueio com/sem solicitação direta de isenção em aparelhos representativos;
- decidir formalmente entre remover, redirecionar para configuração geral ou manter com justificativa técnica/política;
- alinhar onboarding, manifesto, testes e declaração Play.

### FG-006 — foreground services `specialUse` — P1

- auditar gatilho, duração, notificação, encerramento e necessidade dos três serviços;
- manter `specialUse` apenas onde o recurso principal e a experiência do usuário justificarem.

## Parte 3 — confiabilidade, testes e release — P1

### FG-003 — AccessibilityService

- medir frequência de eventos, CPU e latência de bloqueio;
- remover eventos desnecessários ou aplicar timeout/debounce somente com evidência de que não cria bypass perceptível;
- manter teste de latência/regressão.

### FG-004 — testes de integração

- adicionar smoke tests instrumentados onde APIs do SO forem reproduzíveis;
- manter matriz física versionada para Pixel, Samsung e Xiaomi nos fluxos que exigem OEM/aparelho real.

### FG-005 — Android App Bundle

- gerar `bundleRelease` assinado no CI;
- publicar `.aab` como artefato de release, preservando APK apenas quando útil para teste.

### FG-012 — matriz de confiabilidade

- medir tempo até bloqueio, bypass, reboot/update, multi-window/PiP/recentes e bateria por Android/OEM/navegador suportado.

## Parte 4 — performance, adaptabilidade e UX — P2

### FG-008 — Baseline/Startup Profiles

- depois da estabilização arquitetural, introduzir Macrobenchmark;
- medir startup/navegação crítica e gerar Baseline + Startup Profiles.

### FG-009 — Material 3 Adaptive

- auditar Main, Proteção, Métricas e Configurações em classes de janela;
- aplicar layout/navegação adaptativa onde houver benefício, sem simplesmente esticar conteúdo.

### FG-010 — permissões progressivas

- separar permissões essenciais ao bloqueio das opcionais de recursos específicos;
- impedir que uma permissão opcional vire requisito global sem necessidade técnica/política comprovada.

### FG-011 — evolução competitiva

Avaliar, sem copiar automaticamente:

- cooldown voluntário;
- aprovação por pessoa de confiança;
- grupos/perfis reutilizáveis e limite coletivo;
- limite por número de aberturas;
- filtros/categorias prontas;
- diagnóstico automático de permissões/bateria/navegador;
- bloqueio seletivo de Shorts/Reels quando tecnicamente robusto e compatível com política.

Referências comparativas iniciais: AppBlock, Freedom e Stay Focused. O Crítico deve atualizar a comparação na web antes da decisão de cada recurso.

## Parte 5 — revisão integral final

O Crítico deve revisar novamente:

1. bloqueios, bypass e desinstalação;
2. segurança, privacidade e persistência;
3. políticas Play e permissões;
4. bateria e performance;
5. arquitetura e concorrência;
6. UI, acessibilidade e adaptabilidade;
7. métricas e cálculos;
8. testes/CI/release;
9. documentação e i18n;
10. lacunas competitivas atuais.

A auditoria só encerra sem P0/P1 abertos. P2/P3 restantes precisam estar explicitamente documentados e deliberadamente aceitos.
