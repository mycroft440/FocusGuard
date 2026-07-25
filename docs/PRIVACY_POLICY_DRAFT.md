# Política de Privacidade do FocusGuard — rascunho

Última atualização: 25 de julho de 2026

Este documento descreve o comportamento da versão 2.5.0 do FocusGuard. Antes de publicar o aplicativo, este texto deve ser hospedado em uma página HTTPS pública e o endereço deve ser informado no Google Play Console e dentro do aplicativo.

## Visão geral

O FocusGuard é um aplicativo de foco e autocontrole que permite ao próprio usuário configurar bloqueios, limites de uso, sessões de foco e métricas. O aplicativo não possui SDK de publicidade e não vende dados pessoais.

## Dados e acessos utilizados

### Acessibilidade

Quando o usuário ativa voluntariamente o serviço de Acessibilidade, o FocusGuard pode identificar o aplicativo ou navegador visível e ler informações exibidas na janela, incluindo texto e endereços de sites. Esse acesso é usado somente para aplicar regras de bloqueio e limites configuradas pelo usuário.

O serviço não é apresentado como ferramenta destinada a pessoas com deficiência, não controla o aparelho remotamente e não impede que o usuário desative o serviço nas configurações do Android.

### Acesso de uso

Quando autorizado, o FocusGuard consulta estatísticas de uso fornecidas pelo Android para calcular limites diários e apresentar métricas. Esses dados são processados no aparelho.

### Aplicativos instalados

O FocusGuard consulta somente aplicativos que possuem uma atividade visível no inicializador e navegadores compatíveis, para permitir que o usuário escolha o que deseja bloquear. O aplicativo não solicita acesso irrestrito ao inventário completo de pacotes instalados.

### Câmera

A câmera é opcional e só é solicitada quando o usuário ativa a função de foto após tentativas incorretas de senha. As imagens geradas ficam armazenadas no espaço privado do aplicativo e podem ser vistas ou removidas pelo próprio usuário.

### Dados locais

Senhas são armazenadas como verificadores criptográficos com salt. Sessões, listas de bloqueio, limites, preferências, métricas e fotos opcionais ficam no aparelho. O backup do aplicativo está desativado no manifesto.

### Internet

A internet pode ser usada para carregar recursos públicos, como ícones de sites. O FocusGuard não possui conta em nuvem, publicidade ou envio de métricas para servidores de análise nesta versão.

## Compartilhamento

O FocusGuard não vende dados e não compartilha dados pessoais com anunciantes. Recursos públicos externos podem receber dados técnicos normais de uma requisição de rede, como endereço IP, quando um ícone é carregado.

## Controle do usuário

O usuário pode:

- negar ou revogar permissões nas configurações do Android;
- continuar usando recursos limitados sem Acessibilidade ou Acesso de uso;
- apagar sessões, limites, senhas e fotos no aplicativo;
- desinstalar o aplicativo em instalações pessoais comuns.

Em aparelhos corporativos provisionados deliberadamente como Device Owner, políticas de administração seguem o fluxo oficial do Android e precisam ser removidas pela manutenção administrativa antes da desinstalação.

## Segurança

O aplicativo bloqueia tráfego HTTP sem criptografia, desativa backups do Android, usa armazenamento criptografado para configurações sensíveis e assina versões de produção com esquemas modernos de assinatura do Android.

## Contato

Antes da publicação, substitua esta seção por um endereço de contato público e verificável do responsável pelo FocusGuard.
