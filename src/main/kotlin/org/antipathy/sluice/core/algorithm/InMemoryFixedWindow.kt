package org.antipathy.sluice.core.algorithm

import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Instant
import org.antipathy.sluice.core.model.Allowed
import org.antipathy.sluice.core.model.Denied
import org.antipathy.sluice.core.model.Failed
import org.antipathy.sluice.core.model.Policy
import org.antipathy.sluice.core.model.RateLimitResponse

/** Immutable snapshot. Replaced on each request, never mutated. */
private data class FixedWindowCounter(
    val current: UInt,
    val windowExpires: Instant,
)

/** Hard reset at window boundary. Simple, fast, but allows burst at the edges. */
class InMemoryFixedWindow(private val clock: Clock = Clock.System) : InMemoryAlgorithm {

  private val counters = ConcurrentHashMap<String, FixedWindowCounter>()

  override suspend fun calculate(key: String, policy: Policy): RateLimitResponse {
    //pre-assign a value, to avoid null handling
    var result: RateLimitResponse = Failed(reason = "unexpected: compute lambda did not execute")
    counters.compute(key) { _, existing ->
      val currentTime = clock.now()
      val expires = currentTime.plus(policy.window)
      val counter = existing ?: FixedWindowCounter(0u, expires)
      if (counter.windowExpires < currentTime) {
        result = Allowed(policy.limit - 1u, policy.window)
        return@compute FixedWindowCounter(1u, expires)
      }
      if (counter.current + 1u <= policy.limit) {
        result =
            Allowed(policy.limit - (counter.current + 1u), counter.windowExpires.minus(currentTime))
        return@compute counter.copy(current = counter.current + 1u)
      }
      result = Denied(counter.windowExpires.minus(currentTime))
      return@compute counter
    }
    return result
  }
}
