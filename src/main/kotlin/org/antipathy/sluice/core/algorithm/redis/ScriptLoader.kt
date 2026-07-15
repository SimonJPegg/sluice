package org.antipathy.sluice.core.algorithm.redis

import io.lettuce.core.api.StatefulRedisConnection
import org.antipathy.sluice.core.exceptions.RedisScriptMissingException

/** Loads a Lua script into Redis at startup and caches the SHA for evalsha calls. */
class ScriptLoader(
  private val redisConnection: StatefulRedisConnection<String, String>,
) {

  /** Call before calculate. Registers the script with Redis so we can evalsha later. */
  fun loadScript(fileLocation: String): String {
    val fileContent =
        (ScriptLoader::class.java.getResource(fileLocation)
                ?: throw RedisScriptMissingException(fileLocation))
            .readText()
    return redisConnection.sync().scriptLoad(fileContent)
  }

  fun getConnection(): StatefulRedisConnection<String, String> {
    return redisConnection
  }
}
