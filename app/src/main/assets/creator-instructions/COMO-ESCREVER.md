# Como adicionar um capítulo

O caminho no mapa se desenha sozinho a partir de `assets/js/chapters.js`.
Você nunca precisa mexer no `book.js` nem no CSS.

## Adicionar um capítulo novo

**1. Copie o modelo**

```
cp _modelo.html capitulo-02.html
```

**2. No arquivo novo, troque o `data-chapter`**

```html
<main data-chapter="capitulo-02">
```

Esse valor tem que bater com o `id` no manifesto. Também atualize o
`data-page` no `<body>` para o nome do arquivo — é o que salva a
posição de rolagem.

**3. Escreva dentro de `<div class="prose">`**

Só HTML simples: `<p>`, `<h2>`, `<h3>`, `<ul>`, `<strong>`,
`<blockquote>`. O estilo já está pronto para todos.

Não escreva o título do capítulo aqui — ele vem do manifesto.

**4. Ligue o capítulo ao caminho**

Em `assets/js/chapters.js`, na entrada correspondente, acrescente a
linha `file`:

```js
{
  id: 'capitulo-02',
  title: 'O nome de verdade do capítulo',
  summary: 'Uma linha curta que aparece no mapa.',
  file: 'capitulo-02.html'
}
```

Pronto. O nó acende no mapa, deixa de ser "em breve" e vira clicável.

## Regras que valem a pena saber

**Sem `file`, o nó fica bloqueado.** É assim que capítulos planejados
mas ainda não escritos aparecem: apagados, com o selo "em breve", sem
link. Você pode deixar o caminho inteiro planejado desde já e ir
acendendo um nó por vez.

**A ordem do array é a ordem do caminho.** De cima para baixo. Para
reordenar, mova as entradas.

**Nunca mude um `id` já publicado.** O progresso de quem já leu está
salvo por `id`. Mudar o `id` faz a pessoa perder a marcação daquele
capítulo. Título e resumo você pode mudar à vontade.

**Nada de internet.** Tudo roda offline dentro do app. Não use links
externos, fontes do Google, nem imagens hospedadas fora. Imagens vão
em `assets/images/` e entram como `<img src="assets/images/nome.png" alt="...">`.

## Onde fica cada coisa

```
creator-instructions/
├── index.html                     o mapa com o caminho
├── _modelo.html                   copie para criar capítulos
├── capitulo-01-apresentacao.html  primeiro capítulo
├── COMO-ESCREVER.md               este arquivo
└── assets/
    ├── css/book.css               visual do mapa e dos capítulos
    └── js/
        ├── chapters.js            ← o único que você edita
        └── book.js                desenha o caminho e o progresso
```

## Progresso do leitor

Fica no `localStorage` do WebView, na chave `creator-instructions-done`
(uma lista de `id`s). O botão "Zerar meu progresso", no fim do mapa,
limpa tudo. O texto dos capítulos nunca é afetado.
