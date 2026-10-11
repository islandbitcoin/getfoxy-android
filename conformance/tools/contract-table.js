#!/usr/bin/env node
'use strict';
/* contract-table.js — the closing table of docs/BRIDGE.md, generated from contract.json.
 *
 *     node conformance/tools/contract-table.js           # print it
 *     node conformance/tools/contract-table.js --write   # rewrite it in docs/BRIDGE.md
 *     node conformance/tools/contract-table.js --check   # exit 1 if docs/BRIDGE.md is out of date
 *
 * The table sits between <!-- contract-table:begin --> and <!-- contract-table:end -->.
 * contract.json is the source; the doc follows it, and the suite fails when it does not.
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..', '..');
const DOC = path.join(ROOT, 'docs', 'BRIDGE.md');
const BEGIN = '<!-- contract-table:begin -->';
const END = '<!-- contract-table:end -->';

function cell(s) { return String(s).replace(/\|/g, '\\|').replace(/\n/g, ' '); }

function schemaText(schema) {
  const f = (schema && schema.fields) || {};
  const keys = Object.keys(f).filter((k) => k !== 'action' && k !== 'id');
  return keys.length ? keys.map((k) => '`' + k + '`: ' + f[k]).join(', ') : '—';
}

function replyText(r) {
  if (!r || r.format === 'none') return 'none';
  if (r.format === 'json' || r.format === 'json-or-text') return r.format + ' {' + Object.keys((r.json && r.json.fields) || {}).join(', ') + '}' + (r.values ? ' or ' + r.values.map((v) => '"' + v + '"').join('/') : '');
  if (r.format === 'text' && r.values) return r.values.map((v) => '"' + v + '"').join(' / ');
  return r.format;
}

function table(contract) {
  const rows = ['| name | direction | kind | phase | payload | reply | iOS |', '|---|---|---|---|---|---|---|'];
  for (const e of contract) {
    rows.push('| `' + cell(e.name) + '` | ' + (e.direction === 'page->native' ? 'page → native' : 'native → page') + ' | ' + e.kind + ' | ' + e.phase
      + ' | ' + cell(schemaText(e.payloadSchema)) + ' | ' + cell(replyText(e.reply)) + ' | ' + cell((e.iosRef[0] || '').replace(/@.*$/, '')) + ' |');
  }
  return rows.join('\n');
}

function render(contract, doc) {
  const a = doc.indexOf(BEGIN), b = doc.indexOf(END);
  if (a < 0 || b < a) throw new Error('docs/BRIDGE.md has no ' + BEGIN + ' … ' + END + ' block');
  return doc.slice(0, a + BEGIN.length) + '\n' + table(contract) + '\n' + doc.slice(b);
}

function upToDate() {
  const contract = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'contract.json'), 'utf8'));
  const doc = fs.readFileSync(DOC, 'utf8');
  return render(contract, doc) === doc;
}

module.exports = { table, render, upToDate };

if (require.main === module) {
  const contract = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'contract.json'), 'utf8'));
  const arg = process.argv[2];
  if (arg === '--write') {
    fs.writeFileSync(DOC, render(contract, fs.readFileSync(DOC, 'utf8')));
    console.log('docs/BRIDGE.md: table rewritten, ' + contract.length + ' rows');
  } else if (arg === '--check') {
    if (!upToDate()) { console.error('docs/BRIDGE.md: the contract table is out of date; run node conformance/tools/contract-table.js --write'); process.exit(1); }
    console.log('docs/BRIDGE.md: the contract table matches contract.json');
  } else {
    console.log(table(contract));
  }
}
