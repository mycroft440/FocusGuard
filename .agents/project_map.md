# Project Map (Gerado Automaticamente)

## Estrutura de Diretórios
- **.agents** - **.github** - **.gradle** - **.vscode** - **app** - **config** - **gradle** - **scripts** - **workflows** - **workflows** - **8.4** - **buildOutputCleanup** - **kotlin** - **vcs-1** - **build** - **src** - **detekt** - **wrapper**

## Padrões de Design
- Definir padrões aqui...

---
*Dica para Agentes: Este mapa foi gerado automaticamente. Atualize-o com responsabilidades específicas.*

## Proteção web
- `accessibility/website/compatibility`: reconhece navegador/capacidades. Não decide bloqueio nem navega.
- `WebsiteIdentificationEngine` / `WebsiteIdentificationRecovery`: identificam o endereço atual na janela do navegador. Recuperação é limitada à janela atual, com releitura, ativação de barra certificada e revelação do viewport. Não escrevem nem navegam.
- `BrowserObservationSignal`: publica somente um contador monotônico por pacote/janela para sincronizar recovery com novos eventos de Accessibility; nunca compartilha `AccessibilityNodeInfo`.
- `BrowserDetector`: confirma navegador por capacidade genérica HTTP+HTTPS; estados `UNKNOWN` usam retry com backoff curto e só podem virar `PROBABLE_BROWSER` por evidência positiva forte da mesma versão instalada.
- `BrowserCompatibilityStore`: persiste identificação de URL e recuperação por pacote, separadas das preferências de edição; endereço injetado e aceitação de ação não confirmam navegação. Evidência usada para promover navegador fica vinculada ao `versionCode` que realmente a produziu.
- `WebsiteBlocker`: normaliza/matcheia regras de site e utilidades de domínio.
- `accessibility/website/blocking/WebsiteBlockDecisionPolicy`: resolve a propriedade HARD/PASSWORD/NONE de um candidato já identificado. Não é dona da UI nem do redirecionamento. `service/WebsiteProtectionHierarchyPolicy` é apenas facade de compatibilidade para o orquestrador atual.
- A apresentação HARD normal de um site bloqueado é a cortina opaca `TYPE_ACCESSIBILITY_OVERLAY`; ela mantém o navegador como janela ativa para permitir a reescrita da mesma aba.
- `WebsiteBlockNoticeActivity`: superfície terminal/fail-closed para um site bloqueado conhecido. Não participa antes do redirecionamento normal, não identifica URL e não manipula a barra do navegador.
- `GenericBlockNoticeActivity`: superfície de bloqueio de apps e estados fail-closed sem alvo web conhecido; não contém automação de website.
- `accessibility/website/redirection/WebsiteRedirectDestination`: única fonte de verdade do destino seguro e da validação de sua superfície. O destino inicial é `https://www.google.com`.
- `accessibility/website/redirection/WebsiteRedirectionCoordinator`: dono da transação de redirecionamento após a apresentação; controla tentativas na mesma aba, retry limitado, confirmação, terminal estrito e fail-closed. Também contém `WebsiteTabNeutralizationPolicy`.
- `AddressBarRedirectionActions`, `ClipboardPasteFallback` e `WebsiteRedirectionPlan`: executam/definem somente ações certificáveis de barra e limites/fases do redirecionamento.
- `BlockingAccessibilityService`: adaptador Android que conecta identificação → decisão → apresentação overlay → coordinator/destino → confirmação/fail-closed. Fornece janelas/roots/actions/eventos ao coordinator; não é fonte de verdade da URL segura nem decide a sequência da transação.
- Arquitetura detalhada: `docs/WEBSITE_BLOCKING_ARCHITECTURE.md`.
