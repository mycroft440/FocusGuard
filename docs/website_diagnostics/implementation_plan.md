# Plano de implementação — diagnóstico automático do bloqueio de sites

## Objetivo

Criar um subsistema separado do logger geral do FocusGuard para responder, com evidência de runtime, **em qual etapa o bloqueio de sites falhou**.

## Regras

- Não alterar as decisões de bloqueio, reconhecimento de navegador, correspondência de regras ou redirecionamento.
- Não usar o relatório/log atual como armazenamento; os arquivos ficam em diretório próprio.
- Toda gravação em disco ocorre fora da thread principal e nenhuma falha do diagnóstico pode afetar a proteção.
- O diagnóstico usa o matcher de regras sem efeitos colaterais (`findMatchingRulesIgnoringGrants`) para não alterar o ciclo de concessões PASSWORD.
- Não registrar query string, fragmento ou caminho da URL; o alvo persistido é reduzido à origem/domínio.
- Se o alvo não puder ser convertido com segurança em uma origem comprovada, ele não é persistido.
- Limitar retenção a 14 dias e no máximo 60 relatórios para impedir crescimento indefinido.
- Diferenciar falha comprovada de resultado inconclusivo; ausência de evidência nunca vira causa inventada.
- Serializar leitura, gravação, retenção e limpeza dos TXT para evitar leitura parcial ou corrida entre exclusão e criação.
- A limpeza avança uma geração de armazenamento: gravações assíncronas agendadas antes de “Limpar relatórios” não podem reaparecer depois da limpeza; falhas novas continuam sendo registradas normalmente.

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

A classificação diferencia explicitamente **envio aceito sem qualquer evidência de navegação posterior** de **navegação observada sem confirmação do destino seguro**. Isso torna o relatório mais útil para distinguir falha de submissão/execução no navegador de falha de confirmação da nova superfície.

## Saídas

- Diretório independente: `filesDir/WebsiteBlockingDiagnostics/`.
- Um TXT por falha ou resultado inconclusivo relevante: `website_block_failure_<data>_<navegador>_t<id>_n<sequência>.txt`.
- O sufixo de sequência evita colisão quando dois relatórios são criados no mesmo milissegundo.
- Nenhum arquivo é criado para uma transação concluída com redirecionamento confirmado.
- O relatório inclui navegador/versão, Android, janela/transação, regra, etapa, número de submissões aceitas e evidências temporais disponíveis.
- O relatório explicita se houve evidência de navegação e se houve transição de janela.
- O alvo é sanitizado para não persistir query string, fragmento, caminho, conteúdo de página, senha ou texto digitado em formulário.
- A tabela de deduplicação de falhas de identificação é podada para não crescer indefinidamente em sessões longas.

## Modo Dev

- O item existente “Modo Dev” abre um menu interno de ferramentas.
- A opção **“Diagnóstico de bloqueio de sites”** lista todos os TXT do diretório dedicado, mais recentes primeiro.
- A tela também mostra o espaço total atualmente ocupado pelos TXT.
- Tocar em um relatório abre seu conteúdo dentro do app com texto selecionável.
- A opção **“Limpar relatórios”** remove somente os TXT desse subsistema, após confirmação explícita; ela não altera bloqueios ativos nem configurações.
- Relatórios de falhas antigas que ainda estavam apenas enfileirados para I/O também são invalidados pela limpeza e não reaparecem depois.
- Após a limpeza, a tela informa quantos arquivos foram removidos e se algum arquivo não pôde ser excluído.
- As ferramentas de manutenção/ANR existentes continuam disponíveis em uma opção separada do mesmo Modo Dev.

## Validação

- Testes puros para sanitização e classificação da etapa de falha.
- Testes distinguem envio aceito sem navegação de navegação observada sem confirmação do destino.
- Testes existentes do coordinator devem continuar inalterados, pois o diagnóstico não muda seu contrato.
- Verificar que falha de I/O no gravador não propaga exceção para o serviço.
- Confirmar que URL com caminho/query/fragmento não aparece nos relatórios.
- Confirmar que texto que não forma uma origem válida não é persistido como alvo.
- Confirmar que uma transação bem-sucedida não gera TXT e que falhas são listadas em ordem decrescente de modificação.
- Confirmar que “Limpar relatórios” apaga apenas arquivos `website_block_failure_*.txt` do diretório dedicado.
- Confirmar que uma gravação agendada antes da limpeza não recria um TXT após a operação terminar.
