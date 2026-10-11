'use strict';
/* refs.js — every iOS file:line in contract.json, held to the pinned commit.
 *
 * A ref is "path:line" or "path:start-end", optionally followed by "@token":
 * the token must appear on that line, or somewhere in that range. Without a
 * token, the line (or range) must merely exist. Refs into Web/ are read from
 * core/web, which IS Web/ at the pinned commit (tools/verify-core.sh), so they
 * are checked on every run. Every other ref (Foxy/*.swift, build/*.js) needs a
 * clone of the iOS repository: set FOXY_IOS_REF to its path and the file is
 * read with `git show <pinned commit>:<path>`, never from a working tree.
 */
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const ROOT = path.join(__dirname, '..', '..');

function pin() {
  const text = fs.readFileSync(path.join(ROOT, 'core', 'PIN'), 'utf8');
  const get = (k) => ((new RegExp('^' + k + '=(.*)$', 'm')).exec(text) || [])[1];
  return { repo: get('repo'), commit: get('commit') };
}

function parse(ref) {
  const m = /^([^:@]+):(\d+)(?:-(\d+))?(?:@(.+))?$/.exec(String(ref));
  if (!m) return null;
  return { file: m[1], from: Number(m[2]), to: Number(m[3] || m[2]), token: m[4] || null };
}

/* A function that returns a file's lines at the pin, or null when it cannot be read here. */
function reader(iosClone) {
  const commit = pin().commit;
  const cache = new Map();
  return (file) => {
    if (cache.has(file)) return cache.get(file);
    let text = null;
    if (file.startsWith('Web/')) {
      const p = path.join(ROOT, 'core', 'web', file.slice(4));
      text = fs.existsSync(p) ? fs.readFileSync(p, 'utf8') : '';
    } else if (iosClone) {
      try { text = execFileSync('git', ['-C', iosClone, 'show', commit + ':' + file], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, stdio: ['ignore', 'pipe', 'ignore'] }); }
      catch (e) { text = ''; }
    }
    const lines = text === null ? null : text.split('\n');
    cache.set(file, lines);
    return lines;
  };
}

/* Check every ref in the contract. Returns { checked, skipped, problems }. */
function checkRefs(contract, iosClone) {
  const read = reader(iosClone);
  const problems = [];
  let checked = 0, skipped = 0;
  for (const e of contract) {
    for (const key of ['iosRef', 'pageRef']) {
      for (const ref of e[key] || []) {
        const r = parse(ref);
        if (!r) { problems.push(e.name + ': "' + ref + '" is not path:line[-end][@token]'); continue; }
        const lines = read(r.file);
        if (lines === null) { skipped++; continue; }
        if (!lines.length || (lines.length === 1 && lines[0] === '')) { problems.push(e.name + ': ' + r.file + ' does not exist at the pinned commit'); continue; }
        if (r.from < 1 || r.to < r.from || r.to > lines.length) { problems.push(e.name + ': ' + ref + ' is past the end of ' + r.file + ' (' + lines.length + ' lines)'); continue; }
        if (r.token) {
          const span = lines.slice(r.from - 1, r.to).join('\n');
          if (!span.includes(r.token)) problems.push(e.name + ': ' + ref + ' — "' + r.token + '" is not on ' + (r.from === r.to ? 'that line' : 'those lines') + ' (' + lines[r.from - 1].trim().slice(0, 100) + ')');
        }
        checked++;
      }
    }
  }
  return { checked, skipped, problems };
}

module.exports = { checkRefs, parse, pin };
