package org.antipathy.sluice.store

import org.antipathy.sluice.model.Policy
import org.antipathy.sluice.model.RateLimitResponse

/** Atomic rate limit evaluation via Redis Lua scripts */
class RedisCounterStore: CounterStore {

  override suspend fun evaluate(key: String, policy: Policy): RateLimitResponse {
    TODO("Not yet implemented")
  }
}
