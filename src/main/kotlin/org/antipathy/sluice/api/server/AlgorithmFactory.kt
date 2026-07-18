package org.antipathy.sluice.api.server

import org.antipathy.sluice.core.algorithm.InMemoryAlgorithm
import org.antipathy.sluice.core.algorithm.InMemoryFixedWindow
import org.antipathy.sluice.core.algorithm.InMemorySlidingWindowCounter
import org.antipathy.sluice.core.algorithm.InMemorySlidingWindowLog
import org.antipathy.sluice.core.algorithm.InMemoryTokenBucket
import org.antipathy.sluice.core.algorithm.RedisAlgorithm
import org.antipathy.sluice.core.algorithm.RedisFixedWindow
import org.antipathy.sluice.core.algorithm.RedisSlidingWindowCounter
import org.antipathy.sluice.core.algorithm.RedisSlidingWindowLog
import org.antipathy.sluice.core.algorithm.RedisTokenBucket
import org.antipathy.sluice.core.algorithm.redis.ScriptLoader
import org.antipathy.sluice.core.policy.AlgorithmType

/** maps an algorithm type to a redis algorithm */
fun redisAlgorithm(type: AlgorithmType, scriptLoader: ScriptLoader): RedisAlgorithm {
  return when (type) {
    AlgorithmType.FIXED_WINDOW -> {
      RedisFixedWindow(scriptLoader)
    }
    AlgorithmType.TOKEN_BUCKET -> {
      RedisTokenBucket(scriptLoader)
    }
    AlgorithmType.SLIDING_WINDOW_COUNTER -> {
      RedisSlidingWindowCounter(scriptLoader)
    }
    AlgorithmType.SLIDING_WINDOW_LOG -> {
      RedisSlidingWindowLog(scriptLoader)
    }
  }
}

/** maps an algorithm type to an in memory algorithm */
fun inMemoryAlgorithm(type: AlgorithmType): InMemoryAlgorithm {
  return when (type) {
    AlgorithmType.FIXED_WINDOW -> {
      InMemoryFixedWindow()
    }
    AlgorithmType.TOKEN_BUCKET -> {
      InMemoryTokenBucket()
    }
    AlgorithmType.SLIDING_WINDOW_COUNTER -> {
      InMemorySlidingWindowCounter()
    }
    AlgorithmType.SLIDING_WINDOW_LOG -> {
      InMemorySlidingWindowLog()
    }
  }
}
