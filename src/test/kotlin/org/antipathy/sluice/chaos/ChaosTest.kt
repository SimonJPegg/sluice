package org.antipathy.sluice.chaos

import eu.rekawek.toxiproxy.model.ToxicDirection
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.bearer
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.nio.file.Paths
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.antipathy.sluice.api.metrics.PrometheusMetrics
import org.antipathy.sluice.api.routes.metrics
import org.antipathy.sluice.api.routes.rateLimit
import org.antipathy.sluice.api.store.InstrumentedCounterStore
import org.antipathy.sluice.core.algorithm.redis.ScriptLoader
import org.antipathy.sluice.core.algorithm.redisAlgorithm
import org.antipathy.sluice.core.policy.PolicyWatcher
import org.antipathy.sluice.core.policy.YamlPolicyRegistry
import org.antipathy.sluice.core.store.CircuitBreakerCounterStore
import org.antipathy.sluice.core.store.FailureModeCounterStore
import org.antipathy.sluice.core.store.RedisCounterStore
import org.antipathy.sluice.core.store.ThrottledCounterStore
import org.antipathy.sluice.redis.RedisTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("chaos")
class ChaosTest : RedisTest() {

  private val totallySecureAPIKey = "illNotSayIfYouDont"
  private val failureThreshold = 5
  private val resetTimeout = 5.seconds
  private val maxConcurrent = 100
  private val maxIdentifierLength = 256

  @Suppress("LongMethod") // wiring
  private fun Application.tzeentchModule(concurrency: Int = maxConcurrent) {
    val policyWatcher =
        PolicyWatcher(
            Paths.get(environment.config.property("rate-limit.policies.location").getString())
        )
    policyWatcher.load()
    launch { policyWatcher.start() }
    val policyRegistry = YamlPolicyRegistry(policyWatcher)
    val requiredAlgorithms = policyRegistry.requiredAlgorithms()

    val scriptLoader = ScriptLoader(redisConnection)
    val store =
        RedisCounterStore(requiredAlgorithms.associate { it to redisAlgorithm(it, scriptLoader) })

    val appMicrometerRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    val metrics = PrometheusMetrics(appMicrometerRegistry)

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
    install(MicrometerMetrics) {
      registry = appMicrometerRegistry
      meterBinders =
          listOf(JvmMemoryMetrics(), JvmGcMetrics(), JvmThreadMetrics(), ProcessorMetrics())
    }

    install(Authentication) {
      bearer("api-key") {
        authenticate { token ->
          if (token.token == totallySecureAPIKey) {
            UserIdPrincipal("authenticated-client")
          } else {
            null
          }
        }
      }
    }
    install(CallLogging) { callIdMdc("requestId") }

    rateLimit(
        InstrumentedCounterStore(
            ThrottledCounterStore(
                concurrency,
                FailureModeCounterStore(
                    CircuitBreakerCounterStore(
                        InstrumentedCounterStore(store, metrics, "store"),
                        failureThreshold,
                        resetTimeout,
                    ),
                ),
            ),
            metrics,
            "chain",
        ),
        policyRegistry,
        maxIdentifierLength,
        true,
        metrics,
    )
    metrics { appMicrometerRegistry.scrape() }
  }

  fun runChaosTest(conditionStarts: () -> Unit, conditionEnds: () -> Unit) = testApplication {
    environment { config = ApplicationConfig("src/test/resources/api/chaos/simple.yaml") }
    application { tzeentchModule(Int.MAX_VALUE) }

    val correlationID = UUID.randomUUID().toString()

    val getResponse: suspend (String, String) -> HttpResponse = { k, p ->
      client.post("/check") {
        header(HttpHeaders.XRequestId, correlationID)
        header(HttpHeaders.Authorization, "Bearer $totallySecureAPIKey")
        contentType(ContentType.Application.Json)
        setBody("""{"key":"$k","policyId":"$p"}""")
      }
    }

    (0..1000).forEachIndexed { index, _ ->
      when (index) {
        !in 100..900 -> {
          assertEquals(HttpStatusCode.OK, getResponse("open-key", "api-open").status)
          assertEquals(HttpStatusCode.OK, getResponse("closed-key", "api-closed").status)
        }
        100 -> {
          conditionStarts()
        }
        900 -> {
          conditionEnds()
          delay(resetTimeout)
        }
        in (101 + failureThreshold..899) -> {
          getResponse("closed-key", "api-closed")
        }
        else -> {
          val openResp = getResponse("open-key", "api-open")
          val closedResp = getResponse("closed-key", "api-closed")
          println("[$index] open=${openResp.status} closed=${closedResp.status}")
          assertEquals(HttpStatusCode.OK, openResp.status)
          assertEquals(HttpStatusCode.TooManyRequests, closedResp.status)
        }
      }
    }
  }

  @Test
  fun `Redis cluster refuses connection`() {
    runChaosTest({ proxy.disable() }, { proxy.enable() })
  }

  @Test
  fun `Connection to redis cluster is lost`() {
    runChaosTest(
        {
          proxy.toxics().timeout("cut-downstream", ToxicDirection.DOWNSTREAM, 0)
          proxy.toxics().timeout("cut-upstream", ToxicDirection.UPSTREAM, 0)
        },
        {
          proxy.toxics().get("cut-downstream").remove()
          proxy.toxics().get("cut-upstream").remove()
        },
    )
  }

  @Test
  fun `Network connection speed degrades`() {
    runChaosTest(
        { proxy.toxics().latency("slow", ToxicDirection.DOWNSTREAM, 300) },
        { proxy.toxics().get("slow").remove() },
    )
  }
}
