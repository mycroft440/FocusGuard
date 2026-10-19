'use strict';

// Imitação mínima dos serviços do Apps Script usados por Code.gs, para rodar o
// backend no Node. Ela reproduz o que o Sheets faz de traiçoeiro com valores:
// texto com cara de número vira número e texto começando com "=" vira fórmula
// (aqui, um erro), para os testes pegarem qualquer valor gravado sem proteção.

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const NUMERIC_TEXT = /^[+-]?(\d+\.?\d*|\.\d+)(e[+-]?\d+)?$/i;

function coerceCell(value) {
  if (value === null || value === undefined) return '';
  if (typeof value === 'number' || typeof value === 'boolean') return value;
  const text = String(value);
  if (/^[=+]/.test(text)) {
    throw new Error('Valor viraria fórmula na planilha: ' + text.slice(0, 40));
  }
  if (NUMERIC_TEXT.test(text.trim())) return Number(text);
  return text;
}

class FakeSheet {
  constructor(name) {
    this.name = name;
    this.rows = [];
    this.frozenRows = 0;
    this.maxRows = 1000;
  }

  getName() { return this.name; }

  getMaxRows() { return Math.max(this.maxRows, this.rows.length); }

  getLastRow() {
    for (let index = this.rows.length - 1; index >= 0; index--) {
      if ((this.rows[index] || []).some(cell => cell !== '')) return index + 1;
    }
    return 0;
  }

  setFrozenRows(count) { this.frozenRows = count; }

  appendRow(values) {
    const target = this.getLastRow() + 1;
    this.writeCells(target, 1, [values]);
    return this;
  }

  getRange(row, column, numRows, numColumns) {
    const rowCount = numRows === undefined ? 1 : numRows;
    const columnCount = numColumns === undefined ? 1 : numColumns;
    if (row < 1 || column < 1 || rowCount < 1 || columnCount < 1) {
      throw new Error('Intervalo inválido: ' + [row, column, rowCount, columnCount].join(','));
    }
    return new FakeRange(this, row, column, rowCount, columnCount);
  }

  cell(row, column) {
    const values = this.rows[row - 1];
    if (!values) return '';
    const value = values[column - 1];
    return value === undefined ? '' : value;
  }

  writeCells(row, column, matrix) {
    matrix.forEach((values, rowOffset) => {
      const index = row - 1 + rowOffset;
      while (this.rows.length <= index) this.rows.push([]);
      values.forEach((value, columnOffset) => {
        this.rows[index][column - 1 + columnOffset] = coerceCell(value);
      });
    });
  }
}

class FakeRange {
  constructor(sheet, row, column, numRows, numColumns) {
    Object.assign(this, { sheet, row, column, numRows, numColumns });
  }

  getRow() { return this.row; }

  getValues() {
    const matrix = [];
    for (let r = 0; r < this.numRows; r++) {
      const values = [];
      for (let c = 0; c < this.numColumns; c++) {
        values.push(this.sheet.cell(this.row + r, this.column + c));
      }
      matrix.push(values);
    }
    return matrix;
  }

  getValue() { return this.sheet.cell(this.row, this.column); }

  setValues(matrix) {
    if (matrix.length !== this.numRows || matrix.some(values => values.length !== this.numColumns)) {
      throw new Error('Dimensões de setValues não batem com o intervalo.');
    }
    this.sheet.writeCells(this.row, this.column, matrix);
    return this;
  }

  setValue(value) {
    this.sheet.writeCells(this.row, this.column, [[value]]);
    return this;
  }

  clearContent() {
    const blank = [];
    for (let r = 0; r < this.numRows; r++) blank.push(new Array(this.numColumns).fill(''));
    this.sheet.writeCells(this.row, this.column, blank);
    return this;
  }

  setNumberFormat() { return this; }

  createTextFinder(text) {
    return new FakeTextFinder(this, String(text));
  }
}

class FakeTextFinder {
  constructor(range, text) {
    this.range = range;
    this.text = text;
    this.options = { matchCase: false, entireCell: false, regex: false };
  }

  matchCase(value) { this.options.matchCase = value; return this; }

  matchEntireCell(value) { this.options.entireCell = value; return this; }

  useRegularExpression(value) { this.options.regex = value; return this; }

  findAll() {
    const flags = this.options.matchCase ? '' : 'i';
    let pattern;
    if (this.options.regex) {
      pattern = this.options.entireCell ? '^(?:' + this.text + ')$' : this.text;
    } else {
      const escaped = this.text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      pattern = this.options.entireCell ? '^' + escaped + '$' : escaped;
    }
    const matcher = new RegExp(pattern, flags);
    const found = [];
    const { sheet, row, column, numRows, numColumns } = this.range;
    for (let r = 0; r < numRows; r++) {
      for (let c = 0; c < numColumns; c++) {
        const value = sheet.cell(row + r, column + c);
        if (value === '') continue;
        if (matcher.test(String(value))) {
          found.push(new FakeRange(sheet, row + r, column + c, 1, 1));
        }
      }
    }
    return found;
  }
}

