# Workflow: Nova Funcionalidade (FocusGuard)

Use este workflow ao receber a tarefa de criar uma nova tela ou recurso lógico.

## Passo 1: Definição de Dados
1.  Verifique se é necessário um novo modelo em `com.focusguard.data`.
2.  Se precisar de persistência, atualize o `AppDatabase.kt` e crie um novo `DAO`.

## Passo 2: Lógica de Gerenciamento
1.  Implemente ou atualize um `Manager` em `com.focusguard.manager`.
2.  Use Coroutines para operações assíncronas.

## Passo 3: Implementação de UI
1.  Crie a tela em `com.focusguard.ui.compose.screens`.
2.  Use o tema padrão do projeto (`com.focusguard.ui.compose.theme`).
3.  Implemente `AnimatedVisibility` para transições fluidas.
4.  Certifique-se de que todas as strings estejam em `res/values/strings.xml`.

## Passo 4: Integração e Navegação
1.  Adicione a nova rota no `NavHost` dentro da `MainActivity.kt`.
2.  Verifique se a nova funcionalidade respeita as travas de segurança (ex: Pomodoro ativo).

## Passo 5: Verificação
1.  Execute `./gradlew assembleDebug`.
2.  Teste a navegação entre telas.
