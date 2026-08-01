package org.antipathy.sluice.core.algorithm.redis

import io.lettuce.core.api.StatefulRedisConnection
import org.antipathy.sluice.core.exceptions.RedisScriptMissingException

/** Loads a Lua script into Redis at startup and caches the SHA for evalsha calls. */
class ScriptLoader(
    private val redisConnection: StatefulRedisConnection<String, String>,
    private val postLoadFunction: (String) -> Unit = {},
) {

  /** Call before calculate. Registers the script with Redis so we can evalsha later. */
  fun loadScript(fileLocation: String): String {
    val fileContent =
        (ScriptLoader::class.java.getResource(fileLocation)
                ?: throw RedisScriptMissingException(fileLocation))
            .readText()
    val sha = redisConnection.sync().scriptLoad(fileContent)
    postLoadFunction(fileLocation.removeSuffix(".lua").substringAfterLast("/"))
    return sha
  }

  fun getConnection(): StatefulRedisConnection<String, String> {
    return redisConnection
  }
}
