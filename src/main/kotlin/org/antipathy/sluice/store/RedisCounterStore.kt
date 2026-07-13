package org.antipathy.sluice.store

import io.lettuce.core.RedisException
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.StatefulRedisConnection
import org.antipathy.sluice.exceptions.RedisScriptMissingException
import org.antipathy.sluice.model.AlgorithmType
import org.antipathy.sluice.model.Allowed
import org.antipathy.sluice.model.Denied
import org.antipathy.sluice.model.FailType
import org.antipathy.sluice.model.Failed
import org.antipathy.sluice.model.Policy
import org.antipathy.sluice.model.RateLimitResponse
import kotlin.time.Duration.Companion.seconds

/** Atomic rate limit evaluation via Redis Lua scripts */
class RedisCounterStore(
  private val redisConnection: StatefulRedisConnection<String, String>
): CounterStore {

  private val scriptSHAs : Map<AlgorithmType, String>

  init {
    val scripts = mapOf(
      AlgorithmType.FIXED_WINDOW to "/lua/fixed_window.lua",
//      AlgorithmType.SLIDING_WINDOW_COUNTER to "/lua/sliding_window_counter.lua",
//      AlgorithmType.SLIDING_WINDOW_LOG to "/lua/sliding_window_log.lua",
//      AlgorithmType.TOKEN_BUCKET to "/lua/token_bucket.lua",
    )

    scriptSHAs = scripts.mapValues { (_, value) ->
      val fileContent = (RedisCounterStore::class.java.getResource(value) ?:
        throw RedisScriptMissingException(value)).readText()
      redisConnection.sync().scriptLoad(fileContent)
     }
  }

  override suspend fun evaluate(key: String, policy: Policy): RateLimitResponse {
    try {
      return when (policy.algorithmType) {
        AlgorithmType.FIXED_WINDOW -> fixedWindow(key, policy)
        else -> Failed(reason = "Algorithm ${policy.algorithmType} has not been implemented yet")
      }
    } catch (_: RedisException) {
      return if (policy.failType == FailType.OPEN) {
        // no way to calculate here, so tell the consumer that they've hit the limit for now and to wait until the
        // next window. Policy has already told us to allow it.
        Allowed(0u,policy.window)
      } else {
        Denied(policy.window)
      }
    }
  }

  private fun fixedWindow(key: String, policy: Policy): RateLimitResponse {
    val result = redisConnection.sync().evalsha<List<Long>>(
      scriptSHAs.getValue(policy.algorithmType),
      ScriptOutputType.MULTI,
      arrayOf(key),
      policy.limit.toString(),
      policy.window.inWholeSeconds.toString()
    )
    val count = result[0].toUInt()
    val ttl = result[1].seconds

    if (count <= policy.limit) {
      return Allowed((policy.limit - count),ttl)
    } else {
      return Denied(ttl)
    }
  }
}
