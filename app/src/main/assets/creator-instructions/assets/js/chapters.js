/* ==================================================================
 * Instruções do Criador — manifesto do caminho
 * ==================================================================
 *
 * ESTE É O ÚNICO ARQUIVO QUE VOCÊ PRECISA EDITAR PARA ADICIONAR
 * UM CAPÍTULO NOVO. A trilha do mapa se redesenha sozinha.
 *
 * Cada capítulo:
 *   id       identificador curto, sem espaço nem acento (usado no
 *            salvamento do progresso — não mude depois de publicar)
 *   title    o que aparece no mapa
 *   summary  uma linha curta abaixo do título (opcional)
 *   file     o arquivo HTML do capítulo.
 *            SEM file = capítulo ainda não escrito: aparece apagado
 *            no mapa, com o selo "em breve", e não é clicável.
 *
 * Para escrever um capítulo novo:
 *   1. copie _modelo.html para, por exemplo, capitulo-03.html
 *   2. escreva o conteúdo dentro de <div class="prose">
 *   3. acrescente "file: 'capitulo-03.html'" na entrada aqui embaixo
 *
 * A ordem do array é a ordem do caminho, de cima para baixo.
 * ================================================================== */

window.CREATOR_BOOK = {
  title: 'Instruções do Criador',
  subtitle: 'Tudo que existe para sair da pornografia começa aqui.',

  chapters: [
    {
      id: 'apresentacao',
      title: 'Apresentação',
      summary: 'Por onde este caminho começa.',
      file: 'capitulo-01-apresentacao.html'
    },

    /* ------------------------------------------------------------ *
     * Daqui para baixo estão nós ainda não escritos. São exemplos:
     * renomeie, reordene, apague ou acrescente à vontade. Enquanto
     * não tiverem "file", aparecem no mapa como "em breve".
     * ------------------------------------------------------------ */
    {
      id: 'capitulo-02',
      title: 'Capítulo dois',
      summary: 'Renomeie esta etapa quando escrever.'
    },
    {
      id: 'capitulo-03',
      title: 'Capítulo três',
      summary: 'Renomeie esta etapa quando escrever.'
    },
    {
      id: 'capitulo-04',
      title: 'Capítulo quatro',
      summary: 'Renomeie esta etapa quando escrever.'
    },
    {
      id: 'capitulo-05',
      title: 'Capítulo cinco',
      summary: 'Renomeie esta etapa quando escrever.'
    }
  ]
};
