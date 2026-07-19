(() => {
  'use strict';

  const root = document.documentElement;
  const reader = document.querySelector('.reader');
  const sidebar = document.querySelector('.sidebar');
  const sidebarButton = document.querySelector('[data-action="sidebar"]');
  const themeButton = document.querySelector('[data-action="theme"]');
  const fontUpButton = document.querySelector('[data-action="font-up"]');
  const fontDownButton = document.querySelector('[data-action="font-down"]');
  const progress = document.querySelector('.progress-value');
  const searchInput = document.querySelector('#book-search');
  const searchResults = document.querySelector('#search-results');
  const storage = window.localStorage;

  const savedTheme = storage.getItem('easypeasy-theme') || 'night';
  const savedScale = Number(storage.getItem('easypeasy-font-scale') || '1');
  root.dataset.theme = savedTheme;
  root.style.setProperty('--font-scale', String(Math.min(1.35, Math.max(.85, savedScale))));

  const sidebarBackdrop = document.createElement('button');
  sidebarBackdrop.type = 'button';
  sidebarBackdrop.className = 'sidebar-backdrop';
  sidebarBackdrop.setAttribute('aria-label', 'Fechar sumário');
  reader?.insertBefore(sidebarBackdrop, sidebar);

  function closeMobileSidebar() {
    reader?.classList.remove('mobile-sidebar-open');
    sidebarButton?.setAttribute('aria-expanded', 'false');
  }

  function toggleSidebar() {
    if (window.matchMedia('(max-width: 880px)').matches) {
      reader.classList.toggle('mobile-sidebar-open');
      sidebarButton?.setAttribute(
        'aria-expanded',
        String(reader.classList.contains('mobile-sidebar-open'))
      );
    } else {
      reader.classList.toggle('sidebar-hidden');
    }
  }

  function setTheme(theme) {
    root.dataset.theme = theme;
    storage.setItem('easypeasy-theme', theme);
    if (themeButton) themeButton.setAttribute('aria-label', theme === 'night' ? 'Ativar tema claro' : 'Ativar tema escuro');
  }

  function changeFont(delta) {
    const current = Number.parseFloat(getComputedStyle(root).getPropertyValue('--font-scale')) || 1;
    const next = Math.min(1.35, Math.max(.85, Math.round((current + delta) * 100) / 100));
    root.style.setProperty('--font-scale', String(next));
    storage.setItem('easypeasy-font-scale', String(next));
  }

  function updateProgress() {
    const scrollable = document.documentElement.scrollHeight - window.innerHeight;
    const ratio = scrollable > 0 ? Math.min(1, Math.max(0, window.scrollY / scrollable)) : 1;
    if (progress) progress.style.width = `${ratio * 100}%`;
    const page = document.body.dataset.page || location.pathname;
    storage.setItem(`easypeasy-progress:${page}`, String(window.scrollY));
  }

  function restoreProgress() {
    const page = document.body.dataset.page || location.pathname;
    const position = Number(storage.getItem(`easypeasy-progress:${page}`) || 0);
    if (position > 0 && !location.hash) requestAnimationFrame(() => window.scrollTo(0, position));
  }

  async function initSearch() {
    if (!searchInput || !searchResults) return;
    let index = [];
    try {
      const response = await fetch('search-index.json', { cache: 'no-store' });
      if (response.ok) index = await response.json();
    } catch (_) {
      // Android WebViewAssetLoader supports fetch. Under strict file:// mode,
      // sidebar title filtering remains available even if JSON fetch is blocked.
    }

    const normalize = (value) => value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
    const escapeHtml = (value) => value.replace(/[&<>"']/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));

    function closeResults() {
      searchResults.classList.remove('open');
      searchResults.innerHTML = '';
    }

    searchInput.addEventListener('input', () => {
      const query = normalize(searchInput.value.trim());
      document.querySelectorAll('.summary li[data-title]').forEach(item => {
        item.hidden = query.length > 0 && !normalize(item.dataset.title).includes(query);
      });
      if (query.length < 2 || index.length === 0) return closeResults();

      const matches = index
        .map(entry => {
          const title = normalize(entry.title);
          const text = normalize(entry.text);
          const at = text.indexOf(query);
          const score = title.includes(query) ? 0 : at >= 0 ? 1 : 2;
          return { entry, at, score };
        })
        .filter(item => item.score < 2)
        .sort((a, b) => a.score - b.score || a.at - b.at)
        .slice(0, 12);

      searchResults.innerHTML = matches.length
        ? matches.map(({ entry, at }) => {
            const start = Math.max(0, at - 70);
            const snippet = entry.text.slice(start, start + 180).replace(/\s+/g, ' ');
            return `<a class="search-result" href="${encodeURI(entry.url)}"><strong>${escapeHtml(entry.title)}</strong><span>${escapeHtml(snippet)}</span></a>`;
          }).join('')
        : '<div class="search-result"><span>Nenhum resultado encontrado.</span></div>';
      searchResults.classList.add('open');
    });

    document.addEventListener('click', event => {
      if (!searchResults.contains(event.target) && event.target !== searchInput) closeResults();
    });
  }

  sidebarButton?.addEventListener('click', toggleSidebar);
  sidebarBackdrop.addEventListener('click', closeMobileSidebar);
  themeButton?.addEventListener('click', () => setTheme(root.dataset.theme === 'night' ? 'light' : 'night'));
  fontUpButton?.addEventListener('click', () => changeFont(.05));
  fontDownButton?.addEventListener('click', () => changeFont(-.05));
  window.addEventListener('scroll', updateProgress, { passive: true });
  window.addEventListener('resize', updateProgress);
  document.querySelectorAll('.summary a').forEach(link => link.addEventListener('click', closeMobileSidebar));
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape') closeMobileSidebar();
  });
  document.addEventListener('click', event => {
    const link = event.target.closest?.('a[data-external="true"]');
    if (!link) return;
    event.preventDefault();
    const message = 'Este leitor funciona totalmente offline. O link externo foi bloqueado.';
    if (searchResults) {
      searchResults.innerHTML = `<div class="search-result"><strong>Link externo bloqueado</strong><span>${message}</span></div>`;
      searchResults.classList.add('open');
    }
  });

  setTheme(savedTheme);
  initSearch();
  restoreProgress();
  updateProgress();
})();
