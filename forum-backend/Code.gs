/**
 * HardBlock — backend do fórum em Google Apps Script.
 *
 * Publicado como App da Web com "Executar como: eu" e "Quem pode acessar:
 * qualquer pessoa". Todos os dados ficam numa planilha do Google Sheets no
 * Drive da conta que publicou o script. O app nunca recebe credencial dessa
 * conta: ele só conversa com este script por HTTPS.
 *
 * Fluxo:
 *  1. O app entra com Google (Credential Manager) e manda o ID token em
 *     `signIn`. O script confere o token no Google e devolve uma sessão própria,
 *     assinada com HMAC e válida por 90 dias. O e-mail nunca é guardado.
 *  2. As demais ações mandam essa sessão. Escritas passam pelo LockService, então
 *     curtidas e contadores nunca se sobrescrevem entre aparelhos.
 *
 * Moderação pela planilha:
 *  - posts/comments: `status` = hidden esconde a publicação de todos.
 *  - users: `status` = banned impede a conta de escrever (vale em até 2 min).
 *  - Três denúncias de pessoas diferentes escondem a publicação sozinhas.
 * Não apague nem insira linhas nas abas: a paginação e as buscas dependem da
 * ordem das linhas. Para remover algo, use a coluna `status`.
 */

// ─── Configuração ──────────────────────────────────────────────────────────
// ID do cliente OAuth do tipo "Aplicativo da Web" criado no Google Cloud. É o
// mesmo valor de focusguard.forumGoogleWebClientId no gradle.properties do app.
// Também pode ser definido na propriedade do script GOOGLE_WEB_CLIENT_ID.
const GOOGLE_WEB_CLIENT_ID = '';

const FORUM_API_VERSION = 1;
const SESSION_TTL_MS = 90 * 24 * 60 * 60 * 1000;
const LOCK_WAIT_MS = 15000;
const USER_STATUS_CACHE_SECONDS = 120;
const GOOGLE_ISSUERS = ['accounts.google.com', 'https://accounts.google.com'];
const TOKENINFO_URL = 'https://oauth2.googleapis.com/tokeninfo?id_token=';

const LIMITS = {
  postCodePoints: 1000,
  commentCodePoints: 500,
  nameCodePoints: 40,
  avatarCount: 5,
  feedPage: 20,
  userPosts: 50,
  comments: 200,
  notifications: 50,
  maxScanRows: 2000,
  scanChunkRows: 200,
  rowGroupGap: 40,
  requestChars: 16000,
  autoHideReports: 3
};

// CacheService guarda no máximo 6 horas, então nenhuma janela passa disso.
const RATE_LIMITS = {
  read: { seconds: 60, max: 90 },
  write: { seconds: 60, max: 12 },
  writeLong: { seconds: 21600, max: 300 },
  signIn: { seconds: 3600, max: 30 },
  signInGlobal: { seconds: 60, max: 120 }
};

const TABLES = {
  posts: ['id', 'authorId', 'authorName', 'avatarId', 'body', 'createdAt', 'likeCount',
    'commentsCount', 'status'],
  comments: ['id', 'postId', 'authorId', 'authorName', 'avatarId', 'body', 'createdAt',
    'status'],
  likes: ['key', 'postId', 'userId', 'createdAt'],
  notifications: ['id', 'recipientId', 'actorId', 'actorName', 'actorAvatarId', 'postId',
    'type', 'createdAt', 'read'],
  users: ['userId', 'displayName', 'avatarId', 'createdAt', 'lastSignInAt', 'status'],
  reports: ['key', 'targetType', 'targetId', 'postId', 'reporterId', 'createdAt', 'status']
};

// Texto livre vai para a planilha como string JSON ("..."). Assim nada que a
// pessoa digite vira fórmula (=IMPORTXML...), número ou data, e quebras de
// linha e espaços voltam exatamente como foram escritos.
const TEXT_FIELDS = { authorName: true, body: true, actorName: true, displayName: true };
const NUMBER_FORMAT_FIELDS = ['createdAt', 'lastSignInAt'];

const ID_PATTERN = /^[A-Za-z][A-Za-z0-9_-]{0,79}$/;
const USER_ID_PATTERN = /^u[0-9a-f]{24}$/;
const CURSOR_PATTERN = /^(\d{1,9})\.(\d{1,16})$/;

