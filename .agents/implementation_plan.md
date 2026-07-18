# Plano de implementação — fluxo unificado de proteção

## Objetivo

Substituir os três atalhos da tela Proteção por uma única entrada e mover a escolha do modo para depois da criação da lista de apps e sites.

## Jornada

1. A tela Proteção mostra somente o cartão “Bloquear, limitar e proteger apps e sites”.
2. O usuário monta uma lista comum, adicionando aplicativos e regras de site/palavra-chave.
3. A escolha do modo só é liberada quando a lista contém ao menos um item.
4. O usuário escolhe entre:
   - limite diário de uso;
   - bloqueio revogável com senha;
   - jejum de dopamina, irrevogável até o fim do período escolhido.
5. A configuração selecionada reutiliza os mecanismos de bloqueio já existentes.

## Validação

- Testar que uma lista vazia não expõe modos de proteção.
- Testar que os três modos ficam disponíveis após adicionar app ou site.
- Testar a conversão e os limites da duração diária.
- Executar testes unitários, lint e compilação do APK.
