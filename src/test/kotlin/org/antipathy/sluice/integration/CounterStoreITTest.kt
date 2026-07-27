package org.antipathy.sluice.integration

import eu.rekawek.toxiproxy.model.ToxicDirection
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
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
import io.ktor.server.engine.embeddedServer
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.netty.Netty
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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.antipathy.sluice.api.metrics.PrometheusMetrics
import org.antipathy.sluice.api.routes.metrics
import org.antipathy.sluice.api.routes.rateLimit
import org.antipathy.sluice.api.store.InstrumentedCounterStore
import org.antipathy.sluice.core.algorithm.redis.ScriptLoader
import org.antipathy.sluice.core.algorithm.redisAlgorithm
import org.antipathy.sluice.core.policy.YamlPolicyRegistry
import org.antipathy.sluice.core.store.CircuitBreakerCounterStore
import org.antipathy.sluice.core.store.FailureModeCounterStore
import org.antipathy.sluice.core.store.RedisCounterStore
import org.antipathy.sluice.core.store.ThrottledCounterStore
import org.antipathy.sluice.redis.RedisTest
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
class CounterStoreITTest : RedisTest() {

  private val totallySecureAPIKey = "noOneWillEverKnow"
  private val failureThreshold = 5
  private val resetTimeout = 5.seconds
  private val maxConcurrent = 100
  private val maxIdentifierLength = 256

  @Suppress("LongMethod") // wiring
  private fun Application.testModule(policyPath: String?) {

    val policyRegistry =
        YamlPolicyRegistry(
            policyPath ?: environment.config.property("rate-limit.policies.location").getString()
        )
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
                maxConcurrent,
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

  @Test
  fun `Redis failure triggers circuit breaker, which triggers fail-open or closed per policy`() =
      testApplication {
        environment { config = ApplicationConfig("src/test/resources/api/valid/simple.yaml") }
        application { testModule(null) }

        val correlationID = UUID.randomUUID().toString()
        proxy.toxics().timeout("cut-downstream", ToxicDirection.DOWNSTREAM, 0)
        proxy.toxics().timeout("cut-upstream", ToxicDirection.UPSTREAM, 0)

        val shouldBeOpen: suspend () -> HttpResponse = {
          client.post("/check") {
            header(HttpHeaders.XRequestId, correlationID)
            header(HttpHeaders.Authorization, "Bearer $totallySecureAPIKey")
            contentType(ContentType.Application.Json)
            setBody("""{"key":"open-key","policyId":"api-global"}""")
          }
        }

        val shouldBeClosed: suspend () -> HttpResponse = {
          client.post("/check") {
            header(HttpHeaders.XRequestId, correlationID)
            header(HttpHeaders.Authorization, "Bearer $totallySecureAPIKey")
            contentType(ContentType.Application.Json)
            setBody("""{"key":"closed-key","policyId":"login-brute-force"}""")
          }
        }

        repeat((failureThreshold)) { assertEquals(HttpStatusCode.OK, shouldBeOpen().status) }
        proxy.toxics().get("cut-downstream").remove()
        proxy.toxics().get("cut-upstream").remove()

        delay(resetTimeout)
        assertEquals(HttpStatusCode.OK, shouldBeOpen().status)
        delay(1.seconds)
        assertEquals(HttpStatusCode.OK, shouldBeClosed().status)
      }

  @Test
  fun `concurrent requests beyond threshold get shed by throttle layer`() = runBlocking {
    val policyPath =
        requireNotNull(javaClass.classLoader.getResource("policy/valid")) {
              "Test policy file not found on classpath"
            }
            .path
    val server = embeddedServer(Netty, 8080) { testModule(policyPath) }.start(wait = false)
    val client = HttpClient()
    val responses =
        withContext(Dispatchers.IO) {
          (1..(maxConcurrent * 2))
              .map {
                async {
                  client.post("http://localhost:8080/check") {
                    header(HttpHeaders.Authorization, "Bearer $totallySecureAPIKey")
                    contentType(ContentType.Application.Json)
                    setBody("""{"key":"open-key","policyId":"api-global"}""")
                  }
                }
              }
              .awaitAll()
        }

    // this is non-deterministic, so "some of you might not make it" is the best we can do
    assertTrue { responses.count { it.status == HttpStatusCode.OK } > 0 }
    assertTrue { responses.count { it.status == HttpStatusCode.ServiceUnavailable } > 0 }

    server.stop(1000, 1000)
  }

  @Test
  fun `metrics are recorded at each layer`() = testApplication {
    environment { config = ApplicationConfig("src/test/resources/api/valid/simple.yaml") }
    application { testModule(null) }

    client.post("/check") {
      header(HttpHeaders.Authorization, "Bearer $totallySecureAPIKey")
      contentType(ContentType.Application.Json)
      setBody("""{"key":"open-key","policyId":"api-global"}""")
    }

    val result = client.get("/metrics")
    assertTrue(result.bodyAsText().contains("command=\"store\""))
    assertTrue(result.bodyAsText().contains("command=\"chain\""))
  }
}
