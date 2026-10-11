package io.islandbitcoin.foxy.keychain

/**
 * The keys a payment request locks ecash to, at NUT-13's P2PK path
 * m/129373'/10'/0'/0'/{index} (Foxy/Keychain/P2PK.swift). The last level is a
 * NORMAL child, as the spec and CDK derive it.
 *
 * The chain code never leaves: a non-hardened child private key and its
 * parent's chain code give the parent, and the parent every other index. The
 * bridge answers 32 and 33 bytes and nothing else.
 */
object P2PK {
    sealed class Failure(message: String) : Exception(message) {
        class BadIndex : Failure("bad index")
    }

    const val PURPOSE: Long = 129373
    const val ACCOUNT: Long = 10
    const val LAST_INDEX: Long = 0x7FFF_FFFFL
    const val LARGEST_NEXT: Long = 0x8000_0000L
    const val MOST_RESERVED = 64
    const val MOST_SCANNED = 300
    const val LAST_SCANNED: Long = 20_000
    val BEYOND: Long get() = RestoreWindow.BEYOND

    class Pair(val index: Long, val privateKey: ByteArray, val publicKey: ByteArray) {
        fun wipe() = Bytes.wipe(privateKey)
    }

    fun indicesFit(start: Long, count: Int): Boolean {
        if (count < 1 || start < 0) return false
        val end = Bytes.add(start, (count - 1).toLong()) ?: return false
        return end <= LAST_INDEX
    }

    fun scanFits(start: Long, count: Int): Boolean {
        if (!indicesFit(start, count)) return false
        val end = Bytes.add(start, count.toLong()) ?: return false
        return end <= LAST_SCANNED
    }

    /** Within 1000 of the last index reserved, or inside a range this session's scan served. */
    fun keyAllowed(index: Long, next: Long, served: Long): Boolean {
        if (index < 0 || index > LAST_INDEX) return false
        if (index < served) return true
        val limit = Bytes.add(next, BEYOND) ?: return true
        return index < limit
    }

    fun derive(seed: NUT13.Seed, start: Long, count: Int): List<Pair> {
        if (!indicesFit(start, count)) throw Failure.BadIndex()
        var parent = NUT13.master(seed)
        try {
            for (level in longArrayOf(PURPOSE, ACCOUNT, 0, 0)) {
                val next = NUT13.child(parent, level or 0x8000_0000L)
                parent.wipe()
                parent = next
            }
            // every index is a normal child of the same parent, whose public key is computed once
            val point = NUT13.publicKey(parent.key)
            return (0 until count).map { offset ->
                val index = start + offset
                val child = NUT13.child(parent, index, point)
                // the chain code goes here and no further
                Bytes.wipe(child.chain)
                Pair(index, child.key, NUT13.publicKey(child.key))
            }
        } finally {
            parent.wipe()
        }
    }

    /** Public keys only: the private halves are wiped here. */
    fun publicKeys(seed: NUT13.Seed, start: Long, count: Int): List<ByteArray> {
        if (count <= 0) return emptyList()
        val pairs = derive(seed, start, count)
        try {
            return pairs.map { it.publicKey }
        } finally {
            pairs.forEach { it.wipe() }
        }
    }

    fun key(seed: NUT13.Seed, index: Long): Pair = derive(seed, index, 1).firstOrNull() ?: throw Failure.BadIndex()

    fun pubkeysReply(start: Long, next: Long, pubkeys: List<ByteArray>): String {
        val list = pubkeys.joinToString(",") { "\"" + Bytes.hex(it) + "\"" }
        return "{\"start\":$start,\"next\":$next,\"pubkeys\":[$list]}"
    }

    fun keyReply(pair: Pair): String =
        "{\"index\":${pair.index},\"privkey\":\"${Bytes.hex(pair.privateKey)}\",\"pubkey\":\"${Bytes.hex(pair.publicKey)}\"}"
}
