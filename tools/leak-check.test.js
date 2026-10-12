'use strict';
/* leak-check.test.js — the leak test's analysis catches what it is for.
 *
 *   node --test tools/leak-check.test.js
 *
 * Captures are built here, packet by packet (Ethernet, IPv4/IPv6, TCP, UDP,
 * DNS), with the socket table the sampler would have read, and each mode's
 * rule is held to its cases: a relay is fine direct and a leak over a bridge;
 * a bridge is fine over obfs4; Snowflake's names and UDP are fine only in
 * snowflake mode; the mint is fine only after the opt-out; a lookup of one of
 * Foxy's names, or of any onion, is a leak.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const L = require('./leak-check');

const GUEST = '10.0.2.15';
const DNS = '10.0.2.3';

function ipv4(a) { return Buffer.from(a.split('.').map(Number)); }
function ipv6(a) {
  const [h, t] = a.split('::');
  const hp = h ? h.split(':') : [], tp = t !== undefined && t !== '' ? t.split(':') : [];
  const all = [...hp, ...Array(8 - hp.length - tp.length).fill('0'), ...tp];
  const b = Buffer.alloc(16);
  all.forEach((x, i) => b.writeUInt16BE(parseInt(x, 16), i * 2));
  return b;
}

function frame(src, dst, proto, l4) {
  const six = src.includes(':');
  const eth = Buffer.alloc(14);
  eth.writeUInt16BE(six ? 0x86dd : 0x0800, 12);
  let ip;
  if (six) {
    ip = Buffer.alloc(40);
    ip[0] = 0x60;
    ip.writeUInt16BE(l4.length, 4);
    ip[6] = proto;
    ip[7] = 64;
    ipv6(src).copy(ip, 8);
    ipv6(dst).copy(ip, 24);
  } else {
    ip = Buffer.alloc(20);
    ip[0] = 0x45;
    ip.writeUInt16BE(20 + l4.length, 2);
    ip[8] = 64;
    ip[9] = proto;
    ipv4(src).copy(ip, 12);
    ipv4(dst).copy(ip, 16);
  }
  return Buffer.concat([eth, ip, l4]);
}

function tcp(src, sport, dst, dport, syn) {
  const h = Buffer.alloc(20);
  h.writeUInt16BE(sport, 0);
  h.writeUInt16BE(dport, 2);
  h[12] = 5 << 4;
  h[13] = syn ? 0x02 : 0x18;
  return frame(src, dst, 6, h);
}

function udp(src, sport, dst, dport, payload) {
  const h = Buffer.alloc(8);
  h.writeUInt16BE(sport, 0);
  h.writeUInt16BE(dport, 2);
  h.writeUInt16BE(8 + payload.length, 4);
  return frame(src, dst, 17, Buffer.concat([h, payload]));
}

function qname(n) {
  return Buffer.concat([...n.split('.').map((l) => Buffer.concat([Buffer.from([l.length]), Buffer.from(l)])), Buffer.from([0])]);
}

function dnsQuery(name) {
  const h = Buffer.alloc(12);
  h.writeUInt16BE(0x1234, 0);
  h.writeUInt16BE(0x0100, 2);
  h.writeUInt16BE(1, 4);
  return Buffer.concat([h, qname(name), Buffer.from([0, 1, 0, 1])]);
}

function dnsAnswer(name, address) {
  const h = Buffer.alloc(12);
  h.writeUInt16BE(0x1234, 0);
  h.writeUInt16BE(0x8180, 2);
  h.writeUInt16BE(1, 4);
  h.writeUInt16BE(1, 6);
  const rr = Buffer.alloc(12);
  rr.writeUInt16BE(0xc00c, 0); // the question's name
  rr.writeUInt16BE(1, 2);
  rr.writeUInt16BE(1, 4);
  rr.writeUInt32BE(60, 6);
  rr.writeUInt16BE(4, 10);
  return Buffer.concat([h, qname(name), Buffer.from([0, 1, 0, 1]), rr, ipv4(address)]);
}

/* A pcap of [t, frame] pairs. */
function pcap(packets) {
  const g = Buffer.alloc(24);
  g.writeUInt32LE(0xa1b2c3d4, 0);
  g.writeUInt16LE(2, 4);
  g.writeUInt16LE(4, 6);
  g.writeUInt32LE(65535, 16);
  g.writeUInt32LE(1, 20);
  const recs = packets.map(([t, f]) => {
    const r = Buffer.alloc(16);
    r.writeUInt32LE(Math.floor(t), 0);
    r.writeUInt32LE(Math.round((t % 1) * 1e6), 4);
    r.writeUInt32LE(f.length, 8);
    r.writeUInt32LE(f.length, 12);
    return Buffer.concat([r, f]);
  });
  return L.readPcap(Buffer.concat([g, ...recs]));
}

