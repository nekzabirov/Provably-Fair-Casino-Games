package service

import adapter.TonAdapter
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import util.sha256
import util.toHex

/**
 * Shared provably-fair primitives. Pure crypto except [getTonSeed], which reads the
 * TON block hash. Every game derives its outcome from a [computeHash] variant.
 *
 * Two trust models live here, both committed via [hashServerSeed] (the SHA-256 of
 * the server seed, shown to the player before the round so we cannot change the
 * outcome after the fact):
 *
 *  - **Per-player games** (Mines, Shell): the player supplies their own [clientSeed]
 *    on auth. The outcome mixes serverSeed (ours, committed) + clientSeed (theirs) +
 *    tonSeed (a public TON block hash neither party controls) + nonce (the roundId).
 *    Because the player controls part of the input and sees the commitment first,
 *    we cannot pick a server seed that targets a known result. This is the classic
 *    Stake-style model, strengthened by the TON beacon.
 *  - **Shared real-time game** (TeleFly crash): one outcome serves every bettor at
 *    once, so a per-player client seed is impossible. Its public seed is the TON
 *    block hash via the 3-arg [computeHash]. See its module for details.
 */
class ProvablyFair(private val tonAdapter: TonAdapter) {

    private val random = SecureRandom()

    /** 32 random bytes, hex. Stored per round; its hash is revealed up-front. */
    fun generateServerSeed(): String {
        val bytes = ByteArray(SERVER_SEED_BYTES)
        random.nextBytes(bytes)
        return bytes.toHex()
    }

    /** SHA-256 of the server seed — the commitment shown to the player before the round. */
    fun hashServerSeed(serverSeed: String): String = sha256(serverSeed)

    /** Latest TON block hash — a public value neither party controls. */
    fun getTonSeed(): String = tonAdapter.getBlockHash()

    /**
     * A random default client seed (16 bytes, hex) for a player who supplies none.
     * The player can always override it with their own on the next auth.
     */
    fun defaultClientSeed(): String {
        val bytes = ByteArray(CLIENT_SEED_BYTES)
        random.nextBytes(bytes)
        return bytes.toHex()
    }

    /**
     * Normalise a player-supplied client seed: trimmed, restricted to a safe charset
     * and bounded length. Blank input falls back to a fresh [defaultClientSeed].
     */
    fun sanitizeClientSeed(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return defaultClientSeed()
        require(trimmed.length <= MAX_CLIENT_SEED && trimmed.matches(CLIENT_SEED_PATTERN)) {
            "clientSeed must be 1..$MAX_CLIENT_SEED chars of [A-Za-z0-9-_]"
        }
        return trimmed
    }

    /**
     * Per-player outcome hash: `HMAC-SHA256(serverSeed, "clientSeed:tonSeed:nonce")`, hex.
     * The `:`-joined message is exactly what the in-game verifier reproduces.
     */
    fun computeHash(serverSeed: String, clientSeed: String, tonSeed: String, nonce: String): String =
        hmac(serverSeed, "$clientSeed:$tonSeed:$nonce")

    /**
     * Shared-game outcome hash: `HMAC-SHA256(serverSeed, clientSeed + nonce)`, hex,
     * where `clientSeed` is the public TON seed. Used by TeleFly's shared round.
     */
    fun computeHash(serverSeed: String, clientSeed: String, nonce: String): String =
        hmac(serverSeed, clientSeed + nonce)

    private fun hmac(key: String, message: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key.toByteArray(), HMAC_ALGORITHM))
        return mac.doFinal(message.toByteArray()).toHex()
    }

    private companion object {
        const val SERVER_SEED_BYTES = 32
        const val CLIENT_SEED_BYTES = 16
        const val MAX_CLIENT_SEED = 64
        val CLIENT_SEED_PATTERN = Regex("^[A-Za-z0-9-_]+$")
        const val HMAC_ALGORITHM = "HmacSHA256"
    }
}
