(() => {
  'use strict';

  const storage = window.localStorage;
  const pagePrefix = 'easypeasy-editor-page:';
  const customKey = 'easypeasy-editor-custom-chapters';
  const deletedKey = 'easypeasy-editor-deleted-chapters';
  const customId = new URLSearchParams(location.search).get('custom');
  const article = () => document.querySelector('.book-content');
  let panel;
  let editing = false;

  const read = (key, fallback) => {
    try { return JSON.parse(storage.getItem(key) || 'null') ?? fallback; }
    catch (_) { return fallback; }
  };
  const write = (key, value) => storage.setItem(key, JSON.stringify(value));
  const cleanPage = href => String(href || '').split('#')[0].split('?')[0] || 'index.html';
  const pageKey = () => customId ? `custom:${customId}` : cleanPage(document.body.dataset.page || location.pathname.split('/').pop());
  const pageState = page => read(`${pagePrefix}${page}`, null);
  const customChapters = () => read(customKey, []);
  const deletedPages = () => read(deletedKey, []);
  const customHref = id => `index.html?custom=${encodeURIComponent(id)}`;
  const escapeHtml = value => String(value).replace(/[&<>"']/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
  const titleOf = (node, fallback = 'Novo capítulo') => node?.querySelector('h1')?.textContent?.trim() || fallback;

  function toast(message) {
    let node = document.querySelector('.book-editor-toast');
    if (!node) {
      node = document.createElement('div');
      node.className = 'book-editor-toast';
      document.body.appendChild(node);
    }
    node.textContent = message;
    node.classList.add('visible');
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => node.classList.remove('visible'), 2200);
  }

  function applySavedContent() {
    const target = article();
    if (!target) return;
    if (customId) {
      const chapter = customChapters().find(item => item.id === customId);
      if (!chapter) return;
      target.innerHTML = chapter.html;
      document.body.dataset.page = `custom:${customId}`;
      document.title = `${chapter.title} | EasyPeasy`;
      return;
    }
    const state = pageState(pageKey());
    if (state?.html) {
      target.innerHTML = state.html;
      if (state.title) document.title = `${state.title} | EasyPeasy`;
    }
  }

  function rebuildSummary() {
    const summary = document.querySelector('.summary');
    if (!summary) return;
    summary.querySelectorAll('[data-custom-chapter="true"]').forEach(node => node.remove());
    const deleted = new Set(deletedPages());

    summary.querySelectorAll('li').forEach(item => {
      const link = item.querySelector('a[href]');
      if (!link) return;
      const page = cleanPage(link.getAttribute('href'));
      item.style.display = deleted.has(page) ? 'none' : '';
      const state = pageState(page);
      if (state?.title) {
        item.dataset.title = state.title;
        const number = link.querySelector('.chapter-number');
        link.replaceChildren(...(number ? [number] : []), document.createTextNode(state.title));
      }
      link.classList.toggle('active', !customId && page === pageKey());
    });

    customChapters().forEach((chapter, index) => {
      const item = document.createElement('li');
      item.dataset.customChapter = 'true';
      item.dataset.title = chapter.title;
      const link = document.createElement('a');
      link.href = customHref(chapter.id);
      link.classList.toggle('active', customId === chapter.id);
      link.innerHTML = `<span class="chapter-number">+${index + 1}</span>${escapeHtml(chapter.title)}`;
      item.appendChild(link);
      summary.appendChild(item);
    });
  }

  function setEditing(enabled) {
    const target = article();
    if (!target) return;
    editing = enabled;
    target.contentEditable = enabled ? 'true' : 'false';
    target.spellcheck = true;
    target.classList.toggle('editor-active', enabled);
    const button = panel?.querySelector('[data-editor="edit"]');
    if (button) button.textContent = enabled ? 'Concluir edição' : 'Editar texto';
    if (enabled) target.focus({ preventScroll: true });
  }

  function saveChapter() {
    const target = article();
    if (!target) return;
    const title = titleOf(target);
    const html = target.innerHTML;
    if (customId) {
      const chapters = customChapters();
      const index = chapters.findIndex(item => item.id === customId);
      if (index >= 0) chapters[index] = { ...chapters[index], title, html, updatedAt: Date.now() };
      write(customKey, chapters);
    } else {
      write(`${pagePrefix}${pageKey()}`, { title, html, updatedAt: Date.now() });
    }
    rebuildSummary();
    toast('Alterações salvas neste aparelho.');
  }

  function newChapter() {
    const title = window.prompt('Nome do novo capítulo:', 'Meu novo capítulo')?.trim();
    if (!title) return;
    const id = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 7)}`;
    const chapters = customChapters();
    chapters.push({
      id,
      title,
      html: `<section class="normal"><div class="section level1"><h1>${escapeHtml(title)}</h1><p>Comece a escrever este capítulo.</p></div></section>`,
      createdAt: Date.now(),
      updatedAt: Date.now()
    });
    write(customKey, chapters);
    location.href = customHref(id);
  }

  function deleteChapter() {
    const title = titleOf(article(), 'este capítulo');
    if (!window.confirm(`Excluir “${title}” da sua versão editada?`)) return;
    if (customId) {
      write(customKey, customChapters().filter(item => item.id !== customId));
    } else {
      const deleted = new Set(deletedPages());
      deleted.add(pageKey());
      write(deletedKey, [...deleted]);
      storage.removeItem(`${pagePrefix}${pageKey()}`);
    }
    rebuildSummary();
    const next = [...document.querySelectorAll('.summary li')]
      .find(item => item.style.display !== 'none')
      ?.querySelector('a[href]')?.getAttribute('href');
    location.href = next || 'index.html';
  }

  function restoreChapter() {
    if (customId) return toast('Capítulos novos não possuem original.');
    storage.removeItem(`${pagePrefix}${pageKey()}`);
    write(deletedKey, deletedPages().filter(page => page !== pageKey()));
    location.reload();
  }

  function blobDataUrl(blob) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result);
      reader.onerror = reject;
      reader.readAsDataURL(blob);
    });
  }

  async function prepareContent(html) {
    const wrapper = document.createElement('div');
    wrapper.innerHTML = html;
    wrapper.querySelectorAll('script,iframe,object,embed,form').forEach(node => node.remove());
    for (const image of wrapper.querySelectorAll('img[src]')) {
      const src = image.getAttribute('src');
      if (!src || /^(data:|https?:|blob:)/i.test(src)) continue;
      try {
        const response = await fetch(src, { cache: 'no-store' });
        if (response.ok) image.src = await blobDataUrl(await response.blob());
      } catch (_) {}
    }
    return wrapper.innerHTML;
  }

  async function exportBook() {
    if (editing) saveChapter();
    const button = panel.querySelector('[data-editor="export"]');
    const oldLabel = button.textContent;
    button.disabled = true;
    button.textContent = 'Preparando…';

    try {
      rebuildSummary();
      const deleted = new Set(deletedPages());
      const chapters = [];
      const seen = new Set();
      const customs = customChapters();

      for (const item of document.querySelectorAll('.summary li')) {
        const link = item.querySelector('a[href]');
        if (!link || item.style.display === 'none') continue;
        const href = link.getAttribute('href');
        const match = href.match(/[?&]custom=([^&#]+)/);
        if (match) {
          const id = decodeURIComponent(match[1]);
          const key = `custom:${id}`;
          if (seen.has(key)) continue;
          const chapter = customs.find(entry => entry.id === id);
          if (!chapter) continue;
          seen.add(key);
          chapters.push({ title: chapter.title, html: await prepareContent(chapter.html) });
          continue;
        }

        const page = cleanPage(href);
        if (seen.has(page) || deleted.has(page)) continue;
        seen.add(page);
        const state = pageState(page);
        let html = state?.html;
        if (!html) {
          const response = await fetch(page, { cache: 'no-store' });
          if (!response.ok) throw new Error(`Falha ao carregar ${page}`);
          const doc = new DOMParser().parseFromString(await response.text(), 'text/html');
          html = doc.querySelector('.book-content')?.innerHTML || '';
        }
        chapters.push({
          title: state?.title || item.dataset.title || link.textContent.trim(),
          html: await prepareContent(html)
        });
      }

      const toc = chapters.map((chapter, index) => `<li><a href="#cap-${index + 1}">${escapeHtml(chapter.title)}</a></li>`).join('');
      const body = chapters.map((chapter, index) => `<section id="cap-${index + 1}" class="chapter"><article>${chapter.html}</article></section>`).join('\n');
      const exported = `<!doctype html>
<html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>EasyPeasy — edição pessoal</title>
<style>html{scroll-behavior:smooth}body{margin:0;background:#171923;color:#eceef7;font-family:system-ui,-apple-system,Segoe UI,sans-serif;line-height:1.7}.shell{display:grid;grid-template-columns:minmax(230px,300px) 1fr;min-height:100vh}.toc{position:sticky;top:0;height:100vh;overflow:auto;box-sizing:border-box;padding:24px;border-right:1px solid #34384b;background:#202332}.toc a{color:#cbd5ff;text-decoration:none}.toc li{margin:.55rem 0}.main{min-width:0;padding:42px max(24px,6vw)}.chapter{max-width:860px;margin:0 auto 70px;padding-bottom:55px;border-bottom:1px solid #34384b}.chapter img{max-width:100%;height:auto}.chapter a{color:#9db0ff}.chapter blockquote{border-left:3px solid #6577d8;margin-left:0;padding-left:18px;opacity:.95}@media(max-width:760px){.shell{display:block}.toc{position:relative;height:auto;border-right:0;border-bottom:1px solid #34384b}.main{padding:24px 18px}}</style></head>
<body><div class="shell"><nav class="toc"><h1>EasyPeasy</h1><p>Minha versão editada</p><ol>${toc}</ol></nav><main class="main">${body}</main></div></body></html>`;

      if (window.FocusGuardBookExporter?.exportHtml) {
        window.FocusGuardBookExporter.exportHtml('EasyPeasy-editado.html', exported);
        toast('Escolha onde salvar o arquivo HTML.');
      } else {
        const url = URL.createObjectURL(new Blob([exported], { type: 'text/html;charset=utf-8' }));
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = 'EasyPeasy-editado.html';
        anchor.click();
        setTimeout(() => URL.revokeObjectURL(url), 1500);
      }
    } catch (error) {
      console.error(error);
      toast('Não foi possível exportar o livro.');
    } finally {
      button.disabled = false;
      button.textContent = oldLabel;
    }
  }

  function initEditor() {
    if (!article()) return;
    const style = document.createElement('style');
    style.textContent = `.book-editor-panel{position:fixed;z-index:80;top:74px;right:18px;width:min(330px,calc(100vw - 36px));padding:14px;border:1px solid rgba(127,127,127,.25);border-radius:16px;background:#202332;color:#eceef7;box-shadow:0 18px 60px rgba(0,0,0,.38);display:none;gap:9px;grid-template-columns:1fr 1fr}.book-editor-panel.open{display:grid}.book-editor-panel .title{grid-column:1/-1;font-weight:800}.book-editor-panel .help{grid-column:1/-1;font-size:12px;opacity:.72;line-height:1.45}.book-editor-panel button{border:1px solid #41465d;border-radius:10px;padding:10px 8px;background:#2b2f40;color:inherit;font:inherit;font-weight:700}.book-editor-panel .primary{background:#4f6bed;border-color:#4f6bed}.book-editor-panel .danger{color:#ff8585}.book-content.editor-active{outline:2px dashed #6f83ff;outline-offset:10px;min-height:45vh;caret-color:currentColor}.book-editor-toast{position:fixed;z-index:100;left:50%;bottom:24px;transform:translate(-50%,18px);opacity:0;background:#111827;color:#fff;padding:10px 14px;border-radius:999px;font-size:13px;font-weight:700;transition:.2s;pointer-events:none}.book-editor-toast.visible{opacity:1;transform:translate(-50%,0)}@media(max-width:880px){.book-editor-panel{top:auto;left:12px;right:12px;bottom:12px;width:auto}}`;
    document.head.appendChild(style);

    const toggle = document.createElement('button');
    toggle.type = 'button';
    toggle.className = 'icon-button';
    toggle.textContent = '✎';
    toggle.setAttribute('aria-label', 'Editar minha versão do livro');
    document.querySelector('.topbar .toolbar-group')?.appendChild(toggle);

    panel = document.createElement('div');
    panel.className = 'book-editor-panel';
    panel.innerHTML = `<div class="title">Minha versão do EasyPeasy</div><div class="help">Edite o próprio texto do livro. As alterações ficam neste aparelho e podem ser exportadas em um único HTML.</div><button class="primary" data-editor="edit">Editar texto</button><button data-editor="save">Salvar</button><button data-editor="new">Novo capítulo</button><button data-editor="restore">Restaurar original</button><button class="danger" data-editor="delete">Excluir capítulo</button><button data-editor="export">Exportar HTML</button>`;
    document.body.appendChild(panel);

    toggle.addEventListener('click', () => panel.classList.toggle('open'));
    panel.querySelector('[data-editor="edit"]').addEventListener('click', () => setEditing(!editing));
    panel.querySelector('[data-editor="save"]').addEventListener('click', saveChapter);
    panel.querySelector('[data-editor="new"]').addEventListener('click', newChapter);
    panel.querySelector('[data-editor="restore"]').addEventListener('click', restoreChapter);
    panel.querySelector('[data-editor="delete"]').addEventListener('click', deleteChapter);
    panel.querySelector('[data-editor="export"]').addEventListener('click', exportBook);
    document.addEventListener('keydown', event => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's' && editing) {
        event.preventDefault();
        saveChapter();
      }
    });
  }

  applySavedContent();
  rebuildSummary();
  initEditor();
})();
