#!/usr/bin/env node
'use strict';
/* leak-check.js — did Foxy's traffic leave anywhere but Tor? (the P2 leak test)
 *
 * The capture comes from an Android emulator started with `-tcpdump <file>`
 * (every packet of the guest's network, before the emulator's NAT), while the
 * app is driven through one transport. Which flows are Foxy's comes from its
 * sockets, sampled from /proc/net/{tcp,tcp6,udp,udp6} by uid while it ran
 * (tools/leak-test.sh). Every non-loopback flow of Foxy's must be, by mode:
 *
 *   direct       to a Tor relay (the consensus Tor itself downloaded);
 *   obfs4        to one of the seven built-in obfs4 bridges;
 *   snowflake    to the Snowflake broker's CDN front or a STUN server (by the
 *                names DNS answered for them), or UDP to a volunteer proxy:
 *                outside Tor by design (docs/ROUTE.md §3);
 *   unprotected  to a Tor relay, or, only after the person chose CONTINUE
 *                WITHOUT TOR (--after), to the phone's resolver or an address
 *                DNS gave for one of Foxy's names (the mints, the price
 *                sources, the explorers by name; --allow narrows the list):
 *                from then on everything goes on the open connection, by
 *                the person's choice, and an onion still never does.
 *
 * And, whoever asked: no DNS query for a name Foxy uses (the mint, the price
 * sources, the explorers, any .onion), except after the opt-out, and never an
 * onion; and no Snowflake name outside snowflake mode. A flow nobody's socket table showed
 * (the OS's, or one too short to be sampled) is reported, and is a leak when it
 * goes to an address DNS gave for one of Foxy's names.
 *
 *   node tools/leak-check.js --pcap run.pcap --sockets sockets.txt --uid 10234 \
 *        --mode direct --consensus cached-microdesc-consensus [--after-marker <name> | --after <s>] [--json]
 *
 * The opt-out's moment is best given as a name the runner looked up just
 * before the tap (--after-marker): its query's time in the capture is the
 * moment, on the capture's own clock, which an emulator does not keep in step
 * with the host's.
 *
 * Exit 0: no leak. Exit 1: a leak (each one listed). Exit 2: bad input.
 */
const fs = require('fs');

const OBFS4_BRIDGES = ['45.145.95.6', '212.83.43.95', '51.222.13.177', '37.218.245.14', '212.83.43.74', '209.148.46.65', '146.57.248.225'];
const SNOWFLAKE_NAMES = ['1098762253.rsc.cdn77.org', 'app.datapacket.com', 'www.datapacket.com', 'stun.epygi.com', 'stun.uls.co.za',
  'stun.voipgate.com', 'stun.mixvoip.com', 'stun.telnyx.com', 'stun.hot-chilli.net', 'stun.fitauto.ru', 'stun.m-online.net'];
/* Names Foxy reaches only through Tor (or, after the opt-out, the open connection). */
const FOXY_NAMES = ['mint.minibits.cash', 'mint.westernbtc.com', 'mint.macadamia.cash', 'nofee.testnut.cashu.space', 'testnut.cashu.space',
  'api.kraken.com', 'www.bitstamp.net', 'blockchain.info', 'api.gemini.com', 'api.coinbase.com', 'mempool.space', 'blockstream.info'];
const MODES = ['direct', 'obfs4', 'snowflake', 'unprotected'];

// MARK: pcap

/* Packets of a libpcap file: { t (s), proto 'tcp'|'udp', src, dst, sport, dport, syn, payload } for IPv4/IPv6 TCP and UDP. */
function readPcap(buf) {
  if (buf.length < 24) throw new Error('not a pcap file');
  const magic = buf.readUInt32LE(0);
  let le;
  let nano = false;
  if (magic === 0xa1b2c3d4) le = true;
  else if (magic === 0xd4c3b2a1) le = false;
  else if (magic === 0xa1b23c4d) { le = true; nano = true; }
  else if (magic === 0x4d3cb2a1) { le = false; nano = true; }
  else throw new Error('not a pcap file (pcapng is not read; capture with the emulator\'s -tcpdump)');
  const u32 = (o) => (le ? buf.readUInt32LE(o) : buf.readUInt32BE(o));
  const link = u32(20);
  const out = [];
  let off = 24;
  while (off + 16 <= buf.length) {
    const sec = u32(off), frac = u32(off + 4), incl = u32(off + 8);
    const t = sec + frac / (nano ? 1e9 : 1e6);
    const data = buf.subarray(off + 16, Math.min(buf.length, off + 16 + incl));
    off += 16 + incl;
    const p = parseLink(link, data);
    if (p) { p.t = t; out.push(p); }
  }
  return out;
}