class FakeSpreadsheet {
  constructor(id, name) {
    this.id = id;
    this.name = name;
    this.sheets = [new FakeSheet('Página1')];
  }

  getId() { return this.id; }

  getUrl() { return 'https://docs.google.com/spreadsheets/d/' + this.id; }

  getSheets() { return this.sheets.slice(); }

  getSheetByName(name) { return this.sheets.find(sheet => sheet.name === name) || null; }

  insertSheet(name) {
    const sheet = new FakeSheet(name);
    this.sheets.push(sheet);
    return sheet;
  }

  deleteSheet(sheet) {
    this.sheets = this.sheets.filter(candidate => candidate !== sheet);
  }
}

function toSignedBytes(buffer) {
  return Array.from(buffer, byte => (byte > 127 ? byte - 256 : byte));
}

function toBuffer(bytes) {
  return Buffer.from(bytes.map(byte => byte & 0xff));
}

/**
 * Cria um contexto isolado com Code.gs carregado. `tokens` mapeia ID tokens de
 * teste para a resposta do endpoint tokeninfo do Google.
 */
function createEnvironment() {
  const spreadsheets = new Map();
  const properties = new Map();
  const cache = new Map();
  const tokens = new Map();
  const state = { lockAvailable: true, urlFetches: 0, lockHeld: false };

  const SpreadsheetApp = {
    create(name) {
      const book = new FakeSpreadsheet('sheet-' + (spreadsheets.size + 1), name);
      spreadsheets.set(book.id, book);
      return book;
    },
    openById(id) {
      const book = spreadsheets.get(id);
      if (!book) throw new Error('Planilha inexistente: ' + id);
      return book;
    },
    flush() {}
  };

  const PropertiesService = {
    getScriptProperties() {
      return {
        getProperty: key => (properties.has(key) ? properties.get(key) : null),
        setProperty(key, value) { properties.set(key, String(value)); return this; },
        deleteProperty(key) { properties.delete(key); return this; }
      };
    }
  };

  const CacheService = {
    getScriptCache() {
      return {
        get: key => (cache.has(key) ? cache.get(key) : null),
        put(key, value) {
          if (key.length > 250) throw new Error('Chave de cache longa demais');
          cache.set(key, String(value));
        },
        remove(key) { cache.delete(key); }
      };
    }
  };

  const LockService = {
    getScriptLock() {
      return {
        tryLock() {
          if (!state.lockAvailable || state.lockHeld) return false;
          state.lockHeld = true;
          return true;
        },
        releaseLock() { state.lockHeld = false; }
      };
    }
  };

  const Utilities = {
    getUuid: () => crypto.randomUUID(),
    computeHmacSha256Signature(value, key) {
      return toSignedBytes(crypto.createHmac('sha256', Buffer.from(key, 'utf8'))
        .update(value, 'utf8')
        .digest());
    },
    base64EncodeWebSafe(bytes) {
      return toBuffer(bytes).toString('base64').replace(/\+/g, '-').replace(/\//g, '_');
    },
    base64DecodeWebSafe(text) {
      return toSignedBytes(Buffer.from(text.replace(/-/g, '+').replace(/_/g, '/'), 'base64'));
    },
    newBlob(bytes) {
      return { getDataAsString: () => toBuffer(bytes).toString('utf8') };
    }
  };

  const UrlFetchApp = {
    fetch(url) {
      state.urlFetches++;
      const token = decodeURIComponent(url.split('id_token=')[1] || '');
      const info = tokens.get(token);
      return {
        getResponseCode: () => (info ? 200 : 400),
        getContentText: () => JSON.stringify(info || { error: 'invalid_token' })
      };
    }
  };

  const ContentService = {
    MimeType: { JSON: 'application/json' },
    createTextOutput(text) {
      return {
        mimeType: null,
        setMimeType(type) { this.mimeType = type; return this; },
        getContent: () => text
      };
    }
  };

  const logs = [];
  const context = vm.createContext({
    SpreadsheetApp,
    PropertiesService,
    CacheService,
    LockService,
    Utilities,
    UrlFetchApp,
    ContentService,
    console: {
      log: message => logs.push(['log', message]),
      warn: message => logs.push(['warn', message]),
      error: message => logs.push(['error', message])
    }
  });

  const source = fs.readFileSync(path.join(__dirname, '..', 'Code.gs'), 'utf8');
  vm.runInContext(source, context, { filename: 'Code.gs' });

  return { context, spreadsheets, properties, cache, tokens, state, logs };
}

module.exports = { createEnvironment };