const ACTIONS = {
  signIn: { access: 'public', run: actionSignIn },
  listPosts: { access: 'read', run: actionListPosts },
  listUserPosts: { access: 'read', run: actionListUserPosts },
  listComments: { access: 'read', run: actionListComments },
  listNotifications: { access: 'read', run: actionListNotifications },
  markNotificationsRead: { access: 'read', run: actionMarkNotificationsRead },
  publishPost: { access: 'write', run: actionPublishPost },
  toggleLike: { access: 'write', run: actionToggleLike },
  addComment: { access: 'write', run: actionAddComment },
  deletePost: { access: 'write', run: actionDeletePost },
  deleteComment: { access: 'write', run: actionDeleteComment },
  report: { access: 'write', run: actionReport }
};

// ─── Entrada HTTP ──────────────────────────────────────────────────────────

function doGet() {
  return jsonOutput({
    ok: true,
    data: { service: 'hardblock-forum', version: FORUM_API_VERSION, configured: isConfigured() }
  });
}

function doPost(e) {
  return jsonOutput(respond(() => {
    const raw = e && e.postData ? e.postData.contents : null;
    if (typeof raw !== 'string' || raw.length === 0 || raw.length > LIMITS.requestChars) {
      throw new ApiError('bad_request');
    }
    let request;
    try {
      request = JSON.parse(raw);
    } catch (parseError) {
      throw new ApiError('bad_request');
    }
    return handleRequest(request, Date.now());
  }));
}

function respond(work) {
  try {
    return { ok: true, data: work() };
  } catch (error) {
    if (error instanceof ApiError) return { ok: false, error: error.code };
    console.error(error && error.stack ? error.stack : String(error));
    return { ok: false, error: 'server_error' };
  }
}

function handleRequest(request, now) {
  if (!request || typeof request !== 'object' || Array.isArray(request)) {
    throw new ApiError('bad_request');
  }
  if (request.v !== FORUM_API_VERSION) throw new ApiError('unsupported_version');
  const name = request.action;
  if (typeof name !== 'string' || !Object.prototype.hasOwnProperty.call(ACTIONS, name)) {
    throw new ApiError('bad_request');
  }

  const action = ACTIONS[name];
  if (action.access === 'public') {
    return action.run({ userId: null, params: request, now: now });
  }

  const userId = verifySession(request.session, now);
  if (action.access === 'write') {
    consumeRate('w:' + userId, RATE_LIMITS.write, now);
    consumeRate('wl:' + userId, RATE_LIMITS.writeLong, now);
    if (userStatus(userId) === 'banned') throw new ApiError('banned');
  } else {
    consumeRate('r:' + userId, RATE_LIMITS.read, now);
  }
  return action.run({ userId: userId, params: request, now: now });
}

function jsonOutput(payload) {
  return ContentService.createTextOutput(JSON.stringify(payload))
    .setMimeType(ContentService.MimeType.JSON);
}

function ApiError(code) {
  this.code = code;
  this.message = code;
}
ApiError.prototype = Object.create(Error.prototype);
ApiError.prototype.constructor = ApiError;

// ─── Instalação ────────────────────────────────────────────────────────────

/**
 * Rode uma vez pelo editor (Executar → setup). Cria a planilha, as abas e os
 * segredos. Rodar de novo é seguro: nada existente é apagado.
 */
function setup() {
  const properties = PropertiesService.getScriptProperties();
  const existingId = properties.getProperty('SPREADSHEET_ID');
  const book = existingId
    ? SpreadsheetApp.openById(existingId)
    : SpreadsheetApp.create('HardBlock Fórum — dados');

  Object.keys(TABLES).forEach(name => {
    const sheet = book.getSheetByName(name) || book.insertSheet(name);
    ensureHeader(sheet, TABLES[name]);
    sheet.setFrozenRows(1);
    NUMBER_FORMAT_FIELDS.forEach(field => {
      const index = TABLES[name].indexOf(field);
      if (index >= 0) sheet.getRange(2, index + 1, sheet.getMaxRows() - 1, 1).setNumberFormat('0');
    });
  });

  book.getSheets().forEach(sheet => {
    if (!TABLES[sheet.getName()] && sheet.getLastRow() === 0 && book.getSheets().length > 1) {
      book.deleteSheet(sheet);
    }
  });

  if (!properties.getProperty('USER_ID_SALT')) {
    properties.setProperty('USER_ID_SALT', randomSecret());
  }
  if (!properties.getProperty('SESSION_SECRET')) {
    properties.setProperty('SESSION_SECRET', randomSecret());
  }
  properties.setProperty('SPREADSHEET_ID', book.getId());

  console.log('Planilha do fórum: ' + book.getUrl());
  if (!webClientId()) {
    console.warn('Falta o GOOGLE_WEB_CLIENT_ID: sem ele ninguém consegue entrar no fórum.');
  }
  return book.getUrl();
}

