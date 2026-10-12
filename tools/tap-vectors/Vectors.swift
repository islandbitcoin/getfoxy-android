// Vectors.swift: tap-to-pay vectors written by the iOS code itself.
//
// Compiled by tools/tap-vectors/generate.sh together with getfoxy/iOS's own
// Foxy/Bluetooth/TapCrypto.swift, TapSession.swift and TapProtocol.swift at the
// commit in core/PIN, with one mechanical change to TapSession.swift: the
// session's private key and nonce come from `VectorSeed` instead of the system
// random source, so a run can be replayed. Everything else (the handshake, the
// key schedule, sealing, padding, framing, the counters) is iOS's code
// unchanged. FoxyTests' tap tests use random keys and so carry no fixed bytes;
// this is where the fixed bytes come from. The Kotlin tests replay
// app/src/test/resources/fixtures/tap-vectors.json byte for byte
// (io.islandbitcoin.foxy.tap.TapVectorsTest).
import CryptoKit
import Foundation

enum VectorSeed {
    static var keys: [Data] = []
    static var nonces: [Data] = []
    static func key() -> Curve25519.KeyAgreement.PrivateKey {
        // swiftlint:disable:next force_try
        try! Curve25519.KeyAgreement.PrivateKey(rawRepresentation: keys.removeFirst())
    }
    static func nonce() -> Data { nonces.removeFirst() }
}

func hex(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }
func sha(_ d: Data) -> String { hex(Data(SHA256.hash(data: d))) }
func run(_ start: UInt8, _ n: Int) -> Data { Data((0..<n).map { UInt8((Int(start) + $0) & 0xff) }) }
func uuid(_ d: Data) -> UUID {
    let b = [UInt8](d)
    return UUID(uuid: (b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7],
                       b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15]))
}

/// Bytes as the JSON carries them: hex up to 8 KB, and above that the length
/// and SHA-256, so the file stays readable. Either pins them byte for byte.
func bytesField(_ d: Data) -> [String: Any] {
    d.count <= 8192 ? ["hex": hex(d)] : ["length": d.count, "sha256": sha(d)]
}

/// A text argument as the JSON carries it: the text up to 8 KB, and above
/// that its UTF-8 length and SHA-256 (the Kotlin test builds the same text).
func textField(_ t: String) -> Any {
    t.utf8.count <= 8192 ? t : ["length": t.utf8.count, "sha256": sha(Data(t.utf8))]
}

func describe(_ s: TapSession.Step) -> [String: Any] {
    switch s {
    case .send(let d): return ["step": "send", "bytes": bytesField(d)]
    case .waiting: return ["step": "waiting"]
    case .linked(let code): return ["step": "linked", "code": code]
    case .offer(let t, let code): return ["step": "offer", "text": t, "code": code]
    case .payment(let t): return ["step": "payment", "text": textField(t)]
    case .result(let t): return ["step": "result", "text": t]
    case .stop(let why): return ["step": "stop", "why": why]
    case .otherVersion(let theirs, let reply):
        var o: [String: Any] = ["step": "otherVersion", "theirs": theirs]
        if let reply { o["reply"] = hex(reply) }
        return o
    case .change(let t): return ["step": "change", "text": t]
    case .changeTaken: return ["step": "changeTaken"]
    case .quote(let t): return ["step": "quote", "text": t]
    case .terms(let t): return ["step": "terms", "text": t]
    case .asking: return ["step": "asking"]
    case .again(let n): return ["step": "again", "from": n]
    case .paymentSize(let n): return ["step": "paymentSize", "bytes": n]
    }
}

var out: [String: Any] = [:]

// MARK: the doors

let services = [run(0x10, 16), Data(repeating: 0xff, count: 16), Data(count: 16)]
out["doors"] = services.map { s -> [String: Any] in
    let u = uuid(s)
    return ["service": hex(s), "serviceUuid": u.uuidString,
            "in": TapCrypto.inCharacteristic(for: u).uuidString,
            "out": TapCrypto.outCharacteristic(for: u).uuidString,
            "nearLeft": TapCrypto.nearCharacteristic(for: u, edge: "left").uuidString,
            "nearRight": TapCrypto.nearCharacteristic(for: u, edge: "right").uuidString]
}

