package org.antipathy.sluice.core.store

import java.util.concurrent.atomic.AtomicInteger
import org.antipathy.sluice.core.model.Failed
import org.antipathy.sluice.core.model.FailureCategory
import org.antipathy.sluice.core.model.RateLimitResponse
import org.antipathy.sluice.core.policy.Policy

/** Throttles requests once a threshold is met */
class ThrottledCounterStore(val maxRequests: Int, val delegate: CounterStore) : CounterStore {

  private val counter = AtomicInteger()

  override suspend fun evaluate(key: String, policy: Policy): RateLimitResponse {
    try {
      val r = counter.incrementAndGet()
      return if (r <= maxRequests) {
        delegate.evaluate(key, policy)
      } else {
        Failed(
            "Exceeded maxRequests count $r:$maxRequests",
            policy.window,
            FailureCategory.OVERLOADED,
        )
      }
    } finally {
      counter.decrementAndGet()
    }
  }
}