/**
 * Desconecta todo mundo trocando o segredo das sessões. As contas, posts e
 * curtidas continuam iguais; cada pessoa só precisa entrar de novo.
 */
function rotateSessionSecret() {
  PropertiesService.getScriptProperties().setProperty('SESSION_SECRET', randomSecret());
}

function ensureHeader(sheet, header) {
  if (sheet.getLastRow() === 0) {
    sheet.getRange(1, 1, 1, header.length).setValues([header]);
    return;
  }
  const current = sheet.getRange(1, 1, 1, header.length).getValues()[0].map(String);
  if (current.join('|') !== header.join('|')) {
    throw new Error('A aba "' + sheet.getName() + '" tem colunas diferentes do esperado: ' +
      header.join(', '));
  }
}

function isConfigured() {
  const properties = PropertiesService.getScriptProperties();
  return Boolean(
    properties.getProperty('SPREADSHEET_ID') &&
    properties.getProperty('USER_ID_SALT') &&
    properties.getProperty('SESSION_SECRET') &&
    webClientId()
  );
}

function webClientId() {
  const fromProperty = PropertiesService.getScriptProperties().getProperty('GOOGLE_WEB_CLIENT_ID');
  return String(fromProperty || GOOGLE_WEB_CLIENT_ID || '').trim();
}

function secretProperty(name) {
  const value = PropertiesService.getScriptProperties().getProperty(name);
  if (!value) throw new ApiError('not_configured');
  return value;
}

function randomSecret() {
  return (Utilities.getUuid() + Utilities.getUuid()).replace(/-/g, '');
}

// ─── Identidade ────────────────────────────────────────────────────────────

function actionSignIn(ctx) {
  consumeRate('signin-global', RATE_LIMITS.signInGlobal, ctx.now);
  const subject = verifyGoogleIdToken(ctx.params.idToken, ctx.now);
  const userId = userIdForSubject(subject);
  consumeRate('signin:' + userId, RATE_LIMITS.signIn, ctx.now);
  const author = normalizeAuthor(ctx.params.author);

  withScriptLock(() => {
    const existing = findOne('users', 'userId', userId);
    if (existing) {
      existing.record.displayName = author.displayName;
      existing.record.avatarId = author.avatarId;
      existing.record.lastSignInAt = ctx.now;
      writeRecord('users', existing.row, existing.record);
    } else {
      appendRecord('users', {
        userId: userId,
        displayName: author.displayName,
        avatarId: author.avatarId,
        createdAt: ctx.now,
        lastSignInAt: ctx.now,
        status: ''
      });
    }
  });

  const expiresAt = ctx.now + SESSION_TTL_MS;
  const payload = 'v1.' + userId + '.' + expiresAt;
  return { session: payload + '.' + signSession(payload), userId: userId, expiresAt: expiresAt };
}

function verifyGoogleIdToken(idToken, now) {
  if (typeof idToken !== 'string' || idToken.length > 4096 ||
      !/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(idToken)) {
    throw new ApiError('invalid_token');
  }
  const clientId = webClientId();
  if (!clientId) throw new ApiError('not_configured');

  // Conferência local barata antes de gastar a cota de UrlFetch com lixo.
  const claims = decodeJwtPayload(idToken);
  if (!claims || claims.aud !== clientId || !(Number(claims.exp) * 1000 > now)) {
    throw new ApiError('invalid_token');
  }

  const response = UrlFetchApp.fetch(TOKENINFO_URL + encodeURIComponent(idToken), {
    muteHttpExceptions: true
  });
  if (response.getResponseCode() !== 200) throw new ApiError('invalid_token');

  let info;
  try {
    info = JSON.parse(response.getContentText());
  } catch (parseError) {
    throw new ApiError('invalid_token');
  }
  if (!info || info.aud !== clientId || GOOGLE_ISSUERS.indexOf(info.iss) < 0 ||
      !(Number(info.exp) * 1000 > now) || typeof info.sub !== 'string' || !info.sub) {
    throw new ApiError('invalid_token');
  }
  return info.sub;
}

