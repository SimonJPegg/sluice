package org.antipathy.sluice.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlin.time.TimeSource
import org.antipathy.sluice.api.metrics.Metrics
import org.antipathy.sluice.api.model.ErrorResponse
import org.antipathy.sluice.api.model.RateLimitRequest
import org.antipathy.sluice.api.model.RequestWithError
import org.antipathy.sluice.api.model.ValidRequest
import org.antipathy.sluice.api.model.toProcessed
import org.antipathy.sluice.api.model.toResponse
import org.antipathy.sluice.api.model.validate
import org.antipathy.sluice.core.policy.PolicyRegistry
import org.antipathy.sluice.core.store.CounterStore
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("org.antipathy.sluice.api.routes.RateLimitRoute")

/** Rate limit route module. Expects a working store and policy registry */
fun Application.rateLimit(
    store: CounterStore,
    policyRegistry: PolicyRegistry,
    maxIdentifierLength: Int,
    metrics: Metrics,
) {

  routing {
    post("/check") {
      val start = TimeSource.Monotonic.markNow()
      val request =
          try {
            call.receive<RateLimitRequest>()
          } catch (e: BadRequestException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
            logger.error("Invalid request body: call.request.toString()", e)
            return@post
          }
      logger.debug("Evaluating key={} policy={}", request.key, request.policyId)
      when (val result = request.validate(policyRegistry, maxIdentifierLength)) {
        is RequestWithError -> {
          result.toResponse()(call)
          metrics.trackValidationError(result)
        }
        is ValidRequest -> {
          val processed = store.evaluate(result.key, result.policy).toProcessed(result.policy)
          processed.toResponse()(call)
          logger.debug(
              "key={} policy={} result={} duration={}ms",
              result.key,
              result.policy.id,
              processed.javaClass.simpleName,
              start.elapsedNow().inWholeMilliseconds,
          )
          metrics.trackEvaluation(result.policy, processed, start.elapsedNow())
        }
      }
    }
  }
}
