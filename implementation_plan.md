# Plano de implementação — simplificação e ordem das Configurações

## Objetivo
Simplificar a tela **Configurações** do HardBlock, remover opções que não devem mais ser exibidas e garantir que as três últimas ações permaneçam, nesta ordem: **Senha mestre**, **Remover todos os bloqueios** e **Meu Instagram**.

## Diagnóstico
- [x] Confirmar que as opções solicitadas estão concentradas em `SettingsScreen.kt`.
- [x] Confirmar a ordem atual: Senha mestre → Remover todos os bloqueios → Personalizar bloqueio → Limites e segurança → Manutenção do Device Owner → Proteção nuclear → Desinstalar aplicativo → Meu Instagram.
- [x] Confirmar que Manutenção do Device Owner e Proteção nuclear mantêm estado e diálogos próprios dentro da tela de Configurações.
- [x] Confirmar que Personalizar bloqueio e Limites e segurança são rotas de navegação disparadas por callbacks da tela; removê-las da lista não exige apagar as implementações internas.
- [x] Preservar Meu Instagram como a última entrada.

## Ordem final planejada
1. Perfil.
2. Idioma.
3. Opções de privacidade de anúncios, somente quando exigidas pelo consentimento.
4. Desinstalar aplicativo.
5. Senha mestre.
6. Remover todos os bloqueios.
7. Meu Instagram.

## Remoções da tela
- [ ] Personalizar bloqueio.
- [ ] Limites e segurança.
- [ ] Manutenção do Device Owner.
- [ ] Proteção nuclear.

## Implementação
- [ ] Remover os quatro cards da composição de `SettingsScreen`.
- [ ] Remover estado, cálculos, diálogos e imports que existiam apenas para Manutenção do Device Owner e Proteção nuclear.
- [ ] Reordenar Desinstalar aplicativo, Senha mestre e Remover todos os bloqueios para que fiquem imediatamente antes de Meu Instagram.
- [ ] Preservar as Activities/rotas e lógica interna não solicitadas, evitando regressões fora da tela de Configurações.

## Validação
- [ ] Revisar o arquivo final para confirmar que não existe referência visual às quatro opções removidas.
- [ ] Confirmar que Meu Instagram continua sendo o último item clicável.
- [ ] Confirmar que Remover todos os bloqueios é o penúltimo item clicável.
- [ ] Confirmar que Senha mestre é o antepenúltimo item clicável.
- [ ] Verificar compilação/CI disponível para a branch.

## Critério de conclusão
A tela Configurações deve mostrar somente as opções mantidas, sem os quatro cards removidos, e terminar exatamente com **Senha mestre → Remover todos os bloqueios → Meu Instagram**, sem alterar a lógica de bloqueio, Device Owner ou as implementações internas que deixaram de ter entrada nessa tela.