function decodeJwtPayload(token) {
  try {
    let segment = token.split('.')[1];
    while (segment.length % 4 !== 0) segment += '=';
    const bytes = Utilities.base64DecodeWebSafe(segment);
    return JSON.parse(Utilities.newBlob(bytes).getDataAsString('UTF-8'));
  } catch (error) {
    return null;
  }
}

// O `sub` do Google nunca sai daqui: o ID público é um HMAC dele com um sal
// secreto. Não troque USER_ID_SALT, ou todo mundo vira outra pessoa.
function userIdForSubject(subject) {
  const digest = Utilities.computeHmacSha256Signature(
    'google-sub:' + subject,
    secretProperty('USER_ID_SALT')
  );
  return 'u' + bytesToHex(digest).slice(0, 24);
}

function signSession(payload) {
  const digest = Utilities.computeHmacSha256Signature(payload, secretProperty('SESSION_SECRET'));
  return Utilities.base64EncodeWebSafe(digest).replace(/=+$/, '');
}

function verifySession(token, now) {
  if (typeof token !== 'string' || token.length > 200) throw new ApiError('unauthorized');
  const parts = token.split('.');
  if (parts.length !== 4 || parts[0] !== 'v1' || !USER_ID_PATTERN.test(parts[1]) ||
      !/^\d{1,16}$/.test(parts[2])) {
    throw new ApiError('unauthorized');
  }
  const expected = signSession(parts.slice(0, 3).join('.'));
  if (!constantTimeEquals(expected, parts[3]) || Number(parts[2]) <= now) {
    throw new ApiError('unauthorized');
  }
  return parts[1];
}

function userStatus(userId) {
  const cache = CacheService.getScriptCache();
  const key = 'status:' + userId;
  const cached = cache.get(key);
  if (cached !== null) return cached === '-' ? '' : cached;
  const user = findOne('users', 'userId', userId);
  const status = user ? str(user.record.status) : '';
  cache.put(key, status || '-', USER_STATUS_CACHE_SECONDS);
  return status;
}

// ─── Posts ─────────────────────────────────────────────────────────────────

function actionListPosts(ctx) {
  const cursor = parseCursor(ctx.params.cursor);
  const lastRow = table('posts').getLastRow();
  const startRow = cursor ? Math.min(cursor.row, lastRow) : lastRow;
  const page = [];
  let examined = null;

  const outcome = scanPostsBackward(startRow, (row, record) => {
    if (!record.id) return true;
    const createdAt = num(record.createdAt);
    if (cursor && createdAt >= cursor.createdAt) return true;
    if (page.length >= LIMITS.feedPage) return false;
    examined = { row: row, createdAt: createdAt };
    if (isVisible(record)) page.push(record);
    return true;
  });

  const liked = likedPostIds(ctx.userId, page.map(record => str(record.id)));
  const hasMore = examined !== null && examined.row > 2 && outcome !== 'end';
  return {
    posts: page.map(record => serializePost(record, liked[str(record.id)] === true)),
    nextCursor: hasMore ? examined.row + '.' + examined.createdAt : null
  };
}

function actionListUserPosts(ctx) {
  const rows = findRows('posts', 'authorId', ctx.userId);
  const records = readRows('posts', rows.slice(-LIMITS.userPosts * 2))
    .map(entry => entry.record)
    .filter(isVisible)
    .reverse()
    .slice(0, LIMITS.userPosts);
  const liked = likedPostIds(ctx.userId, records.map(record => str(record.id)));
  return { posts: records.map(record => serializePost(record, liked[str(record.id)] === true)) };
}

