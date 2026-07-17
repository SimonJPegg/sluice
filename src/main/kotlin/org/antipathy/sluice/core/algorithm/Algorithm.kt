package org.antipathy.sluice.core.algorithm

import io.lettuce.core.ScriptOutputType
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.future.await
import org.antipathy.sluice.core.algorithm.redis.ScriptLoader
import org.antipathy.sluice.core.model.Allowed
import org.antipathy.sluice.core.model.Denied
import org.antipathy.sluice.core.model.RateLimitResponse
import org.antipathy.sluice.core.policy.Policy

/** One function per algorithm. Keeps counting strategies pluggable. */
sealed interface Algorithm {
  suspend fun calculate(key: String, policy: Policy): RateLimitResponse
}

/** Algorithms that manage their own state in-process. */
sealed interface InMemoryAlgorithm : Algorithm

/** Algorithms that delegate to Redis via Lua scripts. */
sealed class RedisAlgorithm(protected val scriptLoader: ScriptLoader) : Algorithm {
  abstract val fileLocation: String
  val sha by lazy { scriptLoader.loadScript(fileLocation) }

  override suspend fun calculate(key: String, policy: Policy): RateLimitResponse {
    val result =
        scriptLoader
            .getConnection()
            .async()
            .evalsha<List<Long>>(
                sha,
                ScriptOutputType.MULTI,
                arrayOf(key),
                policy.limit.toString(),
                policy.window.inWholeSeconds.toString())
            .await()

    val allowed = result[0] == 1L
    val count = result[1].toUInt()
    val ttl = result[2].seconds

    return if (allowed) {
      Allowed(policy.limit - count, ttl)
    } else {
      Denied(ttl)
    }
  }
}