function parseLink(link, d) {
  let o, type;
  if (link === 1) { // Ethernet
    if (d.length < 14) return null;
    type = d.readUInt16BE(12); o = 14;
    while (type === 0x8100 || type === 0x88a8) { if (d.length < o + 4) return null; type = d.readUInt16BE(o + 2); o += 4; }
  } else if (link === 113) { // Linux cooked
    if (d.length < 16) return null;
    type = d.readUInt16BE(14); o = 16;
  } else if (link === 101 || link === 228 || link === 229 || link === 12) { // raw IP
    if (d.length < 1) return null;
    type = (d[0] >> 4) === 6 ? 0x86dd : 0x0800; o = 0;
  } else return null;
  if (type === 0x0800) return parseIPv4(d, o);
  if (type === 0x86dd) return parseIPv6(d, o);
  return null;
}

function parseIPv4(d, o) {
  if (d.length < o + 20) return null;
  const ihl = (d[o] & 0x0f) * 4;
  const proto = d[o + 9];
  const src = [...d.subarray(o + 12, o + 16)].join('.');
  const dst = [...d.subarray(o + 16, o + 20)].join('.');
  return parseL4(proto, src, dst, d, o + ihl);
}

function v6(b) {
  const parts = [];
  for (let i = 0; i < 16; i += 2) parts.push(((b[i] << 8) | b[i + 1]).toString(16));
  return compress6(parts.join(':'));
}

function compress6(full) {
  // ::ffff:a.b.c.d is the IPv4 it maps
  const p = full.split(':');
  if (p.slice(0, 5).every((x) => x === '0') && p[5] === 'ffff') {
    const a = parseInt(p[6], 16), b = parseInt(p[7], 16);
    return [a >> 8, a & 255, b >> 8, b & 255].join('.');
  }
  return p.map((x) => x.replace(/^0+(?=.)/, '')).join(':');
}

function parseIPv6(d, o) {
  if (d.length < o + 40) return null;
  let next = d[o + 6];
  const src = v6(d.subarray(o + 8, o + 24));
  const dst = v6(d.subarray(o + 24, o + 40));
  let p = o + 40;
  // hop-by-hop, routing, destination options
  while ((next === 0 || next === 43 || next === 60) && d.length >= p + 8) {
    const n = d[p];
    p += (d[p + 1] + 1) * 8;
    next = n;
  }
  return parseL4(next, src, dst, d, p);
}

function parseL4(proto, src, dst, d, p) {
  if (proto === 6) {
    if (d.length < p + 20) return null;
    const off = (d[p + 12] >> 4) * 4;
    const flags = d[p + 13];
    return { proto: 'tcp', src, dst, sport: d.readUInt16BE(p), dport: d.readUInt16BE(p + 2), syn: (flags & 0x02) !== 0 && (flags & 0x10) === 0, payload: d.subarray(p + off) };
  }
  if (proto === 17) {
    if (d.length < p + 8) return null;
    return { proto: 'udp', src, dst, sport: d.readUInt16BE(p), dport: d.readUInt16BE(p + 2), syn: false, payload: d.subarray(p + 8) };
  }
  return null;
}

// MARK: DNS

/* A DNS message: { response, questions: [name], answers: [{ name, address }] }; null when it is not one. */
function parseDns(b) {
  try {
    if (b.length < 12) return null;
    const flags = b.readUInt16BE(2);
    const qd = b.readUInt16BE(4), an = b.readUInt16BE(6);
    let o = 12;
    const name = () => {
      const labels = [];
      let p = o, jumped = false, hops = 0;
      for (;;) {
        if (p >= b.length || ++hops > 128) throw new Error('bad name');
        const len = b[p];
        if (len === 0) { p += 1; break; }
        if ((len & 0xc0) === 0xc0) {
          if (!jumped) o = p + 2;
          p = ((len & 0x3f) << 8) | b[p + 1];
          jumped = true;
          continue;
        }
        labels.push(b.subarray(p + 1, p + 1 + len).toString('latin1'));
        p += 1 + len;
      }
      if (!jumped) o = p;
      return labels.join('.').toLowerCase();
    };
    const questions = [];
    for (let i = 0; i < qd; i++) { questions.push(name()); o += 4; }
    const answers = [];
    for (let i = 0; i < an; i++) {
      const n = name();
      const type = b.readUInt16BE(o), len = b.readUInt16BE(o + 8);
      const rd = b.subarray(o + 10, o + 10 + len);
      if (type === 1 && len === 4) answers.push({ name: n, address: [...rd].join('.') });
      if (type === 28 && len === 16) answers.push({ name: n, address: v6(rd) });
      o += 10 + len;
    }
    return { response: (flags & 0x8000) !== 0, questions, answers };
  } catch (e) {
    return null;
  }
}

