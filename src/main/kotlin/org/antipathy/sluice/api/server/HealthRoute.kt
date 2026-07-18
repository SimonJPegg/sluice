package org.antipathy.sluice.api.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/** Simple healthcheck endpoints */
fun Application.healthCheck() {
  routing {
    get("/health/live") { call.respond(HttpStatusCode.OK) }
    get("/health/ready") { call.respond(HttpStatusCode.OK) }
  }
}