function actionPublishPost(ctx) {
  const body = normalizeText(ctx.params.body, LIMITS.postCodePoints);
  if (!body) throw new ApiError('bad_request');
  const author = normalizeAuthor(ctx.params.author);

  return withScriptLock(() => {
    const sheet = table('posts');
    const lastRow = sheet.getLastRow();
    const previous = lastRow >= 2
      ? num(sheet.getRange(lastRow, column('posts', 'createdAt')).getValue())
      : 0;
    // createdAt sempre cresce junto com a linha: é isso que deixa a paginação
    // por cursor estável mesmo com dois posts no mesmo milissegundo.
    const createdAt = Math.max(ctx.now, previous + 1);
    const record = {
      id: newId('p', createdAt),
      authorId: ctx.userId,
      authorName: author.displayName,
      avatarId: author.avatarId,
      body: body,
      createdAt: createdAt,
      likeCount: 0,
      commentsCount: 0,
      status: ''
    };
    appendRecord('posts', record);
    return { post: serializePost(record, false) };
  });
}

function actionDeletePost(ctx) {
  const postId = requireId(ctx.params.postId);
  return withScriptLock(() => {
    const post = findOne('posts', 'id', postId);
    if (!post || str(post.record.status) === 'deleted') throw new ApiError('not_found');
    if (str(post.record.authorId) !== ctx.userId) throw new ApiError('forbidden');
    post.record.body = '';
    post.record.status = 'deleted';
    writeRecord('posts', post.row, post.record);
    return { deleted: true };
  });
}

function scanPostsBackward(startRow, visit) {
  const sheet = table('posts');
  const columns = TABLES.posts.length;
  let row = startRow;
  let scanned = 0;
  while (row >= 2) {
    if (scanned >= LIMITS.maxScanRows) return 'limit';
    const top = Math.max(2, row - LIMITS.scanChunkRows + 1);
    const values = sheet.getRange(top, 1, row - top + 1, columns).getValues();
    for (let current = row; current >= top; current--) {
      scanned++;
      if (visit(current, fromRow('posts', values[current - top])) === false) return 'stopped';
    }
    row = top - 1;
  }
  return 'end';
}

function parseCursor(raw) {
  if (raw === undefined || raw === null || raw === '') return null;
  const match = CURSOR_PATTERN.exec(String(raw));
  if (!match) throw new ApiError('bad_request');
  return { row: Number(match[1]), createdAt: Number(match[2]) };
}

function serializePost(record, likedByMe) {
  return {
    id: str(record.id),
    authorId: str(record.authorId),
    authorName: str(record.authorName),
    avatarId: normalizeAvatar(record.avatarId),
    body: str(record.body),
    createdAt: num(record.createdAt),
    likeCount: Math.max(0, num(record.likeCount)),
    commentsCount: Math.max(0, num(record.commentsCount)),
    likedByMe: likedByMe
  };
}

// ─── Curtidas ──────────────────────────────────────────────────────────────

function actionToggleLike(ctx) {
  const postId = requireId(ctx.params.postId);
  const author = normalizeAuthor(ctx.params.author);

  return withScriptLock(() => {
    const post = findOne('posts', 'id', postId);
    if (!post || !isVisible(post.record)) throw new ApiError('not_found');

    const key = postId + ':' + ctx.userId;
    const notificationId = 'nl-' + postId + '-' + ctx.userId;
    const existing = findRows('likes', 'key', key);
    let liked;
    if (existing.length > 0) {
      existing.forEach(row => clearRow('likes', row));
      findRows('notifications', 'id', notificationId).forEach(row => clearRow('notifications', row));
      liked = false;
    } else {
      appendRecord('likes', { key: key, postId: postId, userId: ctx.userId, createdAt: ctx.now });
      notify(post.record, ctx.userId, author, 'LIKE', notificationId, ctx.now);
      liked = true;
    }

    SpreadsheetApp.flush();
    post.record.likeCount = findRows('likes', 'postId', postId).length;
    writeField('posts', post.row, 'likeCount', post.record.likeCount);
    return { post: serializePost(post.record, liked) };
  });
}

