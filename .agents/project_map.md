# Project Map (Gerado Automaticamente)

## Estrutura de Diretórios
- **.agents** - **.github** - **.gradle** - **.vscode** - **app** - **config** - **gradle** - **scripts** - **workflows** - **workflows** - **8.4** - **buildOutputCleanup** - **kotlin** - **vcs-1** - **build** - **src** - **detekt** - **wrapper**

## Padrões de Design
- Definir padrões aqui...

---
*Dica para Agentes: Este mapa foi gerado automaticamente. Atualize-o com responsabilidades específicas.*

## Proteção web
- `WebsiteIdentificationRecovery`: recuperação limitada à janela atual, com releitura, ativação de barra certificada e revelação do viewport. Não escreve nem navega. Após cada ação, aguarda um novo evento da mesma janela com timeout curto em vez de depender de sleeps fixos.
- `BrowserObservationSignal`: publica somente um contador monotônico por pacote/janela para sincronizar recovery com novos eventos de Accessibility; nunca compartilha `AccessibilityNodeInfo`.
- `BrowserDetector`: confirma navegador por capacidade genérica HTTP+HTTPS; estados `UNKNOWN` usam retry com backoff curto e só podem virar `PROBABLE_BROWSER` por evidência positiva forte da mesma versão instalada.
- `BrowserCompatibilityStore`: persiste identificação de URL e recuperação por pacote, separadas das preferências de edição; endereço injetado e aceitação de ação não confirmam navegação. Evidência usada para promover navegador fica vinculada ao `versionCode` que realmente a produziu.
- `BlockingAccessibilityService`: aplica regras ao resultado recuperado, coordena tentativas de escrita/envio com raízes novas e só libera a cortina após confirmação do destino.
