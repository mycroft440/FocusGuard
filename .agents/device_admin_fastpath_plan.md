# Device Admin fast-path fix

## Objetivo

Igualar o bloqueio de Device Admin ao caminho rápido já usado para Acessibilidade/App Info: interceptar o mais cedo possível, impedir reentrada repetida e retirar Configurações do primeiro plano/tarefa protegida.

## Checklist

- [x] Não ignorar cliques reais durante a janela interna de reset de Configurações.
- [x] Fechar a janela interna de reset assim que a tela de credencial segura estiver desenhada.
- [x] Detectar a classe de Device Admin em eventos de transição antes de qualquer leitura da árvore.
- [x] Usar os localizadores curtos de Device Admin no clique ambíguo antes do fallback amplo de raiz.
- [x] Em qualquer detecção confirmada de Device Admin, manter cortina e forçar HOME/launcher, além de limpar a tarefa protegida via MasterRemovalActivity.
- [x] Cobrir reentrada e o rótulo atual da One UI com testes unitários.
- [x] Remover toda a infraestrutura temporária usada para aplicar o patch e restaurar o workflow Android CI original.
- [ ] Executar Unit Tests, Lint, APK/AAB e validação de assinatura no head final antes da integração.

## Estado

Implementação concluída. Validação automatizada final pendente no head limpo da branch.
