# Plano de implementação — perfil do usuário

## Objetivos

1. Permitir que o usuário defina o nome exibido no FocusGuard.
2. Oferecer cinco avatares coloridos predefinidos e acessíveis.
3. Manter nome e avatar salvos localmente entre reinicializações.
4. Exibir o perfil no início da tela de Configurações.

## Regras do perfil

- O nome é obrigatório, elimina espaços extras e aceita até 40 caracteres
  Unicode sem cortar emojis pela metade.
- O avatar é persistido por identificador estável; valores inválidos voltam ao
  avatar padrão.
- Os dados ficam em preferências privadas do aplicativo e não saem do aparelho.
- A navegação mantém uma única cópia do estado para atualizar Configurações
  imediatamente após salvar.
- Cada opção de avatar funciona como botão de seleção para leitores de tela.

## Validação

- Testar normalização do nome, limite Unicode e os cinco identificadores.
- Conferir paridade dos recursos em português e inglês.
- Executar testes unitários, lint e compilação dos APKs.
