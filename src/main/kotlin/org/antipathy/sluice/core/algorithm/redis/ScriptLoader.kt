package org.antipathy.sluice.core.algorithm.redis

import io.lettuce.core.api.StatefulRedisConnection
import org.antipathy.sluice.core.exceptions.RedisScriptMissingException

/** Loads a Lua script into Redis at startup and caches the SHA for evalsha calls. */
abstract class ScriptLoader(
  protected val redisConnection: StatefulRedisConnection<String, String>,
) {
  abstract val fileLocation: String
  lateinit var sha: String

  /** Call before calculate. Registers the script with Redis so we can evalsha later. */
  fun loadScript() {
    val fileContent =
        (ScriptLoader::class.java.getResource(fileLocation)
                ?: throw RedisScriptMissingException(fileLocation))
            .readText()
    sha = redisConnection.sync().scriptLoad(fileContent)
  }
}
