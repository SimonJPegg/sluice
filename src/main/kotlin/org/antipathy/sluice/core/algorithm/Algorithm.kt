package org.antipathy.sluice.core.algorithm

import io.lettuce.core.api.StatefulRedisConnection
import org.antipathy.sluice.core.algorithm.redis.ScriptLoader
import org.antipathy.sluice.core.model.Policy
import org.antipathy.sluice.core.model.RateLimitResponse

/** One function per algorithm. Keeps counting strategies pluggable. */
sealed interface Algorithm {
  suspend fun calculate(key: String, policy: Policy): RateLimitResponse
}

/** Algorithms that manage their own state in-process. */
sealed interface InMemoryAlgorithm : Algorithm

/** Algorithms that delegate to Redis via Lua scripts. */
sealed class RedisAlgorithm(
    redisConnection: StatefulRedisConnection<String, String>
) : Algorithm, ScriptLoader(redisConnection)
