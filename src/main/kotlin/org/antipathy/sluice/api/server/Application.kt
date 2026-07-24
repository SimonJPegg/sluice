package org.antipathy.sluice.api.server

import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.bearer
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisException
import io.lettuce.core.api.StatefulRedisConnection
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import org.antipathy.sluice.api.config.SluiceConfiguration
import org.antipathy.sluice.api.health.PolicyStatus
import org.antipathy.sluice.api.health.StatusChecker
import org.antipathy.sluice.api.health.StoreStatus
import org.antipathy.sluice.api.metrics.Metrics
import org.antipathy.sluice.api.metrics.PrometheusMetrics
import org.antipathy.sluice.api.model.PolicyContext
import org.antipathy.sluice.api.routes.healthCheck
import org.antipathy.sluice.api.routes.metrics
import org.antipathy.sluice.api.routes.rateLimit
import org.antipathy.sluice.api.store.InstrumentedCounterStore
import org.antipathy.sluice.core.algorithm.inMemoryAlgorithm
import org.antipathy.sluice.core.algorithm.redis.ScriptLoader
import org.antipathy.sluice.core.algorithm.redisAlgorithm
import org.antipathy.sluice.core.exceptions.RedisScriptMissingException
import org.antipathy.sluice.core.policy.YamlPolicyRegistry
import org.antipathy.sluice.core.store.CircuitBreakerCounterStore
import org.antipathy.sluice.core.store.CounterStore
import org.antipathy.sluice.core.store.InMemoryCounterStore
import org.antipathy.sluice.core.store.RedisCounterStore
import org.antipathy.sluice.core.store.ThrottledCounterStore
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("org.antipathy.sluice.api.server.Application")

/** Ktor EngineMain entry point. Config-driven module loading via application.yaml. */
fun main(
    args: Array<String>
) { // don't believe the hype, the real entry point is Application.module() below
  EngineMain.main(args)
}

/** Creates a plugin to close the redis connection cleanly on shutdown */
internal fun createRedisCleanUpPlugin(
    client: RedisClient,
    connection: StatefulRedisConnection<String, String>,
): ApplicationPlugin<Unit> {
  return createApplicationPlugin("RedisCleanupPlugin") {
    on(MonitoringEvent(ApplicationStopping)) {
      // We're shutting down here, the connection is going either way.
      try {
        connection.close()
      } catch (e: RedisException) {
        logger.error("Error closing connection", e)
      }
      try {
        client.close()
      } catch (e: RedisException) {
        logger.error("Error closing client", e)
      }
    }
  }
}

/** Builds a Redis-backed store and status checker. Registers cleanup via the provided installer. */
internal fun redisStore(
    redisUri: String,
    policyContext: PolicyContext,
    metrics: Metrics,
    installPlugin: (ApplicationPlugin<Unit>) -> Unit,
): Pair<CounterStore, StatusChecker> {

  val client = RedisClient.create(redisUri)
  val connection = client.connect()
  installPlugin(createRedisCleanUpPlugin(client, connection))

  val scriptLoader = ScriptLoader(connection)
  val algorithms =
      policyContext.requiredAlgorithms.associate { algorithmType ->
        try {
          algorithmType to redisAlgorithm(algorithmType, scriptLoader)
        } catch (e: RedisScriptMissingException) {
          metrics.trackLuaScriptLoadFailure(algorithmType.name)
          logger.error("Error script loading failed: $algorithmType", e)
          throw e
        }
      }
  val statusChecker =
      StatusChecker(
          PolicyStatus(policyContext.allPolicies.size, policyContext.policiesLoaded.toString())
      ) {
        try {
          val start = TimeSource.Monotonic.markNow()
          connection.async().ping().await()
          StoreStatus(
              type = "redis",
              status = StoreStatus.HEALTHY,
              latencyMS = start.elapsedNow().inWholeMilliseconds,
          )
        } catch (_: RedisException) {
          StoreStatus(
              type = "redis",
              status = StoreStatus.FAILED,
              latencyMS = 0,
          )
        }
      }

  return Pair(RedisCounterStore(algorithms), statusChecker)
}

/** Builds an in-memory store and status checker. No external dependencies, always healthy. */
internal fun inMemoryStore(policyContext: PolicyContext): Pair<CounterStore, StatusChecker> {
  logger.info("No Redis connection found, loading InMemoryCounterStore")
  val statusChecker =
      StatusChecker(
          PolicyStatus(policyContext.allPolicies.size, policyContext.policiesLoaded.toString())
      ) {
        StoreStatus(
            type = "in memory",
            status = StoreStatus.HEALTHY,
            latencyMS = 0,
        )
      }
  val store =
      InMemoryCounterStore(
          policyContext.requiredAlgorithms.associate { it to inMemoryAlgorithm(it) }
      )
  return Pair(store, statusChecker)
}

/** Composition root. Reads config, builds dependencies, installs plugins, mounts routes. */
fun Application.module() {

  val config = SluiceConfiguration.from(environment.config)

  install(ContentNegotiation) {
    json(
        Json {
          isLenient = false
          ignoreUnknownKeys = false
        }
    )
  }
  install(CallId) {
    header(HttpHeaders.XRequestId)
    generate { UUID.randomUUID().toString() }
    replyToHeader(HttpHeaders.XRequestId)
  }
  install(CallLogging) { callIdMdc("requestId") }

  val appMicrometerRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
  val metrics = PrometheusMetrics(appMicrometerRegistry)
  install(MicrometerMetrics) {
    registry = appMicrometerRegistry
    meterBinders =
        listOf(JvmMemoryMetrics(), JvmGcMetrics(), JvmThreadMetrics(), ProcessorMetrics())
  }

  val policyRegistry = YamlPolicyRegistry(config.policiesLocation)

  val policyContext =
      PolicyContext(policyRegistry.requiredAlgorithms(), Clock.System.now(), policyRegistry.all())

  policyContext.allPolicies.forEach { policy ->
    metrics.trackPolicyLoaded(policy, policyContext.policiesLoaded)
  }
  logger.info(
      "Loaded {} policies and {} algorithms",
      policyContext.allPolicies.size,
      policyContext.requiredAlgorithms.size,
  )

  val (baseStore, statusChecker) =
      if (config.redisUrl != null) {
        redisStore(config.redisUrl, policyContext, metrics) { plugin -> install(plugin) }
      } else {
        inMemoryStore(policyContext)
      }

  val withCircuitBreaker =
      config.circuitBreaker?.let {
        CircuitBreakerCounterStore(baseStore, it.failureThreshold, it.resetTimeout)
      } ?: baseStore

  val withThrottle =
      config.maxConcurrentRequests?.let { ThrottledCounterStore(it, withCircuitBreaker) }
          ?: withCircuitBreaker

  val finalStore = InstrumentedCounterStore(withThrottle, metrics)

  auth(config.apiKey)
  healthCheck(statusChecker)
  rateLimit(finalStore, policyRegistry, config.maxIdentifierLength, config.apiKey != null, metrics)
  metrics { appMicrometerRegistry.scrape() }
}

private fun Application.auth(apiKey: String?) {
  if (apiKey != null) {
    install(Authentication) {
      bearer("api-key") {
        authenticate { token ->
          if (token.token == apiKey) {
            UserIdPrincipal("authenticated-client")
          } else {
            null
          }
        }
      }
    }
  }
}