// MARK: Foxy's sockets

function hexAddress(h) {
  if (h.length === 8) {
    const n = parseInt(h, 16);
    return [n & 255, (n >>> 8) & 255, (n >>> 16) & 255, (n >>> 24) & 255].join('.');
  }
  // /proc/net/*6: four little-endian 32-bit words
  const bytes = [];
  for (let w = 0; w < 4; w++) {
    const word = h.slice(w * 8, w * 8 + 8);
    for (let i = 3; i >= 0; i--) bytes.push(parseInt(word.slice(i * 2, i * 2 + 2), 16));
  }
  return v6(Buffer.from(bytes));
}

/*
 * The sockets a uid held: lines of /proc/net/{tcp,tcp6,udp,udp6}, as the
 * sampler writes them ("<proto> <the /proc line>"); the header lines are
 * skipped. { proto, local, lport, remote, rport }.
 */
function readSockets(text, uid) {
  const out = [];
  for (const raw of text.split('\n')) {
    const m = raw.trim().match(/^(tcp6?|udp6?)\s+\d+:\s+([0-9A-F]+):([0-9A-F]{4})\s+([0-9A-F]+):([0-9A-F]{4})\s+[0-9A-F]{2}\s+\S+\s+\S+\s+\S+\s+(\d+)/i);
    if (!m || Number(m[6]) !== Number(uid)) continue;
    out.push({ proto: m[1].replace('6', ''), local: hexAddress(m[2]), lport: parseInt(m[3], 16), remote: hexAddress(m[4]), rport: parseInt(m[5], 16) });
  }
  return out;
}

/* The relays' addresses in a consensus (microdesc or full): every "r" line's IPv4, and "a" lines' IPv6. */
function readConsensus(text) {
  const out = new Set();
  for (const line of text.split('\n')) {
    let m = line.match(/^r \S+ \S+ (?:\S+ )?\d{4}-\d\d-\d\d \d\d:\d\d:\d\d (\d+\.\d+\.\d+\.\d+) \d+ \d+$/);
    if (m) { out.add(m[1]); continue; }
    m = line.match(/^a \[([0-9a-fA-F:]+)\]:\d+$/);
    if (m) out.add(compress6(expand6(m[1])));
    m = line.match(/^dir-source \S+ \S+ \S+ (\d+\.\d+\.\d+\.\d+) \d+ \d+$/);
    if (m) out.add(m[1]);
  }
  return out;
}

function expand6(a) {
  const [head, tail] = a.split('::');
  const h = head ? head.split(':') : [];
  const t = tail !== undefined && tail !== '' ? tail.split(':') : [];
  const fill = tail !== undefined ? Array(8 - h.length - t.length).fill('0') : [];
  return [...h, ...fill, ...t].map((x) => x.toLowerCase()).join(':');
}

const loopback = (a) => a.startsWith('127.') || a === '::1' || a === '0.0.0.0' || a === '::';
/* The guest's side of the emulator's network (its own and its gateway's addresses), and multicast. */
const local = (a) => /^10\./.test(a) || /^192\.168\./.test(a) || /^172\.(1[6-9]|2\d|3[01])\./.test(a) || /^fe80:/i.test(a) || /^(22[4-9]|23\d)\./.test(a) || /^ff/i.test(a);
const nameIn = (n, list) => list.some((x) => n === x || n.endsWith('.' + x));

// MARK: the check

