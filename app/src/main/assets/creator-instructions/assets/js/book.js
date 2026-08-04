/* ==================================================================
 * Instruções do Criador — mapa e leitor
 * ==================================================================
 * Um script só, usado pelas duas telas. Ele decide o que fazer
 * olhando o que existe na página:
 *   [data-trail]    → desenha o caminho
 *   [data-chapter]  → monta o rodapé de conclusão do capítulo
 *
 * Você normalmente não precisa mexer aqui. Para acrescentar
 * capítulos, edite apenas chapters.js.
 * ================================================================== */

(() => {
  'use strict';

  const book = window.CREATOR_BOOK;
  if (!book) return;

  const SVG_NS = 'http://www.w3.org/2000/svg';
  const DONE_KEY = 'creator-instructions-done';
  const chapters = book.chapters || [];
  const written = chapters.filter(c => c.file);

  /* ---- Estado ---------------------------------------------------- */

  function readDone() {
    try {
      const raw = JSON.parse(localStorage.getItem(DONE_KEY) || '[]');
      return new Set(Array.isArray(raw) ? raw : []);
    } catch (_) {
      return new Set();
    }
  }

  let done = readDone();
  const saveDone = () => localStorage.setItem(DONE_KEY, JSON.stringify([...done]));
  const isDone = id => done.has(id);

  /** Primeiro capítulo escrito e ainda não concluído. */
  const currentChapter = () => written.find(c => !isDone(c.id)) || null;

  function stateOf(chapter) {
    if (!chapter.file) return 'locked';
    if (isDone(chapter.id)) return 'done';
    return chapter === currentChapter() ? 'current' : 'open';
  }

  /* ---- Mapa: o caminho ------------------------------------------- */

  const cssPx = name =>
    parseFloat(getComputedStyle(document.documentElement).getPropertyValue(name)) || 0;

  function layout(trail) {
    const width = trail.clientWidth;
    const row = cssPx('--row') || 132;
    const half = Math.min(width * 0.26, Math.max(0, (width - 160) / 2));

    return chapters.map((chapter, i) => ({
      chapter,
      i,
      x: width / 2 + half * Math.sin(i * Math.PI / 2),
      y: row / 2 + i * row
    }));
  }

  function drawPath(svg, points) {
    svg.replaceChildren();
    const row = cssPx('--row') || 132;
    const pull = row * 0.42;

    points.slice(0, -1).forEach((from, i) => {
      const to = points[i + 1];
      const seg = document.createElementNS(SVG_NS, 'path');
      seg.setAttribute('d',
        `M ${from.x} ${from.y} C ${from.x} ${from.y + pull}, ` +
        `${to.x} ${to.y - pull}, ${to.x} ${to.y}`);
      /* O trecho acende quando o nó de destino já foi concluído. */
      seg.setAttribute('class', 'trail-seg ' + (isDone(to.chapter.id) ? 'lit' : 'dim'));
      svg.append(seg);
    });
  }

  function nodeElement(point) {
    const { chapter, i } = point;
    const state = stateOf(chapter);

    const node = document.createElement('div');
    node.className = `node is-${state}`;
    node.dataset.chapterId = chapter.id;

    const dot = document.createElement('span');
    dot.className = 'node-dot';
    dot.setAttribute('aria-hidden', 'true');
    dot.textContent = state === 'done' ? '✓' : String(i + 1);

    const title = document.createElement('span');
    title.className = 'node-title';
    title.textContent = chapter.title;

    const parts = [dot, title];

    if (chapter.summary) {
      const summary = document.createElement('span');
      summary.className = 'node-summary';
      summary.textContent = chapter.summary;
      parts.push(summary);
    }

    if (state === 'current' || state === 'locked') {
      const badge = document.createElement('span');
      badge.className = 'node-badge';
      badge.textContent = state === 'current' ? 'você está aqui' : 'em breve';
      parts.push(badge);
    }

    if (chapter.file) {
      const link = document.createElement('a');
      link.href = chapter.file;
      link.setAttribute('aria-label',
        `Capítulo ${i + 1}: ${chapter.title}` +
        (state === 'done' ? ' (concluído)' : ''));
      link.append(...parts);
      node.append(link);
    } else {
      node.setAttribute('aria-label', `${chapter.title} — ainda não escrito`);
      node.append(...parts);
    }

    return node;
  }

  function renderTrail() {
    const trail = document.querySelector('[data-trail]');
    if (!trail) return;

    const row = cssPx('--row') || 132;
    trail.style.height = `${chapters.length * row}px`;

    let svg = trail.querySelector('.trail-svg');
    if (!svg) {
      svg = document.createElementNS(SVG_NS, 'svg');
      svg.setAttribute('class', 'trail-svg');
      svg.setAttribute('aria-hidden', 'true');
      trail.append(svg);
    }

    trail.querySelectorAll('.node').forEach(n => n.remove());

    const points = layout(trail);
    drawPath(svg, points);
    points.forEach(point => {
      const node = nodeElement(point);
      node.style.left = `${point.x}px`;
      node.style.top = `${point.y - cssPx('--node') / 2}px`;
      trail.append(node);
    });

    renderProgress();
  }

  function renderProgress() {
    const count = written.filter(c => isDone(c.id)).length;
    const total = written.length;

    const label = document.querySelector('[data-progress-label]');
    if (label) {
      label.innerHTML = `<span><strong>${count}</strong> de ${total} ` +
        `${total === 1 ? 'capítulo escrito' : 'capítulos escritos'}</span>` +
        `<span>${total ? Math.round((count / total) * 100) : 0}%</span>`;
    }

    const bar = document.querySelector('[data-progress-bar]');
    if (bar) bar.style.width = `${total ? (count / total) * 100 : 0}%`;

    const end = document.querySelector('[data-trail-end]');
    if (end) {
      const pending = chapters.length - total;
      end.textContent = pending > 0
        ? `Mais ${pending} ${pending === 1 ? 'etapa está' : 'etapas estão'} a caminho. ` +
          'O caminho cresce conforme o livro é escrito.'
        : 'Você chegou ao fim do caminho — por enquanto.';
    }
  }

  /* ---- Página de capítulo ---------------------------------------- */

  function renderChapter() {
    const host = document.querySelector('[data-chapter]');
    if (!host) return;

    const id = host.dataset.chapter;
    const index = chapters.findIndex(c => c.id === id);
    const chapter = chapters[index];
    if (!chapter) return;

    /* Cabeçalho: posição e título vêm do manifesto, não do HTML,
       para nunca ficarem fora de sincronia com o mapa. */
    const eyebrow = document.querySelector('[data-chapter-eyebrow]');
    if (eyebrow) eyebrow.textContent = `Capítulo ${index + 1}`;

    const heading = document.querySelector('[data-chapter-title]');
    if (heading) heading.textContent = chapter.title;

    const step = document.querySelector('[data-chapter-step]');
    if (step) step.textContent = `${index + 1} de ${chapters.length}`;

    const following = chapters.slice(index + 1).find(c => c.file) || null;
    const box = document.querySelector('[data-chapter-done]');
    if (!box) return;

    function paint() {
      const complete = isDone(id);
      box.className = `chapter-done${complete ? ' is-done' : ''}`;
      box.replaceChildren();

      const title = document.createElement('p');
      title.className = 'chapter-done-title';
      title.textContent = complete ? 'Etapa concluída.' : 'Terminou este capítulo?';

      const hint = document.createElement('p');
      hint.className = 'chapter-done-hint';
      hint.textContent = complete
        ? (following ? `A seguir: ${following.title}` : 'É a última etapa escrita até agora.')
        : 'Marque como concluída para acender este trecho do caminho.';

      const actions = document.createElement('div');
      actions.className = 'chapter-actions';

      const primary = document.createElement('button');
      primary.type = 'button';
      primary.className = 'primary-button';
      primary.textContent = complete
        ? (following ? 'Ir para a próxima etapa' : 'Voltar ao caminho')
        : (following ? 'Concluir e continuar' : 'Concluir etapa');

      primary.addEventListener('click', () => {
        if (!complete) { done.add(id); saveDone(); }
        window.location.href = (complete && following) ? following.file : 'index.html';
      });

      const toggle = document.createElement('button');
      toggle.type = 'button';
      toggle.className = 'ghost-button';
      toggle.textContent = complete ? 'Desmarcar' : 'Só marcar como concluída';
      toggle.addEventListener('click', () => {
        if (complete) done.delete(id); else done.add(id);
        saveDone();
        paint();
      });

      actions.append(primary, toggle);
      box.append(title, hint, actions);
    }

    paint();
  }

  /* ---- Zerar progresso ------------------------------------------- */

  document.querySelector('[data-reset]')?.addEventListener('click', () => {
    if (!window.confirm('Zerar seu progresso no caminho? O texto dos capítulos continua intacto.')) return;
    done = new Set();
    saveDone();
    renderTrail();
  });

  /* ---- Rolagem lembrada ------------------------------------------ */

  const scrollKey = `creator-scroll:${document.body.dataset.page || location.pathname}`;
  window.addEventListener('load', () => {
    const saved = Number(localStorage.getItem(scrollKey) || 0);
    if (saved > 0) window.scrollTo(0, saved);
  }, { once: true });
  window.addEventListener('pagehide', () => {
    localStorage.setItem(scrollKey, String(window.scrollY));
  });

  /* ---- Início ---------------------------------------------------- */

  renderTrail();
  renderChapter();

  let resizeTimer;
  window.addEventListener('resize', () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(renderTrail, 120);
  });
})();
