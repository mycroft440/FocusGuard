# FocusGuard — Guia de Provisionamento Device Owner (Proteção Nuclear)

Este documento descreve como provisionar o FocusGuard como **Device Owner**
para ativar a camada máxima de proteção anti-bypass.

## 📖 O que é Device Owner?

Device Owner é o nível mais alto de privilégio do Android Enterprise. Quando
um app é Device Owner, ele pode:

- ✅ **Impedir desinstalação** (`DISALLOW_UNINSTALL_APPS`) — botão fica cinza
- ✅ **Impedir Safe Boot** (`DISALLOW_SAFE_BOOT`) — bloqueia modo de segurança
- ✅ **Impedir Factory Reset** (`DISALLOW_FACTORY_RESET`)
- ✅ **Bloquear URLs no Chrome/Edge** via Managed Configurations (`URLBlocklist`)
- ✅ **Forçar Private DNS DoT** com servidor filtrante (CleanBrowsing Adult)
- ✅ **Suspender apps** nativamente via `setPackagesSuspended` (0ms — ícone fica cinza)

Sem Device Owner, o FocusGuard funciona com proteção padrão (Accessibility
+ Device Admin), mas algumas defesas ficam indisponíveis.

## ⚠️ Pré-requisitos CRÍTICOS

Para provisionar como Device Owner, o dispositivo DEVE estar em estado
**factory-fresh**:

1. **NENHUMA conta Google logada** — Settings → Users → remover todas as contas
   (Google, Samsung, Xiaomi, etc.)
2. **Nenhum outro Device Owner já configurado** — verifique com:
   ```bash
   adb shell dpm list-owners
   ```
3. **Depuração USB ativada** — Settings → Developer Options → USB Debugging
4. **Cabo USB conectado** a um computador com ADB instalado

> ⚠️ Se o dispositivo já tem contas Google logadas, você precisa removê-las
> ANTES de tentar o provisionamento. Sem isso, o comando `dpm set-device-owner`
> retorna erro `java.lang.IllegalStateException`.

## 🚀 Passo a passo

### Passo 1 — Preparar o ambiente no PC

```bash
# Instalar ADB (Ubuntu/Debian)
sudo apt install adb

# Ou baixar platform-tools diretamente:
# https://developer.android.com/tools/releases/platform-tools

# Verificar que ADB reconhece o dispositivo
adb devices
# Deve listar algo como:
# List of devices attached
# XXXXXX    device
```

### Passo 2 — Remover todas as contas do dispositivo

No celular:
1. Settings → Users & accounts (ou Contas)
2. Remova TODAS as contas Google, Samsung, Xiaomi, etc.
3. Mantenha apenas "Owner" (usuário principal)

### Passo 3 — Instalar o APK do FocusGuard

