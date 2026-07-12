package org.antipathy.sluice.store

import org.antipathy.sluice.model.Policy
import org.antipathy.sluice.model.RateLimitResponse

/** Abstracts the storage mechanism so algorithms can be tested without Redis. */
interface CounterStore {
  suspend fun evaluate(key: String, policy: Policy): RateLimitResponse
}
