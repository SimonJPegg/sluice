package org.antipathy.sluice.core.algorithm

import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.StatefulRedisConnection
import kotlin.time.Duration.Companion.seconds
import org.antipathy.sluice.core.model.Allowed
import org.antipathy.sluice.core.model.Denied
import org.antipathy.sluice.core.model.Policy
import org.antipathy.sluice.core.model.RateLimitResponse
import kotlinx.coroutines.future.await

/** Sliding window counter via Lua. Same weighted approximation, atomic server-side. */
class RedisSlidingWindowCounter(redisConnection: StatefulRedisConnection<String, String>) :
    RedisAlgorithm(redisConnection) {

  override val fileLocation: String = "/lua/sliding_window_counter.lua"

  override suspend fun calculate(key: String, policy: Policy): RateLimitResponse {
    val result =
        redisConnection
            .async()
            .evalsha<List<Long>>(
                sha,
                ScriptOutputType.MULTI,
                arrayOf(key),
                policy.limit.toString(),
                policy.window.inWholeSeconds.toString()).await()
    val count = result[0].toUInt()
    val ttl = result[1].seconds

    return if (count <= policy.limit) {
      Allowed(policy.limit - count, ttl)
    } else {
      Denied(ttl)
    }
  }
}
