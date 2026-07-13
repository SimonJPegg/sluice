package org.antipathy.sluice.store

import org.antipathy.sluice.model.Policy
import org.antipathy.sluice.model.RateLimitResponse

/** Interface for storage counter */
interface CounterStore {
  suspend fun evaluate(key: String, policy: Policy): RateLimitResponse
}

