package org.antipathy.sluice.api.routes

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.util.UUID
import kotlinx.serialization.json.Json
import org.antipathy.sluice.api.metrics.PrometheusMetrics
import org.antipathy.sluice.core.algorithm.inMemoryAlgorithm
import org.antipathy.sluice.core.policy.YamlPolicyRegistry
import org.antipathy.sluice.core.store.InMemoryCounterStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AuthenticationTest {

  private val totallySecureKey = "yep"

  private fun Application.testModule() {
    val policyRegistry =
        YamlPolicyRegistry(environment.config.property("rate-limit.policies.location").getString())
    val requiredAlgorithms = policyRegistry.requiredAlgorithms()

    val maxIdentifierLength = 256
    val store = InMemoryCounterStore(requiredAlgorithms.associate { it to inMemoryAlgorithm(it) })
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
    val appMicrometerRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    val metrics = PrometheusMetrics(appMicrometerRegistry)
    install(MicrometerMetrics) {
      registry = appMicrometerRegistry
      meterBinders =
          listOf(JvmMemoryMetrics(), JvmGcMetrics(), JvmThreadMetrics(), ProcessorMetrics())
    }

    install(Authentication) {
      bearer("api-key") {
        authenticate { token ->
          if (token.token == totallySecureKey) {
            UserIdPrincipal("authenticated-client")
          } else {
            null
          }
        }
      }
    }

    rateLimit(store, policyRegistry, maxIdentifierLength, true, metrics)
  }

  @Test
  fun `an ok response is returned when a valid token is provided`() = testApplication {
    environment { config = ApplicationConfig("src/test/resources/api/valid/simple.yaml") }
    application { testModule() }

    val response =
        client.post("/check") {
          header(HttpHeaders.Authorization, "Bearer $totallySecureKey")
          header(HttpHeaders.XRequestId, UUID.randomUUID().toString())
          contentType(ContentType.Application.Json)
          setBody("""{"key":"some-key","policyId":"api-global"}""")
        }
    assertEquals(HttpStatusCode.OK, response.status)
  }

  @Test
  fun `an unauthorized response is returned when no token is provided`() = testApplication {
    environment { config = ApplicationConfig("src/test/resources/api/valid/simple.yaml") }
    application { testModule() }

    val response =
        client.post("/check") {
          header(HttpHeaders.Authorization, "Bearer nah")
          header(HttpHeaders.XRequestId, UUID.randomUUID().toString())
          contentType(ContentType.Application.Json)
          setBody("""{"key":"some-key","policyId":"api-global"}""")
        }
    assertEquals(HttpStatusCode.Unauthorized, response.status)
  }
}
