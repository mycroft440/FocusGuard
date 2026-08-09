# Plano de implementação — acessos diários por aplicativo

## Objetivo

Contabilizar quantas vezes cada aplicativo foi efetivamente usado no dia. Um
acesso corresponde a entrar no aplicativo e depois sair dele.

## Regra de contabilização

1. Um evento de primeiro plano inicia uma sessão de acesso.
2. A sessão só é contabilizada após a saída do aplicativo.
3. Trocas de Activity dentro do mesmo app não geram novos acessos.
4. Aplicativo ainda aberto no fim da consulta não é contabilizado até sair.
5. Desligar a tela encerra a sessão de uso atual.
6. Apps não inicializáveis, como componentes do sistema, não aparecem no ranking.

## Validação

- Testar entrada e saída simples.
- Testar app ainda aberto.
- Testar troca de Activities no mesmo app.
- Testar troca entre aplicativos.
- Testar eventos incompletos e tela desligada.
- Executar testes unitários, lint e compilação dos APKs.
