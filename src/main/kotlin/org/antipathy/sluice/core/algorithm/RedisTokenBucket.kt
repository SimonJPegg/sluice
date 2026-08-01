package org.antipathy.sluice.core.algorithm

import io.lettuce.core.RedisNoScriptException
import io.lettuce.core.ScriptOutputType
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.future.await
import org.antipathy.sluice.core.algorithm.redis.ScriptLoader
import org.antipathy.sluice.core.model.Allowed
import org.antipathy.sluice.core.model.Denied
import org.antipathy.sluice.core.model.RateLimitResponse
import org.antipathy.sluice.core.policy.Policy
import org.slf4j.LoggerFactory

/**
 * Overrides base because token bucket returns remaining tokens, not request count. Also needs
 * millisecond ttl.
 */
class RedisTokenBucket(scriptLoader: ScriptLoader) : RedisAlgorithm(scriptLoader) {
  override val fileLocation: String = "/lua/token_bucket.lua"

  private val logger = LoggerFactory.getLogger(RedisTokenBucket::class.java)

  override suspend fun calculate(key: String, policy: Policy): RateLimitResponse {
    val result =
        try {
          scriptLoader
              .getConnection()
              .async()
              .evalsha<List<Long>>(
                  sha,
                  ScriptOutputType.MULTI,
                  arrayOf(key),
                  policy.limit.toString(),
                  policy.window.inWholeSeconds.toString(),
              )
              .await()
        } catch (_: RedisNoScriptException) {
          scriptLoader.loadScript(fileLocation)
          logger.warn("NOSCRIPT for {}, re-registering", fileLocation)
          scriptLoader
              .getConnection()
              .async()
              .evalsha<List<Long>>(
                  sha,
                  ScriptOutputType.MULTI,
                  arrayOf(key),
                  policy.limit.toString(),
                  policy.window.inWholeSeconds.toString(),
              )
              .await()
        }

    val allowed = result[0] == 1L
    val remaining = result[1].toUInt() // <-- this is why we're overriding here
    val ttl = result[2].milliseconds // also milliseconds

    return if (allowed) {
      Allowed(remaining, ttl)
    } else {
      Denied(ttl)
    }
  }
}