// MARK: X25519, as CryptoKit does it

let rfcScalar = Data([0xa5, 0x46, 0xe3, 0x6b, 0xf0, 0x52, 0x7c, 0x9d, 0x3b, 0x16, 0x15, 0x4b, 0x82, 0x46, 0x5e, 0xdd,
                      0x62, 0x14, 0x4c, 0x0a, 0xc1, 0xfc, 0x5a, 0x18, 0x50, 0x6a, 0x22, 0x44, 0xba, 0x44, 0x9a, 0xc4])
let rfcU = Data([0xe6, 0xdb, 0x68, 0x67, 0x58, 0x30, 0x30, 0xdb, 0x35, 0x94, 0xc1, 0xa4, 0x24, 0xb1, 0x5f, 0x7c,
                 0x72, 0x66, 0x24, 0xec, 0x26, 0xb3, 0x35, 0x3b, 0x10, 0xa9, 0x03, 0xa6, 0xd0, 0xab, 0x1c, 0x4c])
var agreements: [[String: Any]] = []
for (a, b) in [(rfcScalar, rfcU), (run(0x01, 32), run(0x41, 32)), (Data(repeating: 0xff, count: 32), run(0x80, 32))] {
    let priv = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: a)
    var row: [String: Any] = ["private": hex(a), "public": hex(priv.publicKey.rawRepresentation), "peer": hex(b)]
    if let pub = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: b),
       let s = try? priv.sharedSecretFromKeyAgreement(with: pub) {
        row["shared"] = s.withUnsafeBytes { hex(Data($0)) }
    } else {
        row["refused"] = true
    }
    agreements.append(row)
}
// points of small order: what CryptoKit does with them
let smallOrder = [Data(count: 32), Data([1] + [UInt8](repeating: 0, count: 31)),
                  Data([0xe0, 0xeb, 0x7a, 0x7c, 0x3b, 0x41, 0xb8, 0xae, 0x16, 0x56, 0xe3, 0xfa, 0xf1, 0x9f, 0xc4, 0x6a,
                        0xda, 0x09, 0x8d, 0xeb, 0x9c, 0x32, 0xb1, 0xfd, 0x86, 0x62, 0x05, 0x16, 0x5f, 0x49, 0xb8, 0x00])]
for b in smallOrder {
    let priv = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: run(0x01, 32))
    var row: [String: Any] = ["private": hex(run(0x01, 32)), "public": hex(priv.publicKey.rawRepresentation), "peer": hex(b)]
    if let pub = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: b),
       let s = try? priv.sharedSecretFromKeyAgreement(with: pub) {
        row["shared"] = s.withUnsafeBytes { hex(Data($0)) }
    } else {
        row["refused"] = true
    }
    agreements.append(row)
}
out["x25519"] = agreements

// MARK: the handshake's hashes and the key schedule

let rKey = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: run(0x21, 32))
let pKey = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: run(0x61, 32))
let R = rKey.publicKey.rawRepresentation, P = pKey.publicKey.rawRepresentation
let nr = run(0xa0, 16), np = run(0xc0, 16)
let svc = uuid(services[0])
out["commitment"] = ["public": hex(P), "nonce": hex(np), "commitment": hex(TapCrypto.commitment(publicKey: P, nonce: np))]
out["transcripts"] = [UInt8(2), 3, 4].map { v -> [String: Any] in
    ["version": Int(v), "service": hex(services[0]), "r": hex(R), "p": hex(P), "nr": hex(nr), "np": hex(np),
     "transcript": hex(TapCrypto.transcript(version: v, service: svc, r: R, p: P, nr: nr, np: np))]
}
let th = TapCrypto.transcript(service: svc, r: R, p: P, nr: nr, np: np)
let shared = try rKey.sharedSecretFromKeyAgreement(with: pKey.publicKey)
let keys = TapCrypto.keys(shared: shared, transcript: th)
func raw(_ k: SymmetricKey) -> Data { k.withUnsafeBytes { Data($0) } }
out["keys"] = ["shared": shared.withUnsafeBytes { hex(Data($0)) }, "transcript": hex(th),
               "receiverToPayer": hex(raw(keys.receiverToPayer)), "payerToReceiver": hex(raw(keys.payerToReceiver)),
               "code": keys.code]
