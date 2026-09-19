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
- `WebsiteBlockNoticeActivity`: única superfície visual dedicada a um site bloqueado conhecido. Não identifica URL e não manipula a barra do navegador.
- `GenericBlockNoticeActivity`: superfície de bloqueio de apps e estados fail-closed sem alvo web conhecido; não contém automação de website.
- `accessibility/website/redirection`: executa somente ações certificáveis de barra de endereço para redirecionamento na mesma aba.
- `BlockingAccessibilityService`: orquestra identificação → decisão → cortina → redirecionamento → confirmação/fail-closed; não deve criar uma segunda implementação paralela dessas responsabilidades.
- Arquitetura detalhada: `docs/WEBSITE_BLOCKING_ARCHITECTURE.md`.