// Uma busca só por página: a regex junta os posts da página com a pessoa.
function likedPostIds(userId, postIds) {
  const ids = postIds.filter(id => ID_PATTERN.test(id));
  if (ids.length === 0) return {};
  const sheet = table('likes');
  const lastRow = sheet.getLastRow();
  if (lastRow < 2) return {};
  const liked = {};
  sheet.getRange(2, column('likes', 'key'), lastRow - 1, 1)
    .createTextFinder('(' + ids.join('|') + '):' + userId)
    .matchCase(true)
    .matchEntireCell(true)
    .useRegularExpression(true)
    .findAll()
    .forEach(range => {
      liked[str(range.getValue()).split(':')[0]] = true;
    });
  return liked;
}

// ─── Comentários ───────────────────────────────────────────────────────────

function actionListComments(ctx) {
  const postId = requireId(ctx.params.postId);
  const post = findOne('posts', 'id', postId);
  if (!post || !isVisible(post.record)) throw new ApiError('not_found');
  const rows = findRows('comments', 'postId', postId);
  const comments = readRows('comments', rows.slice(-LIMITS.comments * 2))
    .map(entry => entry.record)
    .filter(isVisible)
    .slice(-LIMITS.comments);
  return { comments: comments.map(serializeComment) };
}

function actionAddComment(ctx) {
  const postId = requireId(ctx.params.postId);
  const body = normalizeText(ctx.params.body, LIMITS.commentCodePoints);
  if (!body) throw new ApiError('bad_request');
  const author = normalizeAuthor(ctx.params.author);

  return withScriptLock(() => {
    const post = findOne('posts', 'id', postId);
    if (!post || !isVisible(post.record)) throw new ApiError('not_found');
    const record = {
      id: newId('c', ctx.now),
      postId: postId,
      authorId: ctx.userId,
      authorName: author.displayName,
      avatarId: author.avatarId,
      body: body,
      createdAt: ctx.now,
      status: ''
    };
    appendRecord('comments', record);
    notify(post.record, ctx.userId, author, 'COMMENT', 'nc-' + record.id, ctx.now);
    const commentsCount = recountComments(post);
    return { comment: serializeComment(record), commentsCount: commentsCount };
  });
}

function actionDeleteComment(ctx) {
  const postId = requireId(ctx.params.postId);
  const commentId = requireId(ctx.params.commentId);
  return withScriptLock(() => {
    const comment = findOne('comments', 'id', commentId);
    if (!comment || str(comment.record.postId) !== postId ||
        str(comment.record.status) === 'deleted') {
      throw new ApiError('not_found');
    }
    if (str(comment.record.authorId) !== ctx.userId) throw new ApiError('forbidden');
    comment.record.body = '';
    comment.record.status = 'deleted';
    writeRecord('comments', comment.row, comment.record);
    const post = findOne('posts', 'id', postId);
    return { deleted: true, commentsCount: post ? recountComments(post) : 0 };
  });
}

// Conta de novo a partir das linhas, então o número se corrige sozinho mesmo
// depois de um comentário escondido à mão na planilha.
function recountComments(post) {
  SpreadsheetApp.flush();
  const rows = findRows('comments', 'postId', str(post.record.id));
  const count = readRows('comments', rows).filter(entry => isVisible(entry.record)).length;
  post.record.commentsCount = count;
  writeField('posts', post.row, 'commentsCount', count);
  return count;
}

function serializeComment(record) {
  return {
    id: str(record.id),
    postId: str(record.postId),
    authorId: str(record.authorId),
    authorName: str(record.authorName),
    avatarId: normalizeAvatar(record.avatarId),
    body: str(record.body),
    createdAt: num(record.createdAt)
  };
}

// ─── Notificações ──────────────────────────────────────────────────────────

function notify(post, actorId, actor, type, notificationId, now) {
  const recipientId = str(post.authorId);
  if (!recipientId || recipientId === actorId) return;
  if (findRows('notifications', 'id', notificationId).length > 0) return;
  appendRecord('notifications', {
    id: notificationId,
    recipientId: recipientId,
    actorId: actorId,
    actorName: actor.displayName,
    actorAvatarId: actor.avatarId,
    postId: str(post.id),
    type: type,
    createdAt: now,
    read: 0
  });
}

function actionListNotifications(ctx) {
  return { notifications: recentNotifications(ctx.userId).map(entry => serializeNotification(entry.record)) };
}

