# Plano de implementação — fluidez do bloqueio por senha

## Objetivo
Eliminar os engasgos percebidos na etapa **Configurar bloqueio por senha**, especialmente ao focar, digitar e alternar entre os campos de senha e confirmação com o teclado aberto.

## Diagnóstico
- `FinalConfigStep` mantinha `unlockPassword` e `unlockPasswordConfirmation` como estados observados diretamente pelo composable raiz.
- Cada caractere digitado invalidava um escopo que também monta `Scaffold`, card de configuração, lógica de biometria, estado do padrão, mensagens de validação, botão de ativação e host de rolagem.
- O `AccessibilityService` já ignora eventos cujo pacote de origem é o próprio FocusGuard antes de inspecionar janelas, navegador ou árvore de acessibilidade; portanto esse pipeline não é a causa contínua desta tela.
- Validação da senha, persistência da credencial e criação da sessão só são necessárias quando o usuário toca em **Ativar bloqueio**, não durante a digitação.

## Alterações
1. Manter as duas credenciais apenas em memória, como antes, mas armazenar no composable raiz somente os objetos `MutableState` sem observar seus valores durante composição.
2. Isolar `Senha de desbloqueio` e `Confirmar senha` no subcomposable `PasswordCredentialEditor`, limitando a recomposição por caractere ao pequeno subtree dos campos.
3. Ler os valores das credenciais no fluxo principal somente ao validar/ativar o bloqueio e ao limpar o método ao voltar.
4. Limpar `configError` na primeira edição necessária sem propagar uma mutação de estado do pai em todas as teclas seguintes.
5. Preservar integralmente requisitos de senha, confirmação, biometria, hash/persistência, alvos protegidos e criação da sessão PASSWORD.
6. Não adicionar `imePadding`, pois a `MainActivity` já usa `adjustResize`; evitar dupla aplicação de inset do teclado.

## Validação
- Compilar APK e AAB da branch final.
- Executar testes unitários e Android Lint.
- Confirmar que os valores digitados continuam sendo validados somente ao ativar e que senhas divergentes continuam sendo rejeitadas.
- Confirmar em dispositivo que digitação, mudança de foco e abertura/fechamento do teclado permanecem fluidos nessa etapa.
- Confirmar que voltar para a seleção do método limpa as credenciais mantidas em memória.

## Critério de conclusão
A etapa de senha deve responder continuamente durante digitação e troca de foco, sem recompor a estrutura completa de `FinalConfigStep` a cada caractere e sem alterar qualquer semântica de autenticação ou bloqueio.