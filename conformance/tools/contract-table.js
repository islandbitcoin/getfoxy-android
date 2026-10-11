#!/usr/bin/env node
'use strict';
/* contract-table.js — the generated parts of docs/BRIDGE.md, from contract.json.
 *
 *     node conformance/tools/contract-table.js           # print the closing table
 *     node conformance/tools/contract-table.js --write   # rewrite both generated blocks in docs/BRIDGE.md
 *     node conformance/tools/contract-table.js --check   # exit 1 if docs/BRIDGE.md is out of date
 *
 * Two blocks, each between its markers:
 *   <!-- contract-detail:begin --> … <!-- contract-detail:end -->  every entry, by domain
 *   <!-- contract-table:begin -->  … <!-- contract-table:end -->   the machine-readable summary
 *
 * contract.json is the source; the doc follows it, and the suite fails when it
 * does not (test/contract.test.js).
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..', '..');
const DOC = path.join(ROOT, 'docs', 'BRIDGE.md');
const CONTRACT_FILE = path.join(__dirname, '..', 'contract.json');

/* The order and the headings the domains appear under. */
const DOMAINS = [
  ['transport', 'The reply path'],
  ['lifecycle', 'Lifecycle, splash and app-switcher cover'],
  ['debug', 'Debug and the field log'],
  ['tor', 'Tor gate'],
  ['network', 'Network (Route)'],
  ['seed', 'Seed and keychain'],
  ['counters', 'Counters, NUT-13 secrets and P2PK lock keys'],
  ['clipboard', 'Clipboard, paste and share'],
  ['scanner', 'Scanner'],
  ['haptics', 'Haptics'],
  ['notifications', 'Notifications'],
  ['platform', 'Other platform doors'],
  ['onion', 'Onion inbox and post (LATER)'],
  ['nostr', 'Nostr (LATER)'],
  ['bluetooth', 'Bluetooth tap to pay (LATER)'],
  ['card', 'NFC card (LATER)'],
];

const cell = (s) => String(s).replace(/\|/g, '\\|').replace(/\n/g, ' ');
const refText = (r) => '`' + String(r).replace(/@.*$/, '') + '`';

function schemaText(schema) {
  const f = (schema && schema.fields) || {};
  const keys = Object.keys(f).filter((k) => k !== 'action' && k !== 'id');
  return keys.length ? keys.map((k) => '`' + k + '`: ' + f[k]).join(', ') : '—';
}

function replyText(r) {
  if (!r || r.format === 'none') return 'none';
  if (r.format === 'json' || r.format === 'json-or-text') {
    return r.format + ' {' + Object.entries((r.json && r.json.fields) || {}).map(([k, t]) => k + ': ' + t).join(', ') + '}'
      + (r.values ? ' or ' + r.values.map((v) => '"' + v + '"').join(' / ') : '');
  }
  if (r.format === 'json-array') return 'JSON array of ' + (typeof r.items === 'string' ? r.items : '{' + Object.entries(r.items.fields).map(([k, t]) => k + ': ' + t).join(', ') + '}');
  if (r.format === 'text' && r.values) return r.values.map((v) => '"' + v + '"').join(' / ');
  if (r.format === 'status-body') return '"<HTTP status>\\n<body>"';
  return r.format + (r.note ? ' (' + r.note + ')' : '');
}

function table(contract) {
  const rows = ['| name | direction | kind | phase | payload | reply | iOS |', '|---|---|---|---|---|---|---|'];
  for (const e of contract) {
    rows.push('| `' + cell(e.name) + '` | ' + (e.direction === 'page->native' ? 'page → native' : 'native → page') + ' | ' + e.kind + ' | ' + e.phase
      + ' | ' + cell(schemaText(e.payloadSchema)) + ' | ' + cell(replyText(e.reply)) + ' | ' + cell((e.iosRef[0] || '').replace(/@.*$/, '')) + ' |');
  }
  return rows.join('\n');
}

function shape(e) {
  if (e.kind === 'action') return '`{ action: "' + e.name + '", id' + Object.keys(e.payloadSchema.fields).filter((k) => k !== 'action' && k !== 'id').map((k) => ', ' + k).join('') + ' }`';
  if (e.kind === 'message') return '`{ ' + Object.keys(e.payloadSchema.fields).join(', ') + ' }` (no action, no id)';
  return '`' + (e.target || e.name) + '`';
}

function detail(contract) {
  const out = [];
  const known = new Set(DOMAINS.map(([d]) => d));
  for (const e of contract) if (!known.has(e.domain)) throw new Error(e.name + ': domain "' + e.domain + '" has no heading');
  for (const [domain, heading] of DOMAINS) {
    const list = contract.filter((e) => e.domain === domain);
    if (!list.length) continue;
    out.push('### ' + heading, '');
    for (const e of list) {
      out.push('#### `' + e.name + '` — ' + e.phase, '');
      out.push('- **Shape:** ' + (e.direction === 'page->native' ? 'page → native, ' : 'native → page, ') + e.kind + ': ' + shape(e));
      if (e.kind !== 'script') out.push('- **Payload:** ' + schemaText(e.payloadSchema));
      out.push('- **Called from:** ' + (e.pageRef.length ? e.pageRef.map(refText).join(', ') : (e.direction === 'page->native' ? 'nothing in the page at the pinned commit' : 'native')));
      out.push('- **Native:** ' + e.behaviour);
      if (e.reply.format !== 'none') {
        out.push('- **Reply:** ' + e.reply.via + ' — ' + replyText(e.reply) + (e.reply.note && e.reply.format !== 'text' ? ' (' + e.reply.note + ')' : (e.reply.note ? '' : '')));
        if (e.reply.format === 'text' && e.reply.note) out.push('  The text is ' + e.reply.note + '.');
        if (e.reply.errors && e.reply.errors.length) out.push('- **Errors:** ' + e.reply.errors.map((x) => '"' + x + '"').join('; '));
      }
      if (e.timing) out.push('- **Timing:** ' + e.timing);
      out.push('- **iOS:** ' + e.iosRef.map(refText).join(', '));
      out.push('');
    }
  }
  return out.join('\n').replace(/\n+$/, '');
}

function replaceBlock(doc, name, body) {
  const BEGIN = '<!-- ' + name + ':begin -->', END = '<!-- ' + name + ':end -->';
  const a = doc.indexOf(BEGIN), b = doc.indexOf(END);
  if (a < 0 || b < a) throw new Error('docs/BRIDGE.md has no ' + BEGIN + ' … ' + END + ' block');
  return doc.slice(0, a + BEGIN.length) + '\n' + body + '\n' + doc.slice(b);
}

function render(contract, doc) {
  return replaceBlock(replaceBlock(doc, 'contract-detail', detail(contract)), 'contract-table', table(contract));
}

const load = () => JSON.parse(fs.readFileSync(CONTRACT_FILE, 'utf8'));

function upToDate() {
  const doc = fs.readFileSync(DOC, 'utf8');
  return render(load(), doc) === doc;
}

module.exports = { table, detail, render, upToDate };

if (require.main === module) {
  const contract = load();
  const arg = process.argv[2];
  if (arg === '--write') {
    fs.writeFileSync(DOC, render(contract, fs.readFileSync(DOC, 'utf8')));
    console.log('docs/BRIDGE.md: generated blocks rewritten, ' + contract.length + ' entries');
  } else if (arg === '--check') {
    if (!upToDate()) { console.error('docs/BRIDGE.md is out of date with contract.json; run node conformance/tools/contract-table.js --write'); process.exit(1); }
    console.log('docs/BRIDGE.md matches contract.json');
  } else {
    console.log(table(contract));
  }
}
