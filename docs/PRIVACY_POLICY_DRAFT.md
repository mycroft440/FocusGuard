# Política de Privacidade do FocusGuard — rascunho

Última atualização: 25 de julho de 2026

Este documento descreve o comportamento da versão 2.5.0 do FocusGuard. Antes de publicar o aplicativo, este texto deve ser hospedado em uma página HTTPS pública e o endereço deve ser informado no Google Play Console e dentro do aplicativo.

## Visão geral

O FocusGuard é um aplicativo de foco e autocontrole que permite ao próprio usuário configurar bloqueios, limites de uso, sessões de foco e métricas. O aplicativo não possui SDK de publicidade e não vende dados pessoais.

## Dados e acessos utilizados

### Acessibilidade

Quando o usuário ativa voluntariamente o serviço de Acessibilidade, o FocusGuard pode identificar o aplicativo ou navegador visível e ler informações exibidas na janela, incluindo texto e endereços de sites. Esse acesso é usado somente para aplicar regras de bloqueio e limites configuradas pelo usuário.

O serviço não é apresentado como ferramenta destinada a pessoas com deficiência e não controla o aparelho remotamente. Em instalações pessoais comuns, ele não impede que o usuário desative o serviço nas configurações do Android. Em um aparelho provisionado deliberadamente como Device Owner, enquanto houver um bloqueio ativo e a manutenção autenticada estiver fechada, ele também identifica e fecha telas do sistema que removeriam a Acessibilidade, o administrador ou o próprio FocusGuard. Esse comportamento de dispositivo gerenciado é configurado pelo proprietário do aparelho e pode ser liberado pela manutenção administrativa.

### Acesso de uso

Quando autorizado, o FocusGuard consulta estatísticas de uso fornecidas pelo Android para calcular limites diários e apresentar métricas. Esses dados são processados no aparelho.

### Aplicativos instalados

O FocusGuard consulta somente aplicativos que possuem uma atividade visível no inicializador e navegadores compatíveis, para permitir que o usuário escolha o que deseja bloquear. O aplicativo não solicita acesso irrestrito ao inventário completo de pacotes instalados.

### Câmera

A câmera é opcional e só é solicitada quando o usuário ativa a função de foto após tentativas incorretas de senha. As imagens geradas ficam armazenadas no espaço privado do aplicativo e podem ser vistas ou removidas pelo próprio usuário.

### Dados locais

Senhas são armazenadas como verificadores criptográficos com salt. Sessões, listas de bloqueio, limites, preferências, métricas e fotos opcionais ficam no aparelho. O backup do aplicativo está desativado no manifesto.

### Internet

A internet pode ser usada para carregar recursos públicos, como ícones de sites, e para o fórum descrito abaixo. O FocusGuard não envia métricas para servidores de análise. A única conta em nuvem é a do fórum, e ela só existe quando o usuário decide entrar nele.

### Fórum

Quando a versão publicada tem o fórum compartilhado ativado, o usuário pode entrar com a conta Google para ler e publicar. O login é feito pelo Android (Credential Manager), e o FocusGuard recebe apenas um token de identificação. O servidor do fórum (um script do Google Apps Script mantido pelo responsável pelo FocusGuard) confere esse token com o Google e guarda somente um código derivado da conta. O e-mail e o nome da conta Google não são gravados.

Ficam guardados, numa planilha do Google Sheets do responsável pelo FocusGuard: o código da conta, o nome e o avatar escolhidos no perfil do aplicativo, as publicações, comentários e curtidas, as notificações de interação e as denúncias feitas. Publicações, comentários, nomes e avatares ficam visíveis para todos os usuários do fórum. Conteúdo denunciado por três pessoas diferentes é ocultado automaticamente e pode ser removido pela moderação, assim como contas que desrespeitem as regras podem ser impedidas de publicar.

Quando o fórum compartilhado não está ativado, publicações, curtidas e comentários ficam salvos apenas no aparelho.

## Compartilhamento

O FocusGuard não vende dados e não compartilha dados pessoais com anunciantes. Recursos públicos externos podem receber dados técnicos normais de uma requisição de rede, como endereço IP, quando um ícone é carregado.

## Controle do usuário

O usuário pode:

- negar ou revogar permissões nas configurações do Android;
- continuar usando recursos limitados sem Acessibilidade ou Acesso de uso;
- apagar sessões, limites, senhas e fotos no aplicativo;
- apagar as próprias publicações e comentários do fórum, sair do fórum e pedir a exclusão dos dados do fórum pelo contato abaixo;
- desinstalar o aplicativo em instalações pessoais comuns.

Em aparelhos corporativos provisionados deliberadamente como Device Owner, políticas de administração seguem o fluxo oficial do Android e precisam ser removidas pela manutenção administrativa antes da desinstalação.

## Segurança

O aplicativo bloqueia tráfego HTTP sem criptografia, desativa backups do Android, usa armazenamento criptografado para configurações sensíveis e assina versões de produção com esquemas modernos de assinatura do Android.

## Contato

Antes da publicação, substitua esta seção por um endereço de contato público e verificável do responsável pelo FocusGuard.
