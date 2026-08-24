# Protected power menu — HardBlock visual redesign

## Objetivo

Aplicar ao menu de energia protegido a composição visual fornecida como referência pelo usuário, preservando a lógica e a velocidade do overlay e usando exclusivamente a paleta visual já existente do HardBlock.

## Implementado

- [x] Sheet inferior com cantos superiores arredondados e grip.
- [x] Badge `PROTEÇÃO ATIVA` usando o ciano do HardBlock.
- [x] Título e subtítulo simplificados.
- [x] Cartões separados para Desligar e Reiniciar.
- [x] Seção visual de Emergência com vermelho semântico do HardBlock.
- [x] Cartões para Chamada de emergência e Informações médicas.
- [x] Ícones vetoriais nativos derivados dos SVGs da referência, evitando variação de glifos entre OEMs.
- [x] Botão Cancelar discreto no rodapé.
- [x] Estados pressionados nos cartões sem animações que atrasem a exibição.
- [x] Safe-area inferior baseada em `WindowInsets`, incluindo Android 15/16 edge-to-edge.
- [x] Paleta alinhada com `FocusGuardTheme.kt`: fundo #0D0D0D, superfície #161616, cartão #1C1C1E, cartão pressionado #252528, ciano #00BCD4, vermelho #E53935, texto #FAFAFA/#B0B0B0, borda #303035.
- [x] Mantido `PixelFormat.OPAQUE`, pré-anexação do overlay e forwarding somente de `ACTION_CLICK` para não reintroduzir gesto nativo de Modo Seguro.
- [x] Strings atualizadas em português e inglês.

## Validação

Pendente: Android CI completo no head final limpo da branch antes do merge.
