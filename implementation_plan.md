# Plano de implementação — simplificação e ordem das Configurações

## Objetivo
Simplificar a tela **Configurações** do HardBlock removendo somente as opções solicitadas e garantindo que as três últimas ações permaneçam, nesta ordem: **Senha mestre**, **Remover todos os bloqueios** e **Meu Instagram**.

## Diagnóstico
- [x] Confirmar que as opções solicitadas estão concentradas em `SettingsScreen.kt`.
- [x] Confirmar a ordem original: Senha mestre → Remover todos os bloqueios → Personalizar bloqueio → Limites e segurança → Manutenção do Device Owner → Proteção nuclear → Desinstalar aplicativo → Meu Instagram.
- [x] Confirmar que Manutenção do Device Owner e Proteção nuclear mantêm estado e diálogos próprios dentro da tela de Configurações.
- [x] Confirmar que Personalizar bloqueio e Limites e segurança são rotas de navegação disparadas por callbacks da tela; removê-las da lista não exige apagar as implementações internas.
- [x] Confirmar que a opção de privacidade de anúncios já existia no `main`, aparece somente quando `AdsConsentManager.isPrivacyOptionsRequired(...)` retorna verdadeiro e não faz parte desta alteração.
- [x] Preservar Meu Instagram como a última entrada.

## Ordem final da tela
1. Perfil.
2. Idioma.
3. Opções de privacidade de anúncios — somente quando o consentimento exigir; comportamento original preservado sem alteração.
4. Senha mestre.
5. Remover todos os bloqueios.
6. Meu Instagram.

## Remoções da tela
- [x] Personalizar bloqueio.
- [x] Limites e segurança.
- [x] Manutenção do Device Owner.
- [x] Proteção nuclear.
- [x] Desinstalar aplicativo.

## Implementação
- [x] Remover somente os cinco cards solicitados da composição de `SettingsScreen`.
- [x] Remover estado, cálculos, diálogos e imports que existiam apenas para Manutenção do Device Owner e Proteção nuclear.
- [x] Manter Senha mestre e Remover todos os bloqueios na seção original de bloqueio, sem movê-los para outra categoria.
- [x] Preservar sem alteração o fluxo condicional de privacidade de anúncios.
- [x] Preservar as Activities/rotas e lógica interna não solicitadas, evitando regressões fora da tela de Configurações.

## Validação
- [x] Revisar o arquivo final para confirmar que não existe referência visual às cinco opções removidas.
- [x] Confirmar que Meu Instagram continua sendo o último item clicável.
- [x] Confirmar que Remover todos os bloqueios é o penúltimo item clicável.
- [x] Confirmar que Senha mestre é o antepenúltimo item clicável.
- [ ] Confirmar o resultado do CI da branch.

## Critério de conclusão
A tela Configurações deve remover apenas **Personalizar bloqueio**, **Limites e segurança**, **Manutenção do Device Owner**, **Proteção nuclear** e **Desinstalar aplicativo**; manter intactas as demais opções existentes; e terminar exatamente com **Senha mestre → Remover todos os bloqueios → Meu Instagram**, sem alterar a lógica de bloqueio, Device Owner, consentimento de anúncios ou outras implementações internas.
