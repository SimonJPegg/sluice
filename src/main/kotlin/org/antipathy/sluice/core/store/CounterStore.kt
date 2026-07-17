package org.antipathy.sluice.core.store

import org.antipathy.sluice.core.model.RateLimitResponse
import org.antipathy.sluice.core.policy.Policy

/** The only thing consumers call. Everything else is internal. */
interface CounterStore {
  suspend fun evaluate(key: String, policy: Policy): RateLimitResponse
}