function check(opts) {
  const { packets, sockets, mode, relays } = opts;
  const after = opts.after === undefined ? Infinity : opts.after;
  const allowedAfter = opts.allow || FOXY_NAMES;
  if (!MODES.includes(mode)) throw new Error('mode is one of ' + MODES.join(', '));
  const leaks = [];
  const notes = [];

  // what DNS said, and who asked what
  const resolved = new Map(); // address -> Set(name)
  const queries = [];
  for (const p of packets) {
    if (p.proto !== 'udp' && p.proto !== 'tcp') continue;
    if (p.sport !== 53 && p.dport !== 53) continue;
    const msg = parseDns(p.proto === 'tcp' ? p.payload.subarray(2) : p.payload);
    if (!msg) continue;
    if (!msg.response) for (const q of msg.questions) queries.push({ t: p.t, name: q });
    for (const a of msg.answers) {
      if (!resolved.has(a.address)) resolved.set(a.address, new Set());
      resolved.get(a.address).add(a.name);
      for (const q of msg.questions) resolved.get(a.address).add(q);
    }
  }
  const namesOf = (addr) => [...(resolved.get(addr) || [])];
  for (const q of queries) {
    if (q.name.endsWith('.onion')) leaks.push({ kind: 'dns', what: 'an onion name was looked up outside Tor: ' + q.name, t: q.t });
    else if (nameIn(q.name, FOXY_NAMES)) {
      if (mode === 'unprotected' && q.t >= after && nameIn(q.name, allowedAfter)) notes.push('after the opt-out, the open connection looked up ' + q.name);
      else leaks.push({ kind: 'dns', what: 'one of Foxy\'s names was looked up outside Tor: ' + q.name, t: q.t });
    } else if (nameIn(q.name, SNOWFLAKE_NAMES)) {
      if (mode !== 'snowflake') leaks.push({ kind: 'dns', what: 'a Snowflake name outside snowflake mode: ' + q.name, t: q.t });
    }
  }

  // Foxy's flows: the sampled sockets, and every packet from one of their local addresses and ports.
  // A loopback socket (Tor to its transport, the app to Tor's SOCKS port) is never one: its port
  // number is free on the outside, and another process using it there is that process's flow.
  const ours = new Map(); // proto:port -> Set(local address)
  for (const s of sockets) {
    if (loopback(s.local)) continue;
    const k = s.proto + ':' + s.lport;
    if (!ours.has(k)) ours.set(k, new Set());
    ours.get(k).add(s.local);
  }
  const isOurs = (proto, port, address) => {
    const at = ours.get(proto + ':' + port);
    return !!at && (at.has(address) || at.has('0.0.0.0') || at.has('0:0:0:0:0:0:0:0'));
  };
  const flows = new Map(); // key -> { proto, lport, remote, rport, first }
  const add = (proto, lport, remote, rport, t, attributed) => {
    if (loopback(remote)) return;
    const key = proto + ' ' + lport + ' ' + remote + ':' + rport;
    if (!flows.has(key)) flows.set(key, { proto, lport, remote, rport, first: t, attributed });
  };
  for (const s of sockets) {
    if (s.proto === 'tcp' && s.rport === 0) continue; // a listener
    if (s.proto === 'udp' && (loopback(s.remote) || s.rport === 0)) continue; // an unconnected UDP socket: seen in the capture
    add(s.proto, s.lport, s.remote, s.rport, -Infinity, true);
  }
  const outbound = (p) => p.syn || p.proto === 'udp';
  const unattributed = new Map();
  for (const p of packets) {
    if (loopback(p.src) || loopback(p.dst)) continue;
    // the guest's own address is the source of what it sends
    const mineSrc = isOurs(p.proto, p.sport, p.src);
    if (mineSrc && outbound(p)) { add(p.proto, p.sport, p.dst, p.dport, p.t, true); continue; }
    if (p.sport === 53 || p.dport === 53) continue;
    if (outbound(p) && !local(p.dst) && !isOurs(p.proto, p.dport, p.dst)) {
      const key = p.proto + ' ' + p.dst + ':' + p.dport;
      if (!unattributed.has(key)) unattributed.set(key, { proto: p.proto, remote: p.dst, rport: p.dport, first: p.t });
    }
  }
  // a flow's first packet time, when the capture has it
  for (const f of flows.values()) {
    if (f.first !== -Infinity) continue;
    const p = packets.find((x) => x.sport === f.lport && x.dst === f.remote && x.dport === f.rport);
    if (p) f.first = p.t;
  }

  const verdict = (f) => {
    const names = namesOf(f.remote);
    if (relays.has(f.remote)) {
      if (mode === 'obfs4' || mode === 'snowflake') return 'a Tor relay reached directly in ' + mode + ' mode';
      return null;
    }
    if (mode === 'obfs4' && OBFS4_BRIDGES.includes(f.remote)) return null;
    if (mode === 'snowflake') {
      if (names.some((n) => nameIn(n, SNOWFLAKE_NAMES))) return null;
      if (f.proto === 'udp') return null; // WebRTC to a volunteer proxy, or STUN by address
    }
    if (mode === 'unprotected' && f.first >= after && (f.rport === 53 || names.some((n) => nameIn(n, allowedAfter)))) return null;
    if (mode === 'unprotected' && (f.rport === 53 || names.some((n) => nameIn(n, allowedAfter)))) return 'reached before the person chose to continue without Tor';
    return 'not Tor, not a bridge, not by design' + (names.length ? ' (DNS: ' + names.join(', ') + ')' : '');
  };
  const report = [];
  for (const f of flows.values()) {
    const why = verdict(f);
    report.push({ ...f, ok: !why, why: why || 'ok' });
    if (why) leaks.push({ kind: 'flow', what: f.proto + ' to ' + f.remote + ':' + f.rport + ' — ' + why, t: f.first });
  }
  const others = [];
  for (const u of unattributed.values()) {
    const names = namesOf(u.remote);
    const foxy = names.filter((n) => nameIn(n, FOXY_NAMES));
    const afterOptOut = mode === 'unprotected' && u.first >= after && foxy.every((n) => nameIn(n, allowedAfter));
    if (foxy.length && !afterOptOut) leaks.push({ kind: 'flow', what: 'a flow nobody\'s sockets showed, to an address of ' + foxy.join(', ') + ': ' + u.proto + ' ' + u.remote + ':' + u.rport, t: u.first });
    others.push({ ...u, names });
  }
  return { mode, leaks, flows: report, others, queries: queries.map((q) => q.name), notes };
}

