package org.antipathy.sluice.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.antipathy.sluice.core.policy.PolicyRegistry
import org.antipathy.sluice.core.store.CounterStore

/** Simple healthcheck endpoints */
fun Application.healthCheck() {
  routing {
    get("/health/live") { call.respond(HttpStatusCode.OK) }
    get("/health/ready") { call.respond(HttpStatusCode.OK) }
  }
}

/** Rate limit route module. Expects a working store and policy registry */
fun Application.rateLimit(store: CounterStore, policyRegistry: PolicyRegistry) {}
