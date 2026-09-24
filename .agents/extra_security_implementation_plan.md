# Bloqueios de segurança extras

- Adicionar entrada no menu existente (SettingsScreen) e tela própria com a opção de bloquear fontes desconhecidas.
- Usar apenas DevicePolicyManager: Android 10+ aplica a restrição global; Android 8/9 aplica a restrição do usuário atual, deixando o escopo explícito.
- Não fingir revogação individual: REQUEST_INSTALL_PACKAGES é acesso especial, não permissão runtime. Oferecer revisão manual das permissões antes do bloqueio. Sem Device Owner, mostrar indisponibilidade e o guia já existente.
- Usar a política persistida pelo próprio Android como fonte de verdade; conferir leitura após gravação, falha não pode produzir indicação de sucesso. Nenhuma alteração automática em ADB, lojas, outras configurações ou proteções.
- Integrar somente a limpeza na remoção legítima do Device Owner. O bloqueio opt-in não faz parte do shield geral nem das sessões de bloqueio.
- Testar administrador comum versus Device Owner, persistência, API 26/28/29/34, escopo, falha, ativação/desativação e preservação de outras políticas.
- Validar traduções, testes, compilação e lint no CI.

Referências: developer.android.com/reference/android/os/UserManager (DISALLOW_INSTALL_UNKNOWN_SOURCES e DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY); developer.android.com/reference/android/app/admin/DevicePolicyManager (setPermissionGrantState limita-se a runtime permissions).
