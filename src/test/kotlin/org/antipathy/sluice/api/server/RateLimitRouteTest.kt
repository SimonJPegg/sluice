package org.antipathy.sluice.api.server

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
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlinx.serialization.json.Json
import org.antipathy.sluice.core.policy.YamlPolicyRegistry
import org.antipathy.sluice.core.store.InMemoryCounterStore
import org.junit.jupiter.api.Assertions.assertEquals
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
          })
    }
    install(CallId) {
      header(HttpHeaders.XRequestId)
      generate { UUID.randomUUID().toString() }
      replyToHeader(HttpHeaders.XRequestId)
    }
    rateLimit(store, policyRegistry, maxIdentifierLength)
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
  fun `returns 429 with Retry-After header when request is denied`() = testApplication {
    environment { config = ApplicationConfig("src/test/resources/api/valid/simple.yaml") }
    application { testModule() }
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
    assertEquals("59", response.headers["Retry-After"])
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
    assertEquals("59", response.headers["Retry-After"])
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
          setBody("""{"key":"", "policyID":"api-global"}""")
        }
    assertEquals(HttpStatusCode.BadRequest, response.status)
    assertEquals(correlationID, response.headers[HttpHeaders.XRequestId])
    assertEquals("""{"error":"No Key was provided"}""", response.bodyAsText())
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
          setBody("""{"key":"some-key", "policyID":""}""")
        }
    assertEquals(HttpStatusCode.BadRequest, response.status)
    assertEquals(correlationID, response.headers[HttpHeaders.XRequestId])
    assertEquals("""{"error":"No policy was provided"}""", response.bodyAsText())
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
          setBody("""{"key":"some-key", "policyID":"allow-all-requests"}""")
        }
    assertEquals(HttpStatusCode.NotFound, response.status)
    assertEquals(correlationID, response.headers[HttpHeaders.XRequestId])
    assertEquals("""{"error":"Policy allow-all-requests does not exist"}""", response.bodyAsText())
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
          setBody("""{"key":"some\u0000key","policyID":"api-global"}""")
        }
    assertEquals(HttpStatusCode.BadRequest, response.status)
    assertEquals(correlationID, response.headers[HttpHeaders.XRequestId])
    assertEquals("""{"error":"Key does not match '^[a-zA-Z0-9\\-_:]+$'"}""", response.bodyAsText())
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
          setBody("""{"key":some-key "policyID":"api-global"}""")
        }
    assertEquals(HttpStatusCode.BadRequest, response.status)
    assertEquals(correlationID, response.headers[HttpHeaders.XRequestId])
    assertEquals("""{"error":"Invalid request body"}""", response.bodyAsText())
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
          setBody("""{"key":some-key "policyID":"api-global"}""")
        }
    assertEquals(HttpStatusCode.BadRequest, response.status)
    assertEquals(correlationID, response.headers[HttpHeaders.XRequestId])
    assertEquals("""{"error":"Invalid request body"}""", response.bodyAsText())
  }
}