// the code over many secrets, so its arithmetic (big-endian, mod 10000, four digits) is pinned at the edges too
var codes: [[String: Any]] = []
for i in 0..<24 {
    let a = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: run(UInt8(i * 7), 32))
    let s = try a.sharedSecretFromKeyAgreement(with: pKey.publicKey)
    let t = TapCrypto.transcript(service: svc, r: a.publicKey.rawRepresentation, p: P, nr: nr, np: np)
    codes.append(["shared": s.withUnsafeBytes { hex(Data($0)) }, "transcript": hex(t), "code": TapCrypto.keys(shared: s, transcript: t).code])
}
out["codes"] = codes

// MARK: sealing

var seals: [[String: Any]] = []
for (msg, counter, kind) in [(Data("the offer".utf8), UInt64(0), UInt8(0x04)), (Data(), UInt64(1), UInt8(0x06)),
                             (run(0, 300), UInt64(0x0102030405060708), UInt8(0x0C)),
                             (Data("12".utf8), UInt64.max, UInt8(0x08))] {
    let sealed = TapCrypto.seal(msg, with: keys.payerToReceiver, counter: counter, transcript: th, kind: kind)!
    seals.append(["key": hex(raw(keys.payerToReceiver)), "counter": String(counter), "transcript": hex(th),
                  "kind": Int(kind), "message": hex(msg), "sealed": hex(sealed),
                  "nonce": TapCrypto.nonce(counter).withUnsafeBytes { hex(Data($0)) }])
}
out["seals"] = seals

// MARK: padding

var pads: [[String: Any]] = []
for (n, size) in [(0, 2048), (1, 256), (252, 256), (2044, 2048)] {
    pads.append(["body": hex(run(0x30, n)), "size": size, "padded": sha(TapCrypto.pad(run(0x30, n), to: size)!)])
}
var payments: [[String: Any]] = []
for n in [0, 10, 4092, 4093, 5000, 9000, 45052, 49148] {
    if let p = TapCrypto.padPayment(Data(repeating: 3, count: n)) {
        payments.append(["length": n, "padded": p.count, "sha256": sha(p)])
    } else {
        payments.append(["length": n, "refused": true])
    }
}
out["padding"] = ["fixed": pads, "payment": payments]

// MARK: the first code (TapProtocol.code), and framing

out["code1"] = [["payload": "lnbc1test", "nonce": hex(run(0, 16)), "code": TapProtocol.code(payload: "lnbc1test", nonce: run(0, 16))],
                ["payload": "{\"v\":2,\"req\":\"creqAbbbbbbbbbbbbbbbbbbbb\"}", "nonce": hex(Data(repeating: 3, count: 16)),
                 "code": TapProtocol.code(payload: "{\"v\":2,\"req\":\"creqAbbbbbbbbbbbbbbbbbbbb\"}", nonce: Data(repeating: 3, count: 16))]]

// MARK: whole conversations, both sides iOS's own TapSession

/// One run, recorded as the calls made and what each answered.
final class Script {
    var ops: [[String: Any]] = []
    let payer: TapSession
    let receiver: TapSession
    init(service: UUID) {
        payer = TapSession(role: .payer, service: service)
        receiver = TapSession(role: .receiver, service: service)
    }
    func side(_ s: TapSession) -> String { s === payer ? "payer" : "receiver" }
    @discardableResult func begin() -> TapSession.Step {
        let s = payer.begin()
        ops.append(["side": "payer", "op": "begin", "out": describe(s)])
        return s
    }
    @discardableResult func receive(_ s: TapSession, _ bytes: Data) -> TapSession.Step {
        let step = s.received(bytes)
        ops.append(["side": side(s), "op": "received", "in": bytesField(bytes), "out": describe(step)])
        return step
    }
    func seal(_ s: TapSession, _ op: String, _ arg: Any?, _ made: Data?) -> Data? {
        var o: [String: Any] = ["side": side(s), "op": op]
        if let arg { o["arg"] = (arg as? String).map { textField($0) } ?? arg }
        o["out"] = made.map { bytesField($0) } ?? NSNull()
        ops.append(o)
        return made
    }
    func sent(_ step: TapSession.Step) -> Data {
        guard case .send(let d) = step else { fatalError("not a send: \(step)") }
        return d
    }
    func handshake() {
        let m1 = sent(begin())
        let m2 = sent(receive(receiver, m1))
        let m3 = sent(receive(payer, m2))
        receive(receiver, m3)
    }
}

