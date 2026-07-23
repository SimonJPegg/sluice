package org.antipathy.sluice.api.routes

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
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
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class RateLimitRouteTest {

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
    rateLimit(store, policyRegistry, maxIdentifierLength, metrics)
  }

  @Test
  fun `returns 200 with rate limit headers when request is allowed`() = testApplication {
    environment { config = ApplicationConfig("src/test/resources/api/valid/simple.yaml") }
    application { testModule() }
    val correlationID = UUID.randomUUID().toString()
    val response =
        client.post("/check") {
          header(HttpHeaders.XRequestId, correlationID)
          contentType(ContentType.Application.Json)
          setBody("""{"key":"some-key","policyId":"api-global"}""")
        }
    Assertions.assertEquals(HttpStatusCode.OK, response.status)
    Assertions.assertEquals(correlationID, response.headers[HttpHeaders.XRequestId])
    Assertions.assertEquals("100", response.headers["X-RateLimit-Limit"])
    Assertions.assertEquals("99", response.headers["X-RateLimit-Remaining"])
    Assertions.assertEquals("60", response.headers["X-RateLimit-Reset"])
    Assertions.assertEquals(
        """{"remaining":99,"limit":100,"resetIn":"PT1M"}""",
        response.bodyAsText(),
    )
  }

  @Test
  fun `returns 429 with Retry-After header when request is denied`() = testApplication {
    environment { config = ApplicationConfig("src/test/resources/api/valid/simple.yaml") }
    application { testModule() }
    val correlationID = UUID.randomUUID().toString()

    repeat(100) { // As Tom jones would say
      client.post("/check") {
        header(HttpHeaders.XRequestId, correlationID)
        contentType(ContentType.Application.Json)
        setBody("""{"key":"some-other-key","policyId":"api-global"}""")
      }
    }

    val response =
        client.post("/check") {
          header(HttpHeaders.XRequestId, correlationID)
          contentType(ContentType.Application.Json)
          setBody("""{"key":"some-other-key","policyId":"api-global"}""")
        }
    Assertions.assertEquals(HttpStatusCode.TooManyRequests, response.status)
    Assertions.assertEquals(correlationID, response.headers[HttpHeaders.XRequestId])
    Assertions.assertEquals("59", response.headers["Retry-After"])
  }

  @Test
  fun `returns 500 when evaluation fails`() = testApplication {
    environment { config = ApplicationConfig("src/test/resources/api/valid/simple.yaml") }
    application { testModule() }
    val correlationID = UUID.randomUUID().toString()

    repeat(100) { // As Tom jones would say
      client.post("/check") {
        header(HttpHeaders.XRequestId, correlationID)
        contentType(ContentType.Application.Json)
        setBody("""{"key":"some-other-key","policyId":"api-global"}""")
      }
    }

    val response =
        client.post("/check") {
          header(HttpHeaders.XRequestId, correlationID)
          contentType(ContentType.Application.Json)
          setBody("""{"key":"some-other-key","policyId":"api-global"}""")
        }
    Assertions.assertEquals(HttpStatusCode.TooManyRequests, response.status)
    Assertions.assertEquals(correlationID, response.headers[HttpHeaders.XRequestId])
    Assertions.assertEquals("59", response.headers["Retry-After"])
  }

  @Test
  fun `returns 400 when key is missing`() = testApplication {
    environment { config = ApplicationConfig("src/test/resources/api/valid/simple.yaml") }
    application { testModule() }
    val correlationID = UUID.randomUUID().toString()

    val response =
        client.post("/check") {
          header(HttpHeaders.XRequestId, correlationID)
          contentType(ContentType.Application.Json)
          setBody("""{"key":"", "policyId":"api-global"}""")
        }
    Assertions.assertEquals(HttpStatusCode.BadRequest, response.status)
    Assertions.assertEquals(correlationID, response.headers[HttpHeaders.XRequestId])
    Assertions.assertEquals("""{"error":"No Key was provided"}""", response.bodyAsText())
  }

  @Test
  fun `returns 400 when policy ID is missing`() = testApplication {
    environment { config = ApplicationConfig("src/test/resources/api/valid/simple.yaml") }
    application { testModule() }
    val correlationID = UUID.randomUUID().toString()

    val response =
        client.post("/check") {
          header(HttpHeaders.XRequestId, correlationID)
          contentType(ContentType.Application.Json)
          setBody("""{"key":"some-key", "policyId":""}""")
        }
    Assertions.assertEquals(HttpStatusCode.BadRequest, response.status)
    Assertions.assertEquals(correlationID, response.headers[HttpHeaders.XRequestId])
    Assertions.assertEquals("""{"error":"No policy was provided"}""", response.bodyAsText())
  }

  @Test
  fun `returns 404 when policy does not exist`() = testApplication {
    environment { config = ApplicationConfig("src/test/resources/api/valid/simple.yaml") }
    application { testModule() }
    val correlationID = UUID.randomUUID().toString()

    val response =
        client.post("/check") {
          header(HttpHeaders.XRequestId, correlationID)
          contentType(ContentType.Application.Json)
          setBody("""{"key":"some-key", "policyId":"allow-all-requests"}""")
        }
    Assertions.assertEquals(HttpStatusCode.NotFound, response.status)
    Assertions.assertEquals(correlationID, response.headers[HttpHeaders.XRequestId])
    Assertions.assertEquals(
        """{"error":"Policy allow-all-requests does not exist"}""",
        response.bodyAsText(),
    )
  }

  @Test
  fun `returns 400 when key contains invalid characters`() = testApplication {
    environment { config = ApplicationConfig("src/test/resources/api/valid/simple.yaml") }
    application { testModule() }
    val correlationID = UUID.randomUUID().toString()

    val response =
        client.post("/check") {
          header(HttpHeaders.XRequestId, correlationID)
          contentType(ContentType.Application.Json)
          setBody("""{"key":"some\u0000key","policyId":"api-global"}""")
        }
    Assertions.assertEquals(HttpStatusCode.BadRequest, response.status)
    Assertions.assertEquals(correlationID, response.headers[HttpHeaders.XRequestId])
    Assertions.assertEquals(
        """{"error":"Key does not match '^[a-zA-Z0-9\\-_:]+$'"}""",
        response.bodyAsText(),
    )
  }

  @Test
  fun `returns 400 when request body is malformed JSON`() = testApplication {
    environment { config = ApplicationConfig("src/test/resources/api/valid/simple.yaml") }
    application { testModule() }
    val correlationID = UUID.randomUUID().toString()

    val response =
        client.post("/check") {
          header(HttpHeaders.XRequestId, correlationID)
          contentType(ContentType.Application.Json)
          setBody("""{"key":some-key "policyId":"api-global"}""")
        }
    Assertions.assertEquals(HttpStatusCode.BadRequest, response.status)
    Assertions.assertEquals(correlationID, response.headers[HttpHeaders.XRequestId])
    Assertions.assertEquals("""{"error":"Invalid request body"}""", response.bodyAsText())
  }

  @Test
  fun `returns X-Request-ID header in response`() = testApplication {
    environment { config = ApplicationConfig("src/test/resources/api/valid/simple.yaml") }
    application { testModule() }
    val correlationID = UUID.randomUUID().toString()

    val response =
        client.post("/check") {
          header(HttpHeaders.XRequestId, correlationID)
          contentType(ContentType.Application.Json)
          setBody("""{"key":some-key "policyId":"api-global"}""")
        }
    Assertions.assertEquals(HttpStatusCode.BadRequest, response.status)
    Assertions.assertEquals(correlationID, response.headers[HttpHeaders.XRequestId])
    Assertions.assertEquals("""{"error":"Invalid request body"}""", response.bodyAsText())
  }
}