/* The names a packet's DNS question asks, if it is one. */
function L_parseDnsNames(p) {
  const m = parseDns(p.proto === 'tcp' ? p.payload.subarray(2) : p.payload);
  return m ? m.questions : [];
}

function main(argv) {
  const a = {};
  for (let i = 0; i < argv.length; i++) {
    if (argv[i].startsWith('--')) {
      const k = argv[i].slice(2);
      if (k === 'json') a.json = true;
      else a[k] = argv[++i];
    }
  }
  for (const k of ['pcap', 'sockets', 'uid', 'mode', 'consensus']) {
    if (!a[k]) { process.stderr.write('leak-check: --' + k + ' is needed\n'); return 2; }
  }
  const packets = readPcap(fs.readFileSync(a.pcap));
  const sockets = readSockets(fs.readFileSync(a.sockets, 'utf8'), a.uid);
  const relays = readConsensus(fs.readFileSync(a.consensus, 'utf8'));
  if (relays.size < 100) { process.stderr.write('leak-check: the consensus lists ' + relays.size + ' relays; that is not a consensus\n'); return 2; }
  let after = a.after === undefined ? undefined : Number(a.after);
  if (a['after-marker']) {
    const marked = packets.find((p) => (p.dport === 53) && (L_parseDnsNames(p).includes(a['after-marker'].toLowerCase())));
    if (!marked) { process.stderr.write('leak-check: no lookup of ' + a['after-marker'] + ' in the capture\n'); return 2; }
    after = marked.t;
  }
  const r = check({ packets, sockets, mode: a.mode, relays, after,
    allow: a.allow ? a.allow.split(',') : undefined });
  if (a.json) process.stdout.write(JSON.stringify(r, null, 2) + '\n');
  else {
    process.stdout.write('leak-check ' + r.mode + (after !== undefined ? ' (opt-out at ' + after + ' on the capture\'s clock)' : '') + ': ' + packets.length + ' packets, ' + sockets.length + ' socket rows of uid ' + a.uid + ', '
      + r.flows.length + ' flows of Foxy\'s, ' + r.others.length + ' of others, ' + r.queries.length + ' DNS queries\n');
    for (const f of r.flows) process.stdout.write('  ' + (f.ok ? 'ok   ' : 'LEAK ') + f.proto + ' ' + f.remote + ':' + f.rport + (f.ok ? '' : ' — ' + f.why) + '\n');
    for (const n of r.notes) process.stdout.write('  note: ' + n + '\n');
    for (const l of r.leaks) process.stdout.write('LEAK: ' + l.what + '\n');
    process.stdout.write(r.leaks.length ? 'leak-check: ' + r.leaks.length + ' leak(s)\n' : 'leak-check: no leak\n');
  }
  return r.leaks.length ? 1 : 0;
}

module.exports = { readPcap, parseDns, readSockets, readConsensus, check, hexAddress, main, OBFS4_BRIDGES, SNOWFLAKE_NAMES, FOXY_NAMES };

if (require.main === module) process.exitCode = main(process.argv.slice(2));