/// Session keys and nonces from one seed byte: the payer's key run(seed),
/// the receiver's run(seed + 0x40), their nonces run(seed + 0x80) and
/// run(seed + 0xc0). The payer is made first, so its key and nonce are taken first.
func seeded(_ start: UInt8) {
    VectorSeed.keys = [run(start, 32), run(start &+ 0x40, 32)]
    VectorSeed.nonces = [run(start &+ 0x80, 16), run(start &+ 0xc0, 16)]
}

var sessions: [[String: Any]] = []

// 1. the whole of a tap: price, payment, a person asking, the result, change missed and asked for again, kept
do {
    seeded(0x01)
    let s = Script(service: svc)
    s.handshake()
    let offer = "{\"v\":2,\"inv\":\"lnbc10n1pn9\",\"req\":\"creqAabcdefghijklmnop\",\"up\":true}"
    let m4 = s.seal(s.receiver, "sealOffer", offer, s.receiver.sealOffer(offer))!
    s.receive(s.payer, m4)
    let quote = "{\"rate\":61000,\"sats\":21}"
    let m10 = s.seal(s.payer, "sealQuote", quote, s.payer.sealQuote(quote))!
    s.receive(s.receiver, m10)
    let terms = "{\"v\":2,\"req\":\"creqAabcdefghijklmnopq\"}"
    let m11 = s.seal(s.receiver, "sealTerms", terms, s.receiver.sealTerms(terms))!
    s.receive(s.payer, m11)
    let pay = "{\"mint\":\"https://mint.example\",\"proofs\":[{\"amount\":21}],\"changeTo\":\"02ab\"}"
    let m5 = s.seal(s.payer, "sealPaymentFrames", pay, s.payer.sealPaymentFrames(pay)?.first)!
    s.receive(s.receiver, m5)
    let m9 = s.seal(s.receiver, "sealAsking", nil, s.receiver.sealAsking())!
    s.receive(s.payer, m9)
    let m6 = s.seal(s.receiver, "sealResult", "{\"v\":2,\"ok\":true,\"code\":200}", s.receiver.sealResult("{\"v\":2,\"ok\":true,\"code\":200}"))!
    s.receive(s.payer, m6)
    _ = s.seal(s.receiver, "sealChange", "cashuBchangetokenforthepayer", s.receiver.sealChange("cashuBchangetokenforthepayer"))!
    let m12 = s.seal(s.payer, "sealAgain", nil, s.payer.sealAgain())!
    if case .again(let from) = s.receive(s.receiver, m12) {
        let frames = s.receiver.framesSince(from)
        s.ops.append(["side": "receiver", "op": "framesSince", "arg": from, "out": frames.map { bytesField($0) }])
        for f in frames { s.receive(s.payer, f) }
    }
    let m8 = s.seal(s.payer, "sealChangeTaken", nil, s.payer.sealChangeTaken())!
    s.receive(s.receiver, m8)
    // the same result twice is refused; the receiver cannot ask; the payer cannot send change
    s.receive(s.payer, m6)
    _ = s.seal(s.receiver, "sealAgain", nil, s.receiver.sealAgain())
    _ = s.seal(s.payer, "sealChange", "cashuBnope", s.payer.sealChange("cashuBnope"))
    sessions.append(["name": "a whole tap", "seed": 0x01, "service": hex(services[0]), "ops": s.ops])
}

