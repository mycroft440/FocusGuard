# FocusGuard - App and Website Blocker

FocusGuard é um aplicativo Android nativo desenvolvido em **Kotlin e Java** que ajuda os usuários a manter o foco bloqueando aplicativos e sites distraentes, **sem usar VPN**.

## Características Principais

- **Bloqueio de Aplicativos**: Detecta e bloqueia aplicativos específicos usando `AccessibilityService`
- **Bloqueio de Websites**: Monitora URLs em navegadores e bloqueia sites bloqueados
- **Armazenamento Local**: Usa Room Database para armazenar apps e sites bloqueados
- **Interface Simples**: Interface com abas para gerenciar apps e sites bloqueados
- **Sem VPN**: Não requer VPN, usa APIs nativas do Android

## Tecnologias Utilizadas

- **Linguagem**: Kotlin e Java
- **API Level**: 21+ (Android 5.0+)
- **Banco de Dados**: Room Database
- **Acessibilidade**: AccessibilityService
- **UI**: Material Design 3, RecyclerView, ViewPager2

## Estrutura do Projeto

```
FocusGuard/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/focusguard/
│   │   │   │   ├── MainActivity.kt
│   │   │   │   ├── adapter/
│   │   │   │   │   ├── TabAdapter.kt
│   │   │   │   │   ├── BlockedAppsAdapter.kt
│   │   │   │   │   └── BlockedWebsitesAdapter.kt
│   │   │   │   ├── database/
│   │   │   │   │   ├── AppDatabase.kt
│   │   │   │   │   ├── Daos.kt
│   │   │   │   │   └── Entities.kt
│   │   │   │   ├── service/
│   │   │   │   │   └── BlockingAccessibilityService.kt
│   │   │   │   ├── ui/
│   │   │   │   │   ├── BlockedAppsFragment.kt
│   │   │   │   │   └── BlockedWebsitesFragment.kt
│   │   │   │   └── utils/
│   │   │   │       └── WebsiteBlocker.kt
│   │   │   ├── res/
│   │   │   │   ├── layout/
│   │   │   │   ├── values/
│   │   │   │   ├── drawable/
│   │   │   │   └── xml/
│   │   │   └── AndroidManifest.xml
│   │   ├── test/
│   │   └── androidTest/
│   └── build.gradle
├── build.gradle
├── settings.gradle
└── gradle.properties
```

## Como Usar

### 1. Clonar o Repositório

```bash
git clone https://github.com/mycroft440/FocusGuard.git
cd FocusGuard
```

### 2. Abrir no Android Studio

1. Abra o Android Studio
2. Selecione "Open an Existing Project"
3. Navegue até a pasta `FocusGuard`
4. Clique em "Open"

### 3. Compilar e Executar

```bash
./gradlew build
./gradlew installDebug
```

### 4. Ativar o Serviço de Acessibilidade

1. Abra as Configurações do Android
2. Vá para **Acessibilidade**
3. Procure por **FocusGuard**
4. Ative o serviço

### 5. Adicionar Apps e Websites Bloqueados

1. Abra o FocusGuard
2. Vá para a aba **Blocked Apps** e clique em "Add App"
3. Vá para a aba **Blocked Websites** e insira o domínio (ex: facebook.com)

## Componentes Principais

### BlockingAccessibilityService

O serviço de acessibilidade que monitora eventos do sistema e detecta quando um app ou website bloqueado está sendo acessado.

**Funcionalidades:**
- Detecta mudanças de janela (`TYPE_WINDOW_STATE_CHANGED`)
- Monitora conteúdo de janelas (`TYPE_WINDOW_CONTENT_CHANGED`)
- Detecta mudanças de texto (`TYPE_VIEW_TEXT_CHANGED`)
- Bloqueia apps redirecionando para a home
- Bloqueia websites voltando para a tela anterior

### Room Database

Armazena de forma persistente:
- Apps bloqueados (`BlockedApp`)
- Websites bloqueados (`BlockedWebsite`)
- Sessões de bloqueio (`BlockSession`)

### WebsiteBlocker Utility

Utilitário para:
- Extrair URLs da árvore de acessibilidade
- Validar URLs
- Extrair domínios de URLs
- Encontrar a barra de endereço do navegador

## Permissões Necessárias

```xml
<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" />
<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

## Próximas Melhorias

- [ ] Interface de usuário aprimorada (conforme fornecido pelo usuário)
- [ ] Agendamento de bloqueios por horário
- [ ] Estatísticas de uso
- [ ] Bloqueio de notificações
- [ ] Modo de foco com timer
- [ ] Sincronização em nuvem (opcional)

## Requisitos do Sistema

- Android 5.0 (API 21) ou superior
- Mínimo 50MB de espaço livre
- Acesso a Configurações de Acessibilidade

## Licença

Este projeto está sob a licença MIT.

## Contato

Para dúvidas ou sugestões, entre em contato através do repositório GitHub.

---

**Desenvolvido com ❤️ para ajudar você a manter o foco.**
