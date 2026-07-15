package org.antipathy.sluice.core.algorithm

import org.antipathy.sluice.core.algorithm.redis.ScriptLoader

/** Guarantees precision at the cost of memory, Atomicity is Redis's problem  */
class RedisSlidingWindowLog(
  scriptLoader: ScriptLoader
) : RedisAlgorithm(scriptLoader) {
  override val fileLocation: String = "/lua/sliding_window_log.lua"
}
