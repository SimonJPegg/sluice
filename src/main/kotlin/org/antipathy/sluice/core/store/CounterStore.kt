package org.antipathy.sluice.core.store

import org.antipathy.sluice.core.model.Policy
import org.antipathy.sluice.core.model.RateLimitResponse

/** The only thing consumers call. Everything else is internal. */
interface CounterStore {
  suspend fun evaluate(key: String, policy: Policy): RateLimitResponse
}
