package org.antipathy.sluice.api.health

import kotlinx.serialization.Serializable

/**
 * Top-level response shape for /health/status — everything a human needs to assess service state.
 */
@Serializable data class SluiceStatus(val policies: PolicyStatus, val storeStatus: StoreStatus)

/** Static policy info captured at startup — stale config is detectable from the timestamp. */
@Serializable data class PolicyStatus(val count: Int, val loaded: String)

/** Store health snapshot — type identifies the backend, status and latency are live. */
@Serializable
data class StoreStatus(
    val type: String,
    val status: String,
    val latencyMS: Long,
) {
  companion object {
    const val HEALTHY = "connected"
    const val FAILED = "connection failed"
  }
}

/** Holds static config and a dynamic probe. Computes full status on demand per request. */
class StatusChecker(
    private val policyStatus: PolicyStatus,
    private val pingRedis: (suspend () -> StoreStatus)
) {
  /** Assembles the response — policy info is baked in, store status is computed live. */
  suspend fun status(): SluiceStatus =
      SluiceStatus(policies = policyStatus, storeStatus = pingRedis())
}
