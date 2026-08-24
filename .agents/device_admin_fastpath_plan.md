# Device Admin fast-path fix

## Objetivo

Igualar o bloqueio de Device Admin ao caminho rápido já usado para Acessibilidade/App Info: interceptar o mais cedo possível, impedir reentrada repetida e retirar Configurações do primeiro plano/tarefa protegida.

## Checklist

- [ ] Não ignorar cliques reais durante a janela interna de reset de Configurações.
- [ ] Fechar a janela interna de reset assim que a tela de credencial segura estiver desenhada.
- [ ] Detectar a classe de Device Admin em eventos de transição antes de qualquer leitura da árvore.
- [ ] Usar os localizadores curtos de Device Admin no clique ambíguo antes do fallback amplo de raiz.
- [ ] Em qualquer detecção confirmada de Device Admin, manter cortina e forçar HOME/launcher, além de limpar a tarefa protegida via MasterRemovalActivity.
- [ ] Cobrir reentrada e regressões com testes.
- [ ] Executar Unit Tests, Lint, APK/AAB e validação de assinatura no CI antes da integração.
