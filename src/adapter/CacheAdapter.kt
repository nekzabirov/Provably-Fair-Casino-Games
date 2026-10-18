package adapter

import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.SetArgs
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import kotlin.time.Duration

/** Redis-backed string cache. Ephemeral only (session lookups, TON block hash). */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class CacheAdapter(private val redis: RedisCoroutinesCommands<String, String>) {

    suspend fun get(key: String): String? = redis.get(key)

    suspend fun set(key: String, value: String, ttl: Duration) {
        redis.set(key, value, SetArgs.Builder.px(ttl.inWholeMilliseconds))
    }
}
