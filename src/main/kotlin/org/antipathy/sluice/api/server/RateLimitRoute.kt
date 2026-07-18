package org.antipathy.sluice.api.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.antipathy.sluice.api.model.ErrorResponse
import org.antipathy.sluice.api.model.RateLimitRequest
import org.antipathy.sluice.api.model.RequestWithError
import org.antipathy.sluice.api.model.ValidRequest
import org.antipathy.sluice.core.policy.PolicyRegistry
import org.antipathy.sluice.core.store.CounterStore

/** Rate limit route module. Expects a working store and policy registry */
fun Application.rateLimit(
    store: CounterStore,
    policyRegistry: PolicyRegistry,
    maxIdentifierLength: Int
) {

  routing {
    post("/check") {
      val request =
          try {
            call.receive<RateLimitRequest>()
          } catch (_: BadRequestException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
            return@post
          }

      when (val result = request.validate(policyRegistry, maxIdentifierLength)) {
        is RequestWithError -> result.toResponse()(call)
        is ValidRequest -> {
          val processed = store.evaluate(result.key, result.policy).toProcessed(result.policy)
          processed.toResponse()(call)
        }
      }
    }
  }
}
