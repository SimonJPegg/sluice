package org.antipathy.sluice.api.routes

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.assertTrue
import org.antipathy.sluice.api.server.module
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class HealthRouteTest {

  @Test
  fun `health liveness endpoint returns OK `() = testApplication {
    environment { config = ApplicationConfig("src/test/resources/api/valid/simple.yaml") }
    application { module() }
    val correlationID = "steve"
    val response = client.get("/health/live") { header(HttpHeaders.XRequestId, correlationID) }
    Assertions.assertEquals(HttpStatusCode.OK, response.status)
    Assertions.assertEquals(correlationID, response.headers[HttpHeaders.XRequestId])
  }

  @Test
  fun `health readiness endpoint returns OK `() = testApplication {
    environment { config = ApplicationConfig("src/test/resources/api/valid/simple.yaml") }
    application { module() }
    val response = client.get("/health/ready")
    Assertions.assertEquals(HttpStatusCode.OK, response.status)
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
