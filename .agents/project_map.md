# Project Map (Gerado Automaticamente)

## Estrutura de Diretórios
- **.agents** - **.github** - **.gradle** - **.vscode** - **app** - **config** - **gradle** - **scripts** - **workflows** - **workflows** - **8.4** - **buildOutputCleanup** - **kotlin** - **vcs-1** - **build** - **src** - **detekt** - **wrapper**

## Padrões de Design
- Definir padrões aqui...

---
*Dica para Agentes: Este mapa foi gerado automaticamente. Atualize-o com responsabilidades específicas.*

## Proteção web
- `sitesblocker/` (Java): único bloqueio de sites e de navegadores não suportados, vindo do app Bloquear Sites. `SiteBlockEngine` recebe os eventos do `BlockingAccessibilityService`; `BlockRedirectController`/`AddressBarNavigator` cobrem a tela e trocam o site pelo Google; `BrowserProfiles`/`IdentifiedBrowsers`/`VerifiedBrowsers` decidem quais navegadores são suportados; `AdultContentFilter` é o bloqueio de pornografia. As regras vêm só dos bloqueios, pelo `SiteBlockEngine.ExternalRules`; a lista da antiga tela Bloquear sites (`blocked_sites`) é apagada por `LegacySitesList`.
- Bloqueio por senha, limite diário e períodos do dia aceitam apps e sites; bloquear sem senha por tempo também aceita palavras (`BlockTargetPolicy`). `WebsiteBlocker` compara as URLs com essas regras.
- Detalhes: `docs/WEBSITE_BLOCKING.md`.