const hex4 = (a) => Buffer.from(a.split('.').map(Number).reverse()).toString('hex').toUpperCase();
const port = (p) => p.toString(16).toUpperCase().padStart(4, '0');
/* A /proc/net/tcp row, as the sampler writes it. */
function row(proto, lport, remote, rport, uid) {
  return proto + '   1: ' + hex4(GUEST) + ':' + port(lport) + ' ' + hex4(remote) + ':' + port(rport) + ' 01 00000000:00000000 00:00000000 00000000 ' + uid + '        0 12345 1 0000000000000000 20 4 30 10 -1';
}

const UID = 10234;
const RELAY = '185.220.101.7';
const relays = new Set([RELAY, '1.2.3.4']);

function run(mode, packets, rows, after) {
  return L.check({ packets: pcap(packets), sockets: L.readSockets(rows.join('\n'), UID), mode, relays, after });
}

test('direct: Foxy\'s flows to Tor relays are fine; anything else is a leak', () => {
  const ok = run('direct', [[1, tcp(GUEST, 40000, RELAY, 443, true)]], [row('tcp', 40000, RELAY, 443, UID)]);
  assert.deepEqual(ok.leaks, []);
  assert.equal(ok.flows.length, 1);
  const bad = run('direct', [[1, tcp(GUEST, 40001, '93.184.216.34', 443, true)]], [row('tcp', 40001, '93.184.216.34', 443, UID)]);
  assert.equal(bad.leaks.length, 1);
  assert.match(bad.leaks[0].what, /93\.184\.216\.34:443 — not Tor/);
});

test('a socket of Foxy\'s too short to sample is still found by its local port in the capture', () => {
  // the sampler saw one socket on 40002; the capture shows a second SYN from it, to somewhere else
  const r = run('direct', [[1, tcp(GUEST, 40002, RELAY, 443, true)], [2, tcp(GUEST, 40002, '8.8.8.8', 443, true)]], [row('tcp', 40002, RELAY, 443, UID)]);
  assert.equal(r.leaks.length, 1);
  assert.match(r.leaks[0].what, /8\.8\.8\.8/);
});

test('a port Foxy holds on loopback is not Foxy\'s on the outside', () => {
  // Tor to its obfs4 transport on 127.0.0.1:40000; another process's connection out from port 40000
  const loopRow = 'tcp   1: 0100007F:' + port(40000) + ' 0100007F:' + port(41001) + ' 01 00000000:00000000 00:00000000 00000000 ' + UID + '        0 1 1 0 20 4 30 10 -1';
  const r = run('obfs4', [[1, tcp(GUEST, 40000, '142.250.1.1', 443, true)]], [loopRow]);
  assert.deepEqual(r.leaks, []);
  assert.equal(r.flows.length, 0);
  assert.equal(r.others.length, 1);
});

