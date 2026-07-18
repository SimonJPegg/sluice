package org.antipathy.sluice.api.server

import io.ktor.client.request.get
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
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.header
import io.ktor.server.testing.testApplication
import java.util.UUID
import org.antipathy.sluice.core.algorithm.redis.ScriptLoader
import org.antipathy.sluice.core.policy.YamlPolicyRegistry
import org.antipathy.sluice.core.store.RedisCounterStore
import org.antipathy.sluice.redis.RedisTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
class RateLimitEndToEndTest : RedisTest() {

  // wiring up here to inject the testContainer redis
  private fun Application.e2eModule() {
    val policyRegistry =
        YamlPolicyRegistry(environment.config.property("rate-limit.policies.location").getString())
    val requiredAlgorithms = policyRegistry.requiredAlgorithms()

    val maxIdentifierLength = 256
    val scriptLoader = ScriptLoader(connection)
    val store =
        RedisCounterStore(requiredAlgorithms.associate { it to redisAlgorithm(it, scriptLoader) })
    install(ContentNegotiation) { json() }
    install(CallId) {
      header(HttpHeaders.XRequestId)
      generate { UUID.randomUUID().toString() }
      replyToHeader(HttpHeaders.XRequestId)
    }

    healthCheck()
    rateLimit(store, policyRegistry, maxIdentifierLength)
  }

  @Test
  fun `full request lifecycle through Redis returns correct response and headers`() =
      testApplication {
        environment { config = ApplicationConfig("src/test/resources/api/valid/simple.yaml") }
        application { e2eModule() }
        val correlationID = UUID.randomUUID().toString()
        val response =
            client.post("/check") {
              header(HttpHeaders.XRequestId, correlationID)
              contentType(ContentType.Application.Json)
              setBody("""{"key":"some-key","policyID":"api-global"}""")
            }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(correlationID, response.headers[HttpHeaders.XRequestId])
        assertEquals("100", response.headers["X-RateLimit-Limit"])
        assertEquals("99", response.headers["X-RateLimit-Remaining"])
        assertEquals("60", response.headers["X-RateLimit-Reset"])
        assertEquals("""{"remaining":99,"limit":100,"resetIn":"PT1M"}""", response.bodyAsText())
      }

  @Test
  fun `multiple requests against same key eventually returns denied`() = testApplication {
    environment { config = ApplicationConfig("src/test/resources/api/valid/simple.yaml") }
    application { e2eModule() }
    val correlationID = UUID.randomUUID().toString()

    repeat(100) { // As Tom jones would say
      client.post("/check") {
        header(HttpHeaders.XRequestId, correlationID)
        contentType(ContentType.Application.Json)
        setBody("""{"key":"some-other-key","policyID":"api-global"}""")
      }
    }

    val response =
        client.post("/check") {
          header(HttpHeaders.XRequestId, correlationID)
          contentType(ContentType.Application.Json)
          setBody("""{"key":"some-other-key","policyID":"api-global"}""")
        }
    assertEquals(HttpStatusCode.TooManyRequests, response.status)
    assertEquals(correlationID, response.headers[HttpHeaders.XRequestId])
    assertEquals("60", response.headers["Retry-After"])
    assertEquals("""{"retryAfter":"PT1M"}""", response.bodyAsText())
  }
}
