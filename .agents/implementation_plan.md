# Plano de implementação — personagens do perfil e contato com o criador

## Objetivos

1. Substituir os ícones de avatar por cinco personagens completos e originais.
2. Manter os personagens legíveis no cabeçalho, em Configurações e na edição.
3. Adicionar abaixo dos bloqueios um atalho destacado para o Instagram do
   criador, com fallback para o navegador.

## Regras do perfil

- Os personagens usam WebP transparente em 512 px e identificadores já
  persistidos, portanto perfis existentes migram sem perder a seleção.
- O seletor continua funcionando como grupo de opções para leitores de tela.
- O card do Instagram tenta abrir `com.instagram.android`; quando indisponível,
  abre a mesma URL no navegador sem derrubar o aplicativo.
- O contato é informativo e não interfere nos três tipos de proteção.

## Validação

- Validar transparência, dimensões e peso dos cinco assets.
- Testar a compilação dos drawables e o destino do atalho externo.
- Conferir paridade dos recursos em português e inglês.
- Executar testes unitários, lint e compilação dos APKs.
