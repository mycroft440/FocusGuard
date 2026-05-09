---
description: Como configurar GitHub Actions para compilação de apps Android
---
# Compilando um App Android com GitHub Actions

Este workflow detalha o passo a passo de como configurar a integração contínua (CI) para projetos Android no GitHub Actions utilizando as melhores práticas e documentação oficial.

## 1. Estrutura do Workflow YAML

Os arquivos de workflow devem obrigatoriamente residir no diretório `.github/workflows/` na raiz do seu repositório. O formato é sempre `.yml` ou `.yaml`.

Exemplo: `.github/workflows/android-build.yml`

## 2. Definindo os Triggers (Gatilhos)

Para automatizar a build a cada commit na branch principal (`main`) ou via pull requests:

```yaml
name: Android CI Build

on:
  push:
    branches: [ "main", "master" ]
  pull_request:
    branches: [ "main", "master" ]
  # Permite rodar o workflow manualmente pelo painel do GitHub:
  workflow_dispatch:
```

## 3. O Job de Build e Mapeamento do Ambiente

Recomenda-se rodar em um ambiente Ubuntu (`ubuntu-latest`), pois ele já vem com o SDK do Android pré-instalado.

```yaml
jobs:
  build:
    name: Build Debug APK
    runs-on: ubuntu-latest
```

## 4. Passos (Steps) Necessários

Defina as etapas de execução da build:

### Passo A: Checkout do código
Você precisa baixar o código do repositório para o runner.
```yaml
    steps:
      - name: Checkout repository
        uses: actions/checkout@v4
```

### Passo B: Configurar o JDK
O Android exige Java. Para projetos recentes, a versão 17 é a norma. Utilize também o cache integrado do Gradle para acelerar compilações futuras.
```yaml
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: gradle
```

### Passo C: Dar permissão de execução ao Gradle Wrapper
Em sistemas Unix (como o Ubuntu onde a action roda), o `gradlew` precisa de permissão de execução antes de ser chamado.
```yaml
      - name: Grant execute permission for gradlew
        run: chmod +x gradlew
```

### Passo D: Build do App
Inicia-se a build do APK via terminal do Gradle. O parâmetro `--no-daemon` é ideal para ambientes de CI/CD pois não deixa processos em background ocupando memória.
```yaml
      - name: Build Debug APK
        run: ./gradlew assembleDebug --no-daemon --stacktrace
```

### Passo E: Upload do Artefato (APK) gerado
Para que você possa baixar o `.apk` ao final da compilação:
```yaml
      - name: Upload Debug APK
        uses: actions/upload-artifact@v4
        with:
          name: app-debug
          path: app/build/outputs/apk/debug/app-debug.apk
```

## Workflow Completo Pronto para Uso

Aqui está a junção completa que você pode copiar e colar:

```yaml
name: Android CI Build

on:
  push:
    branches: [ "main", "master" ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: gradle

      - name: Grant execute permission for gradlew
        run: chmod +x gradlew

      - name: Build Debug APK
        run: ./gradlew assembleDebug --no-daemon

      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: app-debug
          path: app/build/outputs/apk/debug/app-debug.apk
```

---
*Referência: Práticas baseadas na documentação oficial do GitHub Actions e Android Developers (setup-java v4 e actions checkout v4).*
