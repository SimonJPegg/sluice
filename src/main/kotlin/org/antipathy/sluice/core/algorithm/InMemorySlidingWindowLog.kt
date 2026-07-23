package org.antipathy.sluice.core.algorithm

import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Instant
import org.antipathy.sluice.core.model.Allowed
import org.antipathy.sluice.core.model.Denied
import org.antipathy.sluice.core.model.Failed
import org.antipathy.sluice.core.model.RateLimitResponse
import org.antipathy.sluice.core.policy.Policy

/** Guarantees precision at the cost of memory, usage grows with the number of requests */
class InMemorySlidingWindowLog(private val clock: Clock = Clock.System) : InMemoryAlgorithm {

  private val counters = ConcurrentHashMap<String, MutableList<Long>>()

  override suspend fun calculate(key: String, policy: Policy): RateLimitResponse {
    // pre-assign a value, to avoid null handling
    var result: RateLimitResponse =
        Failed(reason = "unexpected: compute lambda did not execute", policy.window)
    counters.compute(key) { _, existing ->
      val currentTime = clock.now()
      val windowStarted = currentTime.minus(policy.window).toEpochMilliseconds()
      val counter = existing ?: mutableListOf()
      val currentWindow = counter.filter { elem -> elem >= windowStarted }.toMutableList()
      val usage = currentWindow.size + 1

      val reset =
          if (currentWindow.isEmpty()) {
            policy.window
          } else {
            Instant.fromEpochMilliseconds(currentWindow.first())
                .plus(policy.window)
                .minus(currentTime)
          }

      when {
        usage.toUInt() <= policy.limit -> {
          result = Allowed((policy.limit - usage.toUInt()), reset)
          currentWindow.add(currentTime.toEpochMilliseconds())
        }
        else -> {
          result = Denied(reset)
        }
      }
      return@compute currentWindow
    }

    return result
  }
}
