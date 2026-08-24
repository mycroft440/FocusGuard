# Device Admin fast-path fix

## Objetivo

Igualar o bloqueio de Device Admin ao caminho rápido já usado para Acessibilidade/App Info: interceptar o mais cedo possível, impedir reentrada repetida e retirar Configurações do primeiro plano/tarefa protegida.

## Checklist

- [x] Não ignorar cliques reais durante a janela interna de reset de Configurações.
- [x] Fechar a janela interna de reset assim que a tela de credencial segura estiver desenhada.
- [x] Detectar a classe de Device Admin em eventos de transição antes de qualquer leitura da árvore.
- [x] Usar os localizadores curtos de Device Admin no clique ambíguo antes do fallback amplo de raiz.
- [x] Em qualquer detecção confirmada de Device Admin, manter cortina e forçar HOME/launcher, além de limpar a tarefa protegida via MasterRemovalActivity.
- [x] Cobrir o rótulo atual da One UI com teste unitário e preservar os testes puros da janela de reset.
- [x] Remover toda a infraestrutura temporária usada para aplicar o patch e restaurar o workflow Android CI original.
- [x] Executar Unit Tests, Lint, APK/AAB e validação de assinatura no código final antes da integração.

## Estado

Implementação concluída. O código final foi validado no commit `2fd95b5ceebd13e6a9512121a19da92610280f4a` pelo Android CI Pro #891: Unit Tests, Android Lint, performance harness, build APK/AAB, verificação `apksigner` e publicação dos artefatos passaram. A atualização deste arquivo é somente documental.
