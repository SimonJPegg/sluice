package org.antipathy.sluice.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.antipathy.sluice.api.health.StatusChecker
import org.antipathy.sluice.api.health.StoreStatus

/** Simple healthcheck endpoints */
fun Application.healthCheck(statusChecker: StatusChecker) {
  routing {
    get("/health/live") { call.respond(HttpStatusCode.OK) }
    get("/health/ready") {
      // we expect to run in k8s, tying this to the redis status would fail healthchecks and take
      // the pod down, removing the fail open option.
      call.respond(HttpStatusCode.OK)
    }
    get("/health/status") {
      val result = statusChecker.status()
      if (result.storeStatus.status.equals(StoreStatus.HEALTHY, ignoreCase = true)) {
        call.respond(HttpStatusCode.OK, result)
      } else {
        call.respond(HttpStatusCode.ServiceUnavailable, result)
      }
    }
  }
}