```bash
# Compile o APK (ou baixe do GitHub Actions artifact)
./gradlew assembleDebug

# Instale no dispositivo
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Passo 4 — Provisionar como Device Owner

```bash
adb shell dpm set-device-owner com.focusguard.v2/com.focusguard.admin.FocusGuardDeviceAdminReceiver
```

**Saída esperada:**
```
Success: Device owner set to package com.focusguard.v2 on user 0
```

**Erros comuns:**

| Erro | Causa | Solução |
|------|-------|---------|
| `IllegalStateException: Not allowed to ... already has accounts` | Conta Google logada | Remova todas as contas em Settings → Users |
| `SecurityException: Not allowed to set device owner` | Outro DO já ativo | Factory reset e tente novamente |
| `Unknown command 'dpm'` | ADB muito antigo | Atualize platform-tools |

### Passo 5 — Verificar provisionamento

No app FocusGuard:
1. Abra o app
2. Vá em Settings → Proteção Nuclear (Device Owner)
3. Deve mostrar "Device Owner Ativo: true"

Ou via ADB:
```bash
adb shell dpm list-owners
# Deve mostrar: com.focusguard.v2
```

### Passo 6 — Re-logar nas contas Google (opcional)

Após o provisionamento bem-sucedido, você pode logar novamente nas contas
Google normalmente. O Device Owner permanece ativo.

## 🛡️ Defesas ativas após provisionamento

Após Device Owner ativo, o FocusGuard aplica automaticamente:

### Policies persistentes (Nuclear Shield)

```kotlin
GLOBAL_SHIELD_RESTRICTIONS = [
    DISALLOW_FACTORY_RESET,      // impede factory reset
    DISALLOW_SAFE_BOOT,          // impede modo de segurança
    DISALLOW_UNINSTALL_APPS,     // impede desinstalar apps
    DISALLOW_APPS_CONTROL        // impede Force Stop / Disable
]
```

### Policies por sessão de bloqueio

```kotlin
SESSION_RESTRICTIONS = [
    DISALLOW_ADD_USER,
    DISALLOW_REMOVE_USER,
    DISALLOW_CONFIG_DATE_TIME
]
```

### Bloqueio de sites (URLBlocklist, sem VPN)

- Chrome e Edge respeitam `URLBlocklist` — bloqueio no renderer (0ms)
- O modo privado é desativado enquanto houver regras ativas
- Firefox, Brave, Opera e outros usam a camada de acessibilidade
- Private DNS via CleanBrowsing é um filtro adulto separado e opcional

### Bloqueio de apps (setPackagesSuspended)

- Apps bloqueados ficam cinza na home screen
- Se o usuário tentar abrir, o Android mostra aviso nativo (0ms)
- Não consome CPU/bateria — bloqueio é em nível de sistema

## 🔓 Como remover o Device Owner (legitimamente)

Se você quiser desinstalar o FocusGuard no futuro:

1. Abra FocusGuard → Settings → Proteção Nuclear
2. Toque em "Revogar Device Owner"
3. Confirme

Ou via ADB:
```bash
adb shell dpm remove-active-admin com.focusguard.v2/com.focusguard.admin.FocusGuardDeviceAdminReceiver
```

Após remover, você pode desinstalar normalmente.

## 🐛 Troubleshooting

### "Provisionamento falhou em Android 13+"

Em Android 13+, alguns OEMs (Xiaomi, Oppo) bloqueiam `dpm set-device-owner`
mesmo sem contas. Solução:

1. Factory reset do dispositivo
2. Não faça setup inicial (pule Google Account)
3. Habilite ADB
4. Provisione imediatamente

### "Device Owner ativo mas bloqueio não funciona"

Verifique:
- ✅ Accessibility Service habilitado em Settings → Accessibility
- ✅ Usage Access concedido em Settings → Apps → FocusGuard → Permissões
- ✅ Battery optimization desativado para FocusGuard
- ✅ FocusGuard não está no modo "App Restrito" (Android 13+ → Settings → Apps → FocusGuard → menu → Allow restricted settings)

### "Private DNS não aplica"

- Verifique se Device Owner está ativo
- Verifique se Android é 9.0+ (Private DNS DoT é API 28+)
- Em Settings → Network → Private DNS, deve mostrar "dns.cleanbrowsing.org"

## 📊 Comparação: com vs sem Device Owner

| Defesa | Sem DO | Com DO |
|--------|--------|--------|
| Bloqueio de apps (Accessibility) | ✅ reativo (ms) | ✅ reativo (ms) |
| Bloqueio de apps (setPackagesSuspended) | ❌ | ✅ proativo (0ms) |
| Bloqueio de sites (Accessibility) | ✅ | ✅ |
| Bloqueio preventivo no Chrome/Edge | ❌ | ✅ URLBlocklist, renderer-level |
| Anti-desinstalação | ⚠️ reativo (onDisableRequested) | ✅ preventivo (botão cinza) |
| Anti Safe Mode | ❌ vulnerável | ✅ bloqueado |
| Anti Factory Reset | ❌ vulnerável | ✅ bloqueado |
| DNS Adulto (CleanBrowsing) | ❌ | ✅ Private DNS do sistema |

## 🔐 Segurança & Privacidade

- Device Owner **NÃO dá acesso root** — é uma API nativa do Android Enterprise
- Device Owner **NÃO permite** ler dados de outros apps
- Device Owner **NÃO permite** gravar áudio/câmera secretamente
- FocusGuard só usa DO para as policies listadas acima — nada mais

Para auditoria, leia o código em:
- `app/src/main/java/com/focusguard/admin/DeviceOwnerManager.kt`
- `app/src/main/java/com/focusguard/admin/FocusGuardDeviceAdminReceiver.kt`
- `app/src/main/res/xml/device_admin_policy.xml`

## 🆘 Suporte

Se algo deu errado:
1. Verifique os logs do app em `Android/data/com.focusguard.v2/files/FocusGuardLogs/`
2. Abra issue em https://github.com/mycroft440/FocusGuard/issues com:
   - Modelo do dispositivo
   - Versão do Android
   - Output de `adb shell dpm list-owners`
   - Logs do app
