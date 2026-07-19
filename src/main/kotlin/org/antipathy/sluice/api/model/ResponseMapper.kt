package org.antipathy.sluice.api.model

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import org.antipathy.sluice.core.model.Allowed
import org.antipathy.sluice.core.model.Denied
import org.antipathy.sluice.core.model.Failed
import org.antipathy.sluice.core.model.RateLimitResponse
import org.antipathy.sluice.core.policy.Policy

/** Maps core domain response to API response types. Boundary between core and HTTP layer. */
fun RateLimitResponse.toProcessed(policy: Policy): ProcessedRequest =
    when (this) {
      is Allowed -> AllowedRequest(remaining.toInt(), policy.limit.toInt(), resetIn)
      is Denied -> DeniedRequest(retryAfter)
      is Failed -> FailedRequest(reason)
    }

/** Maps validation errors to HTTP responses. Returns a curried function awaiting the call. */
fun RequestWithError.toResponse(): suspend (ApplicationCall) -> Unit =
    when (this) {
      is MissingKeyRequest -> { call ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(message))
          }
      is MissingPolicyRequest -> { call ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(message))
          }
      is InvalidPolicyRequest -> { call ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(message))
          }
      is InvalidKeyRequest -> { call ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(message))
          }
      is PolicyNotFoundRequest -> { call ->
            call.respond(
                HttpStatusCode.NotFound, ErrorResponse("Policy $policyName does not exist"))
          }
    }

/**
 * Maps evaluation results to HTTP responses with rate limit headers. Returns a curried function
 * awaiting the call.
 */
fun ProcessedRequest.toResponse(): suspend (ApplicationCall) -> Unit =
    when (this) {
      is AllowedRequest -> { call ->
            call.response.header("X-RateLimit-Limit", limit.toString())
            call.response.header("X-RateLimit-Remaining", remaining.toString())
            call.response.header("X-RateLimit-Reset", resetIn.inWholeSeconds.toString())
            call.respond(HttpStatusCode.OK, this)
          }
      is DeniedRequest -> { call ->
            call.response.header("Retry-After", retryAfter.inWholeSeconds.toString())
            call.respond(HttpStatusCode.TooManyRequests, this)
          }
      is FailedRequest -> { call -> call.respond(HttpStatusCode.InternalServerError, this) }
    }
