package org.antipathy.sluice.core.algorithm

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import org.antipathy.sluice.core.model.Allowed
import org.antipathy.sluice.core.model.Denied
import org.antipathy.sluice.core.model.Failed
import org.antipathy.sluice.core.model.Policy
import org.antipathy.sluice.core.model.RateLimitResponse

/** Bucket state. Tokens drain on use, refill lazily on next request. */
private data class TokenBucket(val tokens: Double, val lastRefill: Instant)

/** Allows bursts up to bucket size, then throttles to limit/window after that. */
class InMemoryTokenBucket(private val clock: Clock = Clock.System) : InMemoryAlgorithm {

  private val counters = ConcurrentHashMap<String, TokenBucket>()

  @Suppress("MagicNumber") // its milliseconds
  override suspend fun calculate(key: String, policy: Policy): RateLimitResponse {
    // pre-assign a value, to avoid null handling
    var result: RateLimitResponse = Failed(reason = "unexpected: compute lambda did not execute")
    counters.compute(key) { _, existing ->
      val currentTime = clock.now()
      val counter = existing ?: TokenBucket(policy.limit.toDouble(), currentTime)
      val refillRate = policy.limit.toDouble() / policy.window.inWholeSeconds
      val elapsed = currentTime - counter.lastRefill
      val tokensReleased =
          elapsed.inWholeMilliseconds / 1000.0 * refillRate // we want sub-second precision
      val currentTokens = min(counter.tokens + tokensReleased, policy.limit.toDouble())

      return@compute if (currentTokens >= 1.0) {
        result =
            Allowed(
                (currentTokens - 1).toUInt(),
                ((policy.limit.toDouble() - (currentTokens - 1)) / refillRate).seconds)
        TokenBucket(currentTokens - 1, currentTime)
      } else {
        result = Denied(((1 - currentTokens) / refillRate).seconds)
        TokenBucket(currentTokens, currentTime)
      }
    }
    return result
  }
}
