package org.antipathy.sluice.api.routes

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.lettuce.core.RedisException
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.coroutines.future.await
import org.antipathy.sluice.api.health.PolicyStatus
import org.antipathy.sluice.api.health.StatusChecker
import org.antipathy.sluice.api.health.StoreStatus
import org.antipathy.sluice.redis.RedisTest
import org.junit.jupiter.api.Test

class HealthStatusRouteTest : RedisTest() {

  @Test
  fun `should return 200 with status when redis is connected`() = testApplication {
    environment { config = ApplicationConfig("src/test/resources/api/valid/simple.yaml") }
    application {
      install(ContentNegotiation) { json() }
      healthCheck(
          StatusChecker(PolicyStatus(10, "2026-07-19T14:30:00.123456789Z")) {
            try {
              val start = TimeSource.Monotonic.markNow()
              connection.async().ping().await()
              StoreStatus(
                  type = "redis",
                  status = "connected",
                  latencyMS = start.elapsedNow().inWholeMilliseconds)
            } catch (_: RedisException) {
              StoreStatus(
                  type = "redis",
                  status = "connection failed",
                  latencyMS = 0,
              )
            }
          })
    }
    val response =
        client.get("/health/status") {
          header(HttpHeaders.Accept, ContentType.Application.Json.toString())
        }
    assertEquals(HttpStatusCode.OK, response.status)
    val body = response.bodyAsText()
    assertTrue(body.contains("connected"))
    assertTrue(body.contains("\"count\":10"))
  }

  @Test
  fun `should return error status code when redis connection fails`() = testApplication {
    environment { config = ApplicationConfig("src/test/resources/api/valid/simple.yaml") }
    application {
      install(ContentNegotiation) { json() }
      healthCheck(
          StatusChecker(PolicyStatus(10, "2026-07-19T14:30:00.123456789Z")) {
            try {
              val start = TimeSource.Monotonic.markNow()
              connection.async().ping().await()
              StoreStatus(
                  type = "redis",
                  status = "connected",
                  latencyMS = start.elapsedNow().inWholeMilliseconds)
            } catch (_: RedisException) {
              StoreStatus(
                  type = "redis",
                  status = "connection failed",
                  latencyMS = 0,
              )
            }
          })
    }
    connection.close()
    val response =
        client.get("/health/status") {
          header(HttpHeaders.Accept, ContentType.Application.Json.toString())
        }
    assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    val body = response.bodyAsText()
    assertTrue(body.contains("connection failed"))
    assertTrue(body.contains("\"count\":10"))
  }

  @Test
  fun `should return 200 when using in-memory store`() = testApplication {
    environment { config = ApplicationConfig("src/test/resources/api/valid/simple.yaml") }
    application {
      install(ContentNegotiation) { json() }
      healthCheck(
          StatusChecker(PolicyStatus(10, "2026-07-19T14:30:00.123456789Z")) {
            StoreStatus(
                type = "in memory",
                status = "connected",
                latencyMS = 0,
            )
          })
    }
    val response =
        client.get("/health/status") {
          header(HttpHeaders.Accept, ContentType.Application.Json.toString())
        }
    assertEquals(HttpStatusCode.OK, response.status)
    val body = response.bodyAsText()
    assertTrue(body.contains("connected"))
    assertTrue(body.contains("\"count\":10"))
  }
}
