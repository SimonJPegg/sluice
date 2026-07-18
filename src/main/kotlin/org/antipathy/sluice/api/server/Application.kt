package org.antipathy.sluice.api.server

import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.server.application.install
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisException
import io.lettuce.core.api.StatefulRedisConnection
import java.util.UUID
import kotlinx.serialization.json.Json
import org.antipathy.sluice.core.algorithm.redis.ScriptLoader
import org.antipathy.sluice.core.policy.YamlPolicyRegistry
import org.antipathy.sluice.core.store.InMemoryCounterStore
import org.antipathy.sluice.core.store.RedisCounterStore

/** Ktor EngineMain entry point. Config-driven module loading via application.yaml. */
fun main(
    args: Array<String>
) { // don't believe the hype, the real entry point is Application.module() below
  EngineMain.main(args)
}

/** Creates a plugin to close the redis connection cleanly on shutdown */
fun createRedisCleanUpPlugin(
    client: RedisClient,
    connection: StatefulRedisConnection<String, String>
): ApplicationPlugin<Unit> {
  return createApplicationPlugin("RedisCleanupPlugin") {
    on(MonitoringEvent(ApplicationStopping)) {
      // TODO: Replace with structured logging
      // We're shutting down here, the connection is going either way.
      try {
        connection.close()
      } catch (e: RedisException) {
        e.printStackTrace()
      }
      try {
        client.close()
      } catch (e: RedisException) {
        e.printStackTrace()
      }
    }
  }
}

/** Composition root. Reads config, builds dependencies, installs plugins, mounts routes. */
fun Application.module() {
  val policyRegistry =
      YamlPolicyRegistry(environment.config.property("rate-limit.policies.location").getString())

  @Suppress("MagicNumber") // obvious from the context
  val maxIdentifierLength =
      environment.config
          .propertyOrNull("rate-limit.validation.max-identifier-length")
          ?.getString()
          ?.toInt() ?: 256

  val requiredAlgorithms = policyRegistry.requiredAlgorithms()
  val redisUri = environment.config.propertyOrNull("rate-limit.backend.redis-uri")
  val store =
      if (redisUri != null) {
        val client = RedisClient.create(redisUri.getString())
        val connection = client.connect()
        install(createRedisCleanUpPlugin(client, connection))
        val scriptLoader = ScriptLoader(connection)
        RedisCounterStore(requiredAlgorithms.associate { it to redisAlgorithm(it, scriptLoader) })
      } else {
        InMemoryCounterStore(requiredAlgorithms.associate { it to inMemoryAlgorithm(it) })
      }

  install(ContentNegotiation) {
    json(
        Json {
          isLenient = false
          ignoreUnknownKeys = false
        })
  }

  install(CallId) {
    header(HttpHeaders.XRequestId)
    generate { UUID.randomUUID().toString() }
    replyToHeader(HttpHeaders.XRequestId)
  }

  healthCheck()
  rateLimit(store, policyRegistry, maxIdentifierLength)
}
