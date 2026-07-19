package org.antipathy.sluice.api.routes

import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/** Metric endpoints */
fun Application.metrics(scrape: () -> String) {
  routing { get("/metrics") { call.respondText(scrape()) } }
}
