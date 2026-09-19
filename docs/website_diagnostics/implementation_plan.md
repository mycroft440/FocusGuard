# Plano de implementação — diagnóstico automático do bloqueio de sites

## Objetivo

Criar um subsistema separado do logger geral do FocusGuard para responder, com evidência de runtime, **em qual etapa o bloqueio de sites falhou**.

## Regras

- Não alterar as decisões de bloqueio, reconhecimento de navegador, correspondência de regras ou redirecionamento.
- Não usar o relatório/log atual como armazenamento; os arquivos ficam em diretório próprio.
- Toda gravação em disco ocorre fora da thread principal e nenhuma falha do diagnóstico pode afetar a proteção.
- Não registrar query string, fragmento ou caminho da URL; o alvo persistido é reduzido à origem/domínio.
- Limitar retenção a 14 dias e no máximo 60 relatórios para impedir crescimento indefinido.
- Diferenciar falha comprovada de resultado inconclusivo; ausência de evidência nunca vira causa inventada.

## Pontos observados

1. Resultado de identificação de URL/barra de endereço e recovery esgotado.
2. Detecção de candidato que corresponde a regra bloqueada.
3. Criação da transação e apresentação da cortina.
4. Preparação da barra de endereço na mesma aba.
5. Submissão aceita do destino seguro.
6. Evidência de evento de navegação após a submissão.
7. Confirmação do destino seguro.
8. Rebind certificado de janela quando o navegador recria a superfície de acessibilidade.
9. Solicitação/confirmação do destino rigoroso.
10. Encerramento da transação sem resultado terminal comprovado.

## Saídas

- Diretório independente: `filesDir/WebsiteBlockingDiagnostics/`.
- Um TXT por falha ou resultado inconclusivo relevante: `website_block_failure_<data>_<navegador>_t<id>.txt`.
- Nenhum arquivo é criado para uma transação concluída com redirecionamento confirmado.
- O relatório inclui navegador/versão, Android, janela/transação, regra, etapa, número de submissões aceitas e evidências temporais disponíveis.
- O alvo é sanitizado para não persistir query string, fragmento, caminho, conteúdo de página, senha ou texto digitado em formulário.

## Modo Dev

- O item existente “Modo Dev” abre um menu interno de ferramentas.
- A nova opção **“Diagnóstico de bloqueio de sites”** lista todos os TXT do diretório dedicado, mais recentes primeiro.
- Tocar em um relatório abre seu conteúdo dentro do app com texto selecionável.
- As ferramentas de manutenção/ANR existentes continuam disponíveis em uma opção separada do mesmo Modo Dev.

## Validação

- Testes puros para sanitização e classificação da etapa de falha.
- Testes existentes do coordinator devem continuar inalterados, pois o diagnóstico não muda seu contrato.
- Verificar que falha de I/O no gravador não propaga exceção para o serviço.
- Confirmar que URL com caminho/query/fragmento não aparece nos relatórios.
- Confirmar que uma transação bem-sucedida não gera TXT e que falhas são listadas em ordem decrescente de modificação.
