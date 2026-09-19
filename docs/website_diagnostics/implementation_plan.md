# Plano de implementação — diagnóstico automático do bloqueio de sites

## Objetivo

Criar um subsistema separado do logger geral do FocusGuard para responder, com evidência de runtime, **em qual etapa o bloqueio de sites falhou**.

## Regras

- Não alterar as decisões de bloqueio, reconhecimento de navegador, correspondência de regras ou redirecionamento.
- Não usar o relatório/log atual como armazenamento; os arquivos ficam em diretório próprio.
- Toda gravação em disco ocorre fora da thread principal e nenhuma falha do diagnóstico pode afetar a proteção.
- Não registrar query string nem fragmento de URL; endereços são sanitizados e limitados.
- Limitar retenção e tamanho para impedir crescimento indefinido.
- Diferenciar `falhou`, `abortado` e `não confirmado`; ausência de evidência não vira sucesso nem causa inventada.

## Pontos observados

1. Inspeção do navegador e obtenção do root/janela.
2. Resultado de identificação de URL/barra de endereço.
3. Detecção de candidato que corresponde a regra bloqueada.
4. Criação da transação e apresentação da cortina.
5. Preparação da barra de endereço na mesma aba.
6. Submissão do destino seguro.
7. Confirmação do destino seguro.
8. Restauração/retry.
9. Resultado terminal: redirecionado, destino estrito, fail-closed ou abortado.
10. Recovery esgotado/URL não observável.

## Saídas

- `filesDir/WebsiteBlockingDiagnostics/events_YYYY-MM-DD.jsonl`: eventos estruturados para correlação.
- `filesDir/WebsiteBlockingDiagnostics/failures_YYYY-MM-DD.txt`: relatório humano somente de falhas/inconclusivos relevantes.

Cada falha terminal inclui navegador/versão, API Android, janela/transação, etapa que falhou, tentativas, evidências disponíveis e uma hipótese **derivada do estágio observado**, nunca uma causa afirmada sem prova.

## Validação

- Testes puros para sanitização/classificação e montagem do diagnóstico.
- Testes do coordinator existentes devem continuar inalterados, pois o diagnóstico não muda seu contrato.
- Verificar que falha de I/O no gravador não propaga exceção para o serviço.
- Confirmar que URL com query/fragmento não aparece nos relatórios.
