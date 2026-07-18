package org.antipathy.sluice.api

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HealthCheckRouteTest {

  @Test
  fun `health liveness endpoint returns OK `() = testApplication {
    environment { config = ApplicationConfig("src/test/resources/api/valid/simple.yaml") }
    application { module() }
    val correlationID = "steve"
    val response = client.get("/health/live") { header(HttpHeaders.XRequestId, correlationID) }
    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals(correlationID, response.headers[HttpHeaders.XRequestId])
  }

  @Test
  fun `health readiness endpoint returns OK `() = testApplication {
    environment { config = ApplicationConfig("src/test/resources/api/valid/simple.yaml") }
    application { module() }
    val response = client.get("/health/ready")
    assertEquals(HttpStatusCode.OK, response.status)
    assertTrue {
      try {
        UUID.fromString(response.headers[HttpHeaders.XRequestId])
        true
      } catch (_: IllegalArgumentException) {
        false
      }
    }
  }
}