// 2. a payment too big for one message: the size first, then the parts
do {
    seeded(0x31)
    let s = Script(service: uuid(services[1]))
    s.handshake()
    let offer = "{\"v\":2,\"req\":\"creqAabcdefghijklmnop\"}"
    s.receive(s.payer, s.seal(s.receiver, "sealOffer", offer, s.receiver.sealOffer(offer))!)
    let big = "{\"proofs\":\"" + String(repeating: "abcdefghi\u{20BF}", count: 8_400) + "\"}"
    let estimate = s.payer.paymentWireEstimate(big)
    s.ops.append(["side": "payer", "op": "paymentWireEstimate", "arg": textField(big), "out": estimate])
    let size = s.seal(s.payer, "sealPaymentSize", estimate, s.payer.sealPaymentSize(estimate))!
    let frames = s.payer.sealPaymentFrames(big)!
    s.ops.append(["side": "payer", "op": "sealPaymentFrames", "arg": textField(big), "out": frames.map { bytesField($0) }])
    s.receive(s.receiver, size)
    for f in frames { s.receive(s.receiver, f) }
    // change too big for its one size goes in payment blocks; small change is one size
    let small = s.seal(s.receiver, "sealChange", "cashuBa", s.receiver.sealChange("cashuBa"))!
    s.receive(s.payer, small)
    let large = "cashuB" + String(repeating: "y", count: 3000)
    s.receive(s.payer, s.seal(s.receiver, "sealChange", large, s.receiver.sealChange(large))!)
    // past the cap nothing is sealed
    _ = s.seal(s.payer, "sealPaymentFrames", String(repeating: "x", count: TapCrypto.mostPaymentTotal + 1),
               s.payer.sealPaymentFrames(String(repeating: "x", count: TapCrypto.mostPaymentTotal + 1))?.first)
    sessions.append(["name": "a large payment", "seed": 0x31, "service": hex(services[1]), "ops": s.ops])
}

// 3. what stops a link: a relabelled message, a key that was not promised, a message from nowhere
do {
    seeded(0x51)
    let s = Script(service: uuid(services[2]))
    s.handshake()
    let offer = "{\"v\":2,\"req\":\"creqA\"}"
    s.receive(s.payer, s.seal(s.receiver, "sealOffer", offer, s.receiver.sealOffer(offer))!)
    var asked = s.seal(s.payer, "sealAgain", nil, s.payer.sealAgain())!
    asked[0] = 0x08
    s.receive(s.receiver, asked)
    sessions.append(["name": "relabelled", "seed": 0x51, "service": hex(services[2]), "ops": s.ops])

    seeded(0x71)
    let f = Script(service: uuid(services[2]))
    let m1 = f.sent(f.begin())
    f.receive(f.receiver, m1)
    // a reveal carrying a key that was never promised
    f.receive(f.receiver, Data([0x03]) + run(0x09, 32) + run(0x99, 16))
    sessions.append(["name": "not promised", "seed": 0x71, "service": hex(services[2]), "ops": f.ops])
}

// 4. versions, and messages out of place (no keys needed)
do {
    seeded(0x91)
    let s = Script(service: svc)
    s.receive(s.receiver, Data([0x01]) + Data(repeating: 7, count: 32))
    s.receive(s.receiver, Data([0x01, 4]) + Data(repeating: 7, count: 40))
    s.receive(s.receiver, Data([0x01, 3, 1, 2]))
    s.receive(s.receiver, Data([0x01]))
    s.receive(s.receiver, Data())
    s.receive(s.receiver, Data([0x63, 1, 2]))
    s.receive(s.receiver, Data([0x05, 1, 2, 3]))
    s.receive(s.payer, Data([0x04, 1, 2, 3]))
    s.receive(s.payer, Data([0x07, 1, 2, 3]))
    s.begin()
    s.receive(s.payer, Data([0x02]) + Data(repeating: 7, count: 48))
    s.receive(s.payer, Data([0x02, 4]))
    s.receive(s.payer, Data([0x02, 3, 1]))
    sessions.append(["name": "versions and strays", "seed": 0x91, "service": hex(services[0]), "ops": s.ops])
}
out["sessions"] = sessions

out["source"] = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "unknown"
out["version"] = Int(TapCrypto.version)
let json = try JSONSerialization.data(withJSONObject: out, options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes])
// to a file of its own: the sessions print their diary to standard output
try (json + Data("\n".utf8)).write(to: URL(fileURLWithPath: CommandLine.arguments[2]))
