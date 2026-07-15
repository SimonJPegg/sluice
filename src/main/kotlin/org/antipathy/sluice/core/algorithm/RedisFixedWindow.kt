package org.antipathy.sluice.core.algorithm

import org.antipathy.sluice.core.algorithm.redis.ScriptLoader

/** Fixed window via Lua. Atomicity is Redis's problem, not ours. */
class RedisFixedWindow(
      scriptLoader: ScriptLoader
) : RedisAlgorithm(scriptLoader) {
  override val fileLocation: String = "/lua/fixed_window.lua"
}
