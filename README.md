# FocusGuard

FocusGuard é um aplicativo Android avançado de produtividade que utiliza políticas de administração de dispositivo para impor o foco.

## 🚀 Guia de Desenvolvimento

Para facilitar o desenvolvimento e economizar recursos computacionais (tokens), este projeto utiliza uma estrutura de agentes na pasta `.agents/`.

### 📂 Estrutura do Projeto
- **Referência Rápida**: Consulte [.agents/project_map.md](.agents/project_map.md) para entender onde cada componente reside.
- **Economia de Tokens**: Siga as diretrizes em [.agents/token_savings.md](.agents/token_savings.md).
- **Novas Funcionalidades**: Siga o workflow em [.agents/workflows/new_feature.md](.agents/workflows/new_feature.md).

## 🛠️ Tecnologias
- **Linguagem**: Kotlin
- **UI**: Jetpack Compose (Material 3)
- **Persistência**: Room, SharedPreferences, EncryptedSharedPreferences
- **Segurança**: Device Policy Manager, Biometrics

## 🏗️ Como Buildar
1. Clone o repositório.
2. Abra no Android Studio (Koala ou superior).
3. Execute `./gradlew assembleDebug` para gerar o APK.

## 📋 Code Review

Uma avaliação técnica completa do projeto está disponível em [`docs/CODE_REVIEW.md`](docs/CODE_REVIEW.md), cobrindo arquitetura, testes, code smells, i18n, segurança e plano de ação em 4 fases.

O desenho e as limitações do bloqueio de sites sem VPN estão documentados em
[`docs/WEBSITE_BLOCKING.md`](docs/WEBSITE_BLOCKING.md).