function actionMarkNotificationsRead(ctx) {
  return withScriptLock(() => {
    const entries = recentNotifications(ctx.userId);
    entries.forEach(entry => {
      if (num(entry.record.read) !== 1) {
        entry.record.read = 1;
        writeField('notifications', entry.row, 'read', 1);
      }
    });
    return { notifications: entries.map(entry => serializeNotification(entry.record)) };
  });
}

function recentNotifications(userId) {
  const rows = findRows('notifications', 'recipientId', userId);
  return readRows('notifications', rows.slice(-LIMITS.notifications))
    .filter(entry => str(entry.record.id) !== '')
    .sort((a, b) => num(b.record.createdAt) - num(a.record.createdAt));
}

function serializeNotification(record) {
  return {
    id: str(record.id),
    actorId: str(record.actorId),
    actorName: str(record.actorName),
    actorAvatarId: normalizeAvatar(record.actorAvatarId),
    postId: str(record.postId),
    type: str(record.type) === 'COMMENT' ? 'COMMENT' : 'LIKE',
    createdAt: num(record.createdAt),
    read: num(record.read) === 1
  };
}

// ─── Denúncias ─────────────────────────────────────────────────────────────

function actionReport(ctx) {
  const targetType = ctx.params.targetType;
  if (targetType !== 'post' && targetType !== 'comment') throw new ApiError('bad_request');
  const targetId = requireId(ctx.params.targetId);

  return withScriptLock(() => {
    const target = findOne(targetType === 'post' ? 'posts' : 'comments', 'id', targetId);
    if (!target || !isVisible(target.record)) throw new ApiError('not_found');
    if (str(target.record.authorId) === ctx.userId) throw new ApiError('forbidden');
    const postId = targetType === 'post' ? targetId : str(target.record.postId);

    const key = targetType + ':' + targetId + ':' + ctx.userId;
    if (findRows('reports', 'key', key).length === 0) {
      appendRecord('reports', {
        key: key,
        targetType: targetType,
        targetId: targetId,
        postId: postId,
        reporterId: ctx.userId,
        createdAt: ctx.now,
        status: 'open'
      });
      SpreadsheetApp.flush();
      if (findRows('reports', 'targetId', targetId).length >= LIMITS.autoHideReports) {
        target.record.status = 'hidden';
        writeField(targetType === 'post' ? 'posts' : 'comments', target.row, 'status', 'hidden');
        if (targetType === 'comment') {
          const post = findOne('posts', 'id', postId);
          if (post) recountComments(post);
        }
      }
    }
    return { reported: true };
  });
}

// ─── Planilha ──────────────────────────────────────────────────────────────

let cachedBook = null;

function table(name) {
  if (!cachedBook) {
    const id = PropertiesService.getScriptProperties().getProperty('SPREADSHEET_ID');
    if (!id) throw new ApiError('not_configured');
    cachedBook = SpreadsheetApp.openById(id);
  }
  const sheet = cachedBook.getSheetByName(name);
  if (!sheet) throw new ApiError('not_configured');
  return sheet;
}

function column(name, field) {
  const index = TABLES[name].indexOf(field);
  if (index < 0) throw new Error('Coluna desconhecida: ' + name + '.' + field);
  return index + 1;
}

function findRows(name, field, value, useRegex) {
  const sheet = table(name);
  const lastRow = sheet.getLastRow();
  if (lastRow < 2 || value === '') return [];
  return sheet.getRange(2, column(name, field), lastRow - 1, 1)
    .createTextFinder(String(value))
    .matchCase(true)
    .matchEntireCell(true)
    .useRegularExpression(Boolean(useRegex))
    .findAll()
    .map(range => range.getRow())
    .sort((a, b) => a - b);
}

function findOne(name, field, value) {
  const rows = findRows(name, field, value);
  if (rows.length === 0) return null;
  return readRows(name, [rows[0]])[0];
}

// Lê várias linhas juntando as vizinhas numa chamada só: cada getValues custa
// uma ida ao Sheets, então ler linha por linha deixaria listas longas lentas.
function readRows(name, rows) {
  const sheet = table(name);
  const columns = TABLES[name].length;
  const entries = [];
  let index = 0;
  while (index < rows.length) {
    const first = rows[index];
    let last = first;
    let next = index + 1;
    while (next < rows.length && rows[next] - last <= LIMITS.rowGroupGap) {
      last = rows[next];
      next++;
    }
    const values = sheet.getRange(first, 1, last - first + 1, columns).getValues();
    for (let i = index; i < next; i++) {
      entries.push({ row: rows[i], record: fromRow(name, values[rows[i] - first]) });
    }
    index = next;
  }
  return entries;
}

