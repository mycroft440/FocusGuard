# Plano de implementação — permissões pendentes precisas

## Objetivos

1. Identificar separadamente Acessibilidade e Acesso de uso.
2. Exibir no aviso inicial somente os nomes das permissões realmente ausentes.
3. Abrir um fluxo reduzido contendo apenas as pendências essenciais.
4. Atualizar o estado assim que o usuário voltar das configurações do Android.

## Regras do fluxo

- O aviso não aparece enquanto Acessibilidade e Acesso de uso estiverem ativos.
- Se apenas uma autorização faltar, o texto e a tela citam somente essa opção.
- O atalho não inclui Notificações, Bateria irrestrita ou Admin do dispositivo,
  pois essas permissões são opcionais.
- O onboarding completo continua oferecendo as cinco etapas existentes.
- Ao voltar das configurações, uma autorização concedida é removida da sequência.

## Validação

- Testar todas as combinações das duas permissões essenciais.
- Testar a lista reduzida e preservar o fluxo completo do onboarding.
- Conferir paridade dos recursos em português e inglês.
- Executar testes unitários, lint e compilação dos APKs.
