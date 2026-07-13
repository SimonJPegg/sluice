package org.antipathy.sluice.core.algorithm

import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import org.antipathy.sluice.core.model.Allowed
import org.antipathy.sluice.core.model.Denied
import org.antipathy.sluice.core.model.Failed
import org.antipathy.sluice.core.model.Policy
import org.antipathy.sluice.core.model.RateLimitResponse

private data class SlidingWindowCounter(
    val currentWindowCount: UInt,
    val previousWindowCount: UInt,
    val windowStarted: Instant,
)

/** Smooths fixed window's burst-at-boundary problem using weighted approximation of the previous window. */
class InMemorySlidingWindowCounter(private val clock: Clock = Clock.System) : InMemoryAlgorithm {

  private val counters = ConcurrentHashMap<String, SlidingWindowCounter>()

  override suspend fun calculate(key: String, policy: Policy): RateLimitResponse {
    //pre-assign a value, to avoid null handling
    var result: RateLimitResponse = Failed(reason = "unexpected: compute lambda did not execute")
    counters.compute(key) {_, existing ->
      val currentTime = clock.now()
      val counter = existing ?: SlidingWindowCounter(0u, 0u, currentTime)
      val (rolledCounter, remaining) = rollWindow(counter, currentTime, policy.window)
      val estimated = estimate(rolledCounter, remaining, policy.window)
      val (updatedCounter, response) = decide(rolledCounter, estimated, policy, remaining)
      result = response
      return@compute updatedCounter
    }
    return result
  }

  /** Detects window expiry and rotates counters if needed. */
  private fun rollWindow(
    counter: SlidingWindowCounter,
    currentTime: Instant,
    window: Duration): Pair<SlidingWindowCounter, Duration> {
    if (currentTime - counter.windowStarted >= window + window) {
      // Two or more windows stale, previous is meaningless
      return Pair(SlidingWindowCounter(0u, 0u, currentTime), window)
    }
    val remaining = window.minus(currentTime.minus(counter.windowStarted))
    if (remaining <= Duration.ZERO) {
      val newWindowStart = counter.windowStarted + window
      return Pair(
        counter.copy(currentWindowCount = 0u, previousWindowCount = counter.currentWindowCount, windowStarted = newWindowStart),
        window - (currentTime.minus(newWindowStart))
      )
    }
    return Pair(counter, remaining)
  }

  /** Weighted approximation: previous window's contribution decays as current window progresses. */
  private fun estimate(rolledCounter: SlidingWindowCounter, remaining: Duration, window: Duration) : UInt {
    val overlapPercent = (remaining / window)
    val estimated = (rolledCounter.previousWindowCount.toInt() * overlapPercent).toUInt() + rolledCounter.currentWindowCount
    return estimated
  }

  /** Compares estimated count against limit. Returns updated counter and the verdict. */
  private fun decide(
    rolledCounter: SlidingWindowCounter,
    estimated: UInt,
    policy: Policy,
    remaining: Duration
  ): Pair<SlidingWindowCounter, RateLimitResponse> {
    return if (estimated + 1u <= policy.limit) {
      Pair(
        rolledCounter.copy(currentWindowCount = rolledCounter.currentWindowCount + 1u),
        Allowed(policy.limit - (estimated + 1u), remaining)
      )
    } else {
      Pair(
        rolledCounter,
        Denied(remaining)
      )
    }
  }
}
