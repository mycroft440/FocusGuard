# FocusGuard

FocusGuard é um aplicativo Android focado em aumentar a produtividade, bloqueando aplicativos e sites distrativos rigorosamente.

## Guia de Instalação e Atualização (Atenção: Erro "App não instalado")

Devido às pesadas permissões de **Device Admin** (Administrador do Dispositivo) e **Device Owner** (Opção Nuclear), o Android possui uma forte trava de segurança contra alterações no app.

**SE VOCÊ RECEBER O ERRO "App não instalado" AO TENTAR ATUALIZAR:**

Isso ocorre porque:
1. O app antigo ainda está ativo como Administrador do Dispositivo (Device Admin / Owner).
2. Há um conflito entre a assinatura do APK instalado (ex: via cabo USB/Android Studio) e o APK baixado do GitHub Actions.

### Como desinstalar completamente a versão antiga (Obrigatório)

Você não conseguirá arrastar o app para a lixeira se ele for "Device Admin".
Conecte o celular ao computador via cabo USB, abra o terminal e execute:

```bash
# 1. Comando obrigatório para remover as credenciais de Admin:
adb shell dpm remove-active-admin com.focusguard.v2/.admin.FocusGuardDeviceAdminReceiver

# 2. Comando para desinstalar o app por completo:
adb uninstall com.focusguard.v2
```

*(Nota: o comando acima usa `com.focusguard.v2`. Se o seu app antigo usava `com.focusguard`, troque o nome do pacote no comando).*

Após essas etapas, você poderá instalar o APK baixado sem nenhum problema!
