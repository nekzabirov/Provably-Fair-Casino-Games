package adapter

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicReference
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import util.Env

/**
 * Source of public randomness for provably-fair: the latest TON masterchain block
 * hash. TON is a neutral randomness beacon here, not a payment rail.
 *
 * Synchronous on purpose: the client seed is resolved inside the (non-suspend)
 * Exposed entity builder when a round is created. A short in-memory cache (the hash
 * is a public, process-agnostic value) keeps it off the network on the hot path and
 * avoids a Redis round-trip; the blocking fetch runs on the transaction's IO thread.
 */
class TonAdapter {

    private val http: HttpClient = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val cached = AtomicReference<CachedHash?>(null)

    fun getBlockHash(): String {
        val now = Clock.System.now()
        cached.get()?.let { if (it.expiresAt > now) return it.hash }
        val hash = fetchLatestBlockHash()
        cached.set(CachedHash(hash, now + Env.tonBlockCacheTtl))
        return hash
    }

    private fun fetchLatestBlockHash(): String {
        val request = HttpRequest.newBuilder(URI.create("${Env.tonApiUrl}/getMasterchainInfo"))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return json.decodeFromString<MasterchainInfo>(response.body()).result.last.rootHash
    }

    private data class CachedHash(val hash: String, val expiresAt: Instant)

    @Serializable
    private data class MasterchainInfo(val result: Result) {
        @Serializable
        data class Result(val last: Block)

        @Serializable
        data class Block(@SerialName("root_hash") val rootHash: String)
    }
}