test('a UDP socket bound to every address is Foxy\'s on the outside (WebRTC, Snowflake\'s volunteer)', () => {
  const wild = 'udp   313: 00000000:' + port(53351) + ' 00000000:0000 07 00000000:00000000 00:00000000 00000000 ' + UID + '        0 32877 2 0000000000000000 0';
  const r = run('direct', [[1, udp(GUEST, 53351, '83.135.243.222', 17551, Buffer.alloc(40))]], [wild]);
  assert.equal(r.flows.length, 1, 'attributed to Foxy');
  assert.equal(r.leaks.length, 1, 'and outside snowflake mode, a leak');
  assert.deepEqual(run('snowflake', [[1, udp(GUEST, 53351, '83.135.243.222', 17551, Buffer.alloc(40))]], [wild]).leaks, [], 'in it, by design');
});

test('another uid\'s flows are not Foxy\'s, unless they go where one of Foxy\'s names resolved', () => {
  const other = run('direct', [[1, tcp(GUEST, 50000, '142.250.1.1', 443, true)]], [row('tcp', 50000, '142.250.1.1', 443, 1000)]);
  assert.deepEqual(other.leaks, []);
  assert.equal(other.others.length, 1);
  const mint = run('direct', [
    [1, udp(GUEST, 33333, DNS, 53, dnsQuery('connectivitycheck.gstatic.com'))],
    [1.1, udp(DNS, 53, GUEST, 33333, dnsAnswer('testnut.cashu.space', '104.21.1.1'))],
    [2, tcp(GUEST, 50001, '104.21.1.1', 443, true)],
  ], []);
  assert.ok(mint.leaks.some((l) => /nobody's sockets showed, to an address of testnut\.cashu\.space/.test(l.what)));
});

test('a DNS query for one of Foxy\'s names, or for an onion, is a leak in every Tor mode', () => {
  for (const mode of ['direct', 'obfs4', 'snowflake']) {
    for (const n of ['testnut.cashu.space', 'api.kraken.com', 'mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion', 'x.mempool.space']) {
      const r = run(mode, [[1, udp(GUEST, 33333, DNS, 53, dnsQuery(n))]], []);
      assert.equal(r.leaks.length, 1, mode + ' ' + n);
      assert.equal(r.leaks[0].kind, 'dns');
    }
  }
  assert.deepEqual(run('direct', [[1, udp(GUEST, 33333, DNS, 53, dnsQuery('www.google.com'))]], []).leaks, [], 'the OS\'s lookups are not Foxy\'s');
});

test('obfs4: the seven bridges are fine, a relay reached directly is not', () => {
  for (const b of L.OBFS4_BRIDGES) {
    assert.deepEqual(run('obfs4', [[1, tcp(GUEST, 40010, b, 443, true)]], [row('tcp', 40010, b, 443, UID)]).leaks, [], b);
  }
  const r = run('obfs4', [[1, tcp(GUEST, 40011, RELAY, 443, true)]], [row('tcp', 40011, RELAY, 443, UID)]);
  assert.match(r.leaks[0].what, /a Tor relay reached directly in obfs4 mode/);
  assert.equal(run('direct', [[1, tcp(GUEST, 40012, L.OBFS4_BRIDGES[0], 443, true)]], [row('tcp', 40012, L.OBFS4_BRIDGES[0], 443, UID)]).leaks.length, 1,
    'a bridge outside obfs4 mode is not by design');
});

test('snowflake: the broker front, STUN and a volunteer\'s UDP are by design; only in snowflake mode', () => {
  const packets = [
    [1, udp(GUEST, 33334, DNS, 53, dnsQuery('1098762253.rsc.cdn77.org'))],
    [1.1, udp(DNS, 53, GUEST, 33334, dnsAnswer('1098762253.rsc.cdn77.org', '185.59.220.1'))],
    [2, tcp(GUEST, 40020, '185.59.220.1', 443, true)],
    [3, udp(GUEST, 40021, '198.51.100.9', 3478, Buffer.alloc(20))],
  ];
  const rows = [row('tcp', 40020, '185.59.220.1', 443, UID), row('udp', 40021, '198.51.100.9', 3478, UID)];
  assert.deepEqual(run('snowflake', packets, rows).leaks, []);
  const d = run('direct', packets, rows);
  assert.ok(d.leaks.some((l) => /a Snowflake name outside snowflake mode/.test(l.what)));
  assert.ok(d.leaks.some((l) => /185\.59\.220\.1/.test(l.what)));
  // TCP to an address no Snowflake name gave is still a leak there
  const t = run('snowflake', [[1, tcp(GUEST, 40022, '203.0.113.5', 443, true)]], [row('tcp', 40022, '203.0.113.5', 443, UID)]);
  assert.equal(t.leaks.length, 1);
});

test('unprotected: the open connection only after the person chose to continue without Tor', () => {
  const packets = (t) => [
    [t, udp(GUEST, 33335, DNS, 53, dnsQuery('testnut.cashu.space'))],
    [t + 0.1, udp(DNS, 53, GUEST, 33335, dnsAnswer('testnut.cashu.space', '104.21.9.9'))],
    [t + 0.2, tcp(GUEST, 40030, '104.21.9.9', 443, true)],
  ];
  const rows = [row('tcp', 40030, '104.21.9.9', 443, UID), row('udp', 33335, DNS, 53, UID)];
  assert.deepEqual(run('unprotected', packets(100), rows, 50).leaks, [], 'after the opt-out: the resolver and the mint');
  const before = run('unprotected', packets(10), rows, 50);
  assert.ok(before.leaks.some((l) => /looked up outside Tor: testnut/.test(l.what)));
  assert.ok(before.leaks.some((l) => /104\.21\.9\.9.*before the person chose/.test(l.what)));
  assert.ok(before.leaks.some((l) => /10\.0\.2\.3:53.*before the person chose/.test(l.what)), 'the app\'s own lookups before it');
  // a price source after it is the person's choice too; an onion never is
  assert.deepEqual(run('unprotected', [[100, udp(GUEST, 33336, DNS, 53, dnsQuery('api.kraken.com'))]], [], 50).leaks, []);
  assert.equal(run('unprotected', [[100, udp(GUEST, 33337, DNS, 53, dnsQuery('abcdef.onion'))]], [], 50).leaks.length, 1);
  // and a host that is none of Foxy's is not, even after
  const other = run('unprotected', [[100, tcp(GUEST, 40031, '203.0.113.9', 443, true)]], [row('tcp', 40031, '203.0.113.9', 443, UID)], 50);
  assert.equal(other.leaks.length, 1);
});

test('loopback and IPv6 are read', () => {
  assert.deepEqual(run('direct', [[1, tcp('127.0.0.1', 40040, '127.0.0.1', 9050, true)]], [row('tcp', 40040, '127.0.0.1', 9050, UID)]).leaks, []);
  const six = L.readPcap(Buffer.concat([
    (() => { const g = Buffer.alloc(24); g.writeUInt32LE(0xa1b2c3d4, 0); g.writeUInt32LE(1, 20); return g; })(),
    (() => { const f = tcp('2001:db8::15', 40041, '2a03:2880::1', 443, true); const r = Buffer.alloc(16); r.writeUInt32LE(f.length, 8); r.writeUInt32LE(f.length, 12); return Buffer.concat([r, f]); })(),
  ]));
  assert.equal(six[0].dst, '2a03:2880:0:0:0:0:0:1');
  assert.equal(L.hexAddress('0000000000000000FFFF00000F02000A'), '10.0.2.15', 'an IPv4-mapped /proc/net/tcp6 address');
  assert.equal(L.hexAddress('0F02000A'), '10.0.2.15');
});

test('the consensus is read for relay addresses', () => {
  const text = [
    'network-status-version 3 microdesc',
    'dir-source moria1 F533C81CEF0BC0267857C99B2F471ADF249FA232 128.31.0.39 128.31.0.39 9231 0',
    'r seele AAoQ1DAR6kkoo19hBAX5K0QztNw 2026-10-11 05:12:38 104.53.221.159 9001 0',
    'a [2600:1700:8f70::1]:9001',
    'r test AAoQ1DAR6kkoo19hBAX5K0QztNw Zm9vYmFy 2026-10-11 05:12:38 1.1.1.2 443 80',
  ].join('\n');
  const s = L.readConsensus(text);
  for (const a of ['128.31.0.39', '104.53.221.159', '1.1.1.2', '2600:1700:8f70:0:0:0:0:1']) assert.ok(s.has(a), a);
});

test('the obfs4 bridges are the app\'s own (tor/Bridges.kt)', () => {
  const kt = fs.readFileSync(path.join(__dirname, '..', 'app/src/main/java/io/islandbitcoin/foxy/tor/Bridges.kt'), 'utf8');
  const ips = [...kt.matchAll(/"obfs4 (\d+\.\d+\.\d+\.\d+):\d+ /g)].map((m) => m[1]);
  assert.deepEqual(ips, L.OBFS4_BRIDGES);
  const snow = kt.match(/url=https:\/\/([^/]+)\/ fronts=([^ ]+) ice=([^ ]+) /);
  const names = [snow[1], ...snow[2].split(','), ...snow[3].split(',').map((s) => s.replace(/^stun:/, '').replace(/:\d+$/, ''))];
  assert.deepEqual([...new Set(names)], L.SNOWFLAKE_NAMES);
});

test('a bad input says so', () => {
  assert.throws(() => L.readPcap(Buffer.from('not a pcap at all, really not')), /not a pcap/);
  assert.throws(() => L.check({ packets: [], sockets: [], mode: 'tor', relays }), /mode is one of/);
  assert.equal(L.parseDns(Buffer.from([1, 2, 3])), null);
});

test('the opt-out\'s moment is read from the capture: the marker lookup\'s own time', () => {
  const os = require('os');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'leak-'));
  const g = Buffer.alloc(24); g.writeUInt32LE(0xa1b2c3d4, 0); g.writeUInt32LE(1, 20);
  const rec = (t, f) => { const r = Buffer.alloc(16); r.writeUInt32LE(t, 0); r.writeUInt32LE(f.length, 8); r.writeUInt32LE(f.length, 12); return Buffer.concat([r, f]); };
  // a capture clock hours away from any host's: the mint before the marker is a leak, after it is not
  const before = rec(5000, tcp(GUEST, 40050, '104.21.9.9', 443, true));
  const marker = rec(6000, udp(GUEST, 33340, DNS, 53, dnsQuery('foxy-leak-test-opt-out.example.com')));
  const answer = rec(6000, udp(DNS, 53, GUEST, 33341, dnsAnswer('testnut.cashu.space', '104.21.9.9')));
  const afterFlow = rec(7000, tcp(GUEST, 40051, '104.21.9.9', 443, true));
  fs.writeFileSync(path.join(dir, 'c.pcap'), Buffer.concat([g, answer, before, marker, afterFlow]));
  fs.writeFileSync(path.join(dir, 's.txt'), [row('tcp', 40050, '104.21.9.9', 443, UID), row('tcp', 40051, '104.21.9.9', 443, UID)].join('\n'));
  fs.writeFileSync(path.join(dir, 'k.txt'), Array.from({ length: 200 }, (_, i) => 'r n id 2026-10-11 05:12:38 1.2.' + Math.floor(i / 250) + '.' + (i % 250) + ' 9001 0').join('\n'));
  const out = [];
  const write = process.stdout.write;
  process.stdout.write = (x) => { out.push(String(x)); return true; };
  let code;
  try {
    code = L.main(['--pcap', path.join(dir, 'c.pcap'), '--sockets', path.join(dir, 's.txt'), '--uid', String(UID), '--mode', 'unprotected',
      '--consensus', path.join(dir, 'k.txt'), '--after-marker', 'foxy-leak-test-opt-out.example.com']);
  } finally {
    process.stdout.write = write;
  }
  assert.equal(code, 1, out.join(''));
  const text = out.join('');
  assert.match(text, /opt-out at 6000 on the capture's clock/);
  assert.match(text, /104\.21\.9\.9:443 — reached before the person chose/);
  assert.equal((text.match(/^LEAK: /gm) || []).length, 1, 'only the flow before the marker');
});
