# Checklist de confiança e publicação no Google Play

Este checklist separa melhorias de código das etapas que somente o proprietário da conta do Google Play pode concluir.

## Código concluído no projeto

- [x] Remover `QUERY_ALL_PACKAGES` e usar visibilidade direcionada com `<queries>`.
- [x] Remover permissões não utilizadas de rede e a solicitação direta de isenção de bateria.
- [x] Manter tráfego HTTP sem criptografia desativado.
- [x] Marcar o serviço como `isAccessibilityTool="false"`.
- [x] Informar claramente o que a Acessibilidade observa e por quê.
- [x] Obter consentimento afirmativo antes de abrir permissões sensíveis.
- [x] Permitir continuar sem conceder as permissões.
- [x] Não impedir a desativação da Acessibilidade em instalações pessoais.
- [x] Substituir a tela coerciva de reativação por notificação dispensável.
- [x] Manter anti-remoção forte somente no fluxo oficial de Device Owner.
- [x] Limitar a interceptação de telas anti-remoção a Device Owner com bloqueio ativo e manutenção fechada.
- [x] Usar assinatura APK v2 e v3 para versões de produção.
- [x] Desativar backup e captura de áudio da interface do aplicativo.

## Etapas obrigatórias fora do código

- [ ] Criar a ficha do FocusGuard no Google Play Console.
- [ ] Ativar o Play App Signing e guardar com segurança a chave de upload.
- [ ] Publicar um Android App Bundle (`.aab`) de release; não distribuir o APK de debug como versão oficial.
- [ ] Hospedar a política de privacidade em uma URL HTTPS pública.
- [ ] Preencher a seção Segurança dos dados de acordo com o comportamento real do app.
- [ ] Preencher a declaração da API AccessibilityService.
- [ ] Enviar um vídeo mostrando a divulgação, o consentimento e a função principal que usa Acessibilidade.
- [ ] Declarar e justificar Acesso de uso, alarme exato, Device Admin e serviço em primeiro plano quando solicitado pelo Play Console.
- [ ] Usar uma faixa de teste interno antes da produção e instalar a versão diretamente pelo Google Play.
- [ ] Ativar Play Integrity e verificar o veredito `PLAY_RECOGNIZED` na versão distribuída.
- [ ] Atualizar `compileSdk` e `targetSdk` para a exigência vigente antes do envio à produção.
- [ ] Informar um e-mail público e verificável de suporte e privacidade.

## Testes físicos recomendados

- [ ] Android puro/Pixel.
- [ ] Samsung One UI.
- [ ] Xiaomi/HyperOS.
- [ ] Instalação pela faixa interna do Google Play.
- [ ] Acessibilidade ativada, desativada e reativada voluntariamente.
- [ ] Seleção de todos os aplicativos com ícone no launcher após remover `QUERY_ALL_PACKAGES`.
- [ ] Bloqueio de Chrome, Samsung Internet, Firefox, aplicativos comuns e sites.
- [ ] Reinício do aparelho com sessões agendadas.
- [ ] Reinício durante a manutenção Device Owner, verificando restauração antes do primeiro desbloqueio.
- [ ] Device Owner em aparelho de teste dedicado.
- [ ] Samsung One UI: tentar Acessibilidade, lista de administradores, informações do app e desinstalação durante bloqueio Device Owner.

## Observação importante

Um APK instalado manualmente pode continuar recebendo o aviso de configurações restritas do Android. Esse aviso está ligado à origem da instalação e ao acesso sensível solicitado. A versão reconhecida oficialmente precisa corresponder ao pacote, certificado e código distribuídos pelo Google Play.
