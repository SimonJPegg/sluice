package org.antipathy.sluice.api.store

import kotlin.time.TimeSource
import org.antipathy.sluice.api.metrics.Metrics
import org.antipathy.sluice.core.model.RateLimitResponse
import org.antipathy.sluice.core.policy.Policy
import org.antipathy.sluice.core.store.CounterStore

/** Decorator that records duration and errors for every store call without touching core. */
internal class InstrumentedCounterStore(
    private val delegate: CounterStore,
    private val metrics: Metrics
) : CounterStore {

  @Suppress("TooGenericExceptionCaught") // decorator must observe all failures regardless of store
  // implementation
  override suspend fun evaluate(key: String, policy: Policy): RateLimitResponse {
    val start = TimeSource.Monotonic.markNow()
    return try {
      val result = delegate.evaluate(key, policy)
      metrics.trackStoreDuration("evaluate", start.elapsedNow())
      result
    } catch (e: Exception) {
      metrics.trackStoreDuration("evaluate", start.elapsedNow())
      metrics.trackStoreError(e::class.simpleName ?: "unknown")
      throw e
    }
  }
}