function appendRecord(name, record) {
  table(name).appendRow(toRow(name, record));
}

function writeRecord(name, row, record) {
  table(name).getRange(row, 1, 1, TABLES[name].length).setValues([toRow(name, record)]);
}

function writeField(name, row, field, value) {
  table(name).getRange(row, column(name, field))
    .setValue(TEXT_FIELDS[field] ? encodeText(value) : value);
}

// Linhas viram vazias em vez de sumir: apagar deslocaria as linhas de baixo
// enquanto outra leitura, fora da trava, ainda usa os números antigos.
function clearRow(name, row) {
  table(name).getRange(row, 1, 1, TABLES[name].length).clearContent();
}

function toRow(name, record) {
  return TABLES[name].map(field => {
    const value = record[field];
    if (value === undefined || value === null) return '';
    return TEXT_FIELDS[field] ? encodeText(value) : value;
  });
}

function fromRow(name, values) {
  const record = {};
  TABLES[name].forEach((field, index) => {
    const value = values[index];
    record[field] = TEXT_FIELDS[field] ? decodeText(value) : value;
  });
  return record;
}

function encodeText(value) {
  return JSON.stringify(String(value));
}

function decodeText(value) {
  if (value === '' || value === null || value === undefined) return '';
  const text = String(value);
  if (text.length >= 2 && text.charAt(0) === '"' && text.charAt(text.length - 1) === '"') {
    try {
      const parsed = JSON.parse(text);
      if (typeof parsed === 'string') return parsed;
    } catch (ignored) {
      // Texto digitado à mão na planilha: vale como está.
    }
  }
  return text;
}

function isVisible(record) {
  return str(record.id) !== '' && str(record.status) === '';
}

// ─── Utilidades ────────────────────────────────────────────────────────────

function withScriptLock(work) {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(LOCK_WAIT_MS)) throw new ApiError('busy');
  try {
    const result = work();
    SpreadsheetApp.flush();
    return result;
  } finally {
    lock.releaseLock();
  }
}

function consumeRate(bucket, rule, now) {
  const cache = CacheService.getScriptCache();
  const key = 'rl:' + bucket + ':' + Math.floor(now / (rule.seconds * 1000));
  const used = Number(cache.get(key)) || 0;
  if (used >= rule.max) throw new ApiError('rate_limited');
  cache.put(key, String(used + 1), rule.seconds);
}

function normalizeAuthor(raw) {
  const source = raw && typeof raw === 'object' ? raw : {};
  return {
    displayName: takeCodePoints(
      stripControls(String(source.displayName || '')).replace(/\s+/g, ' ').trim(),
      LIMITS.nameCodePoints
    ),
    avatarId: normalizeAvatar(source.avatarId)
  };
}

function normalizeText(raw, maxCodePoints) {
  if (typeof raw !== 'string') return '';
  const text = stripControls(raw.replace(/\r\n/g, '\n').replace(/\r/g, '\n'));
  return takeCodePoints(text, maxCodePoints).trim();
}

function normalizeAvatar(value) {
  const avatar = Number(value);
  return Number.isInteger(avatar) && avatar >= 0 && avatar < LIMITS.avatarCount ? avatar : 0;
}

function stripControls(text) {
  return text.replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, '');
}

function takeCodePoints(text, maxCodePoints) {
  const points = Array.from(text);
  return points.length <= maxCodePoints ? text : points.slice(0, maxCodePoints).join('');
}

function requireId(value) {
  if (typeof value !== 'string' || !ID_PATTERN.test(value)) throw new ApiError('bad_request');
  return value;
}

function newId(prefix, now) {
  return prefix + now.toString(36) + Utilities.getUuid().replace(/-/g, '').slice(0, 16);
}

function bytesToHex(bytes) {
  return bytes.map(byte => ((byte & 0xff) + 0x100).toString(16).slice(1)).join('');
}

function constantTimeEquals(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function str(value) {
  return value === null || value === undefined ? '' : String(value);
}

function num(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number : 0;
}
