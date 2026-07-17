package org.antipathy.sluice.core.algorithm

import org.antipathy.sluice.core.algorithm.redis.ScriptLoader

/** Sliding window counter via Lua. Same weighted approximation, atomic server-side. */
class RedisSlidingWindowCounter(scriptLoader: ScriptLoader) : RedisAlgorithm(scriptLoader) {
  override val fileLocation: String = "/lua/sliding_window_counter.lua"
}
