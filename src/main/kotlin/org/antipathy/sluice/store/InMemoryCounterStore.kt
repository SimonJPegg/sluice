package org.antipathy.sluice.store

import org.antipathy.sluice.model.Policy
import org.antipathy.sluice.model.AlgorithmType
import org.antipathy.sluice.model.RateLimitResponse
import org.antipathy.sluice.model.Failed
import org.antipathy.sluice.model.Allowed
import org.antipathy.sluice.model.Denied
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Instant

/** Type for the internal counter map */
private sealed interface Counter

/** Immutable snapshot of a fixed window's state — replaced (not mutated) on each request. */
private data class FixedWindowCounter(
  val current: UInt,
  val windowExpires: Instant,
) : Counter

/** In-memory counter store for testing and single-node deployments without Redis. */
class InMemoryCounterStore(private val clock: Clock = Clock.System) : CounterStore {

  private val counters = ConcurrentHashMap<String, Counter>()

  override suspend fun evaluate(key: String, policy: Policy): RateLimitResponse {
    return when (policy.algorithmType) {
      AlgorithmType.FIXED_WINDOW -> fixedWindow(key, policy)
      else -> Failed(reason = "Algorithm ${policy.algorithmType} has not been implemented yet")
    }
  }

  /** Fixed window: one counter per key, hard reset at window boundary. */
  private fun fixedWindow(key: String, policy: Policy): RateLimitResponse {
    // assigning a value here to avoid the null handling, we don't expect this to be returned
    var result: RateLimitResponse = Failed(reason = "unexpected: compute lambda did not execute")
    counters.compute(key) {_,existing ->
      val currentTime = clock.now()
      val expires = currentTime.plus(policy.window)
      val counter = existing ?: FixedWindowCounter(0u, expires)

      if (counter !is FixedWindowCounter) {
        result =  Failed(reason = "key $key is not a fixed-window schedule")
        return@compute counter
      }
      if (counter.windowExpires < currentTime) {
        result = Allowed(policy.limit - 1u, policy.window)
        return@compute FixedWindowCounter(1u, expires)
      }
      if (counter.current +1u <= policy.limit) {
        result = Allowed(policy.limit - (counter.current + 1u), counter.windowExpires.minus(currentTime))
        return@compute counter.copy(current = counter.current + 1u)
      }
      result = Denied(counter.windowExpires.minus(currentTime))
      return@compute counter
    }
    return result
  }

}
