package org.antipathy.sluice.api.metrics

import io.micrometer.core.instrument.Tags
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.toJavaDuration
import org.antipathy.sluice.api.model.InvalidKeyRequest
import org.antipathy.sluice.api.model.InvalidPolicyRequest
import org.antipathy.sluice.api.model.MissingKeyRequest
import org.antipathy.sluice.api.model.MissingPolicyRequest
import org.antipathy.sluice.api.model.PolicyNotFoundRequest
import org.antipathy.sluice.api.model.RequestWithError
import org.antipathy.sluice.core.model.Allowed
import org.antipathy.sluice.core.model.Denied
import org.antipathy.sluice.core.model.Failed
import org.antipathy.sluice.core.model.FailedClosed
import org.antipathy.sluice.core.model.FailedOpen
import org.antipathy.sluice.core.model.RateLimitResponse
import org.antipathy.sluice.core.policy.Policy

/** Shim interface to avoid passing the MeterRegistry around (which is far too big for my liking) */
interface Metrics {

  /** Tracks whether requests are being allowed, denied, or failing — and how long each takes. */
  fun trackEvaluation(policy: Policy, result: RateLimitResponse, duration: Duration)

  /** Tracks client misuse: bad input that never reaches evaluation. */
  fun trackValidationError(error: RequestWithError)

  /** Tracks Redis latency so we know when the backend is slow before it's dead. */
  fun trackStoreDuration(command: String, duration: Duration)

  /** Tracks Redis failures by type so we can distinguish timeouts from connection drops. */
  fun trackStoreError(type: String)

  /**
   * Tracks Lua script load failures — if these spike, Redis was restarted and scripts need
   * re-registering.
   */
  fun trackLuaScriptLoadFailure(script: String)

  fun trackRedisScriptLoaded(name: String)

  /** Records when each policy was loaded so stale config is detectable from a dashboard. */
  fun trackPolicyLoaded(policy: Policy, instant: Instant)

  /** Records that a change to the policies occured, but they were not loaded */
  fun trackPolicyReloadFailure()
}

/** Prometheus implementation of the Metric interface */
class PrometheusMetrics(private val registry: PrometheusMeterRegistry) : Metrics {

  private val policyTimestamps = ConcurrentHashMap<String, AtomicLong>()

  override fun trackEvaluation(policy: Policy, result: RateLimitResponse, duration: Duration) {

    val resultTag =
        when (result) {
          is Allowed -> "allowed"
          is Denied -> "denied"
          is Failed -> "failed"
          is FailedOpen -> "failed_open"
          is FailedClosed -> "failed_closed"
        }
    registry
        .counter("sluice_request_outcomes", "policy", policy.id, "result", resultTag)
        .increment()
    registry
        .timer("sluice_request_duration", "policy", policy.id, "result", resultTag)
        .record(duration.toJavaDuration())
  }

  override fun trackValidationError(error: RequestWithError) {
    val tag =
        when (error) {
          is MissingKeyRequest -> "missing_key"
          is MissingPolicyRequest -> "missing_policy"
          is PolicyNotFoundRequest -> "policy_not_found"
          is InvalidKeyRequest -> "invalid_key"
          is InvalidPolicyRequest -> "invalid_policy"
        }
    registry.counter("sluice_validation_errors", "error", tag).increment()
  }

  override fun trackStoreDuration(command: String, duration: Duration) {
    registry
        .timer("sluice_store_command_duration", "command", command)
        .record(duration.toJavaDuration())
  }

  override fun trackStoreError(type: String) {
    registry.counter("sluice_store_error", "type", type).increment()
  }

  override fun trackLuaScriptLoadFailure(script: String) {
    registry.counter("sluice_script_load_failed", "script", script).increment()
  }

  override fun trackPolicyLoaded(policy: Policy, instant: Instant) {
    val holder =
        policyTimestamps.computeIfAbsent(policy.id) { id ->
          val ref = AtomicLong(0)
          registry.gauge("sluice_policy_loaded_timestamp", Tags.of("policy", id), ref) {
            it.get().toDouble()
          }
          ref
        }
    holder.set(instant.epochSeconds)
  }

  override fun trackPolicyReloadFailure() {
    registry.counter("sluice_policy_reload_error").increment()
  }

  override fun trackRedisScriptLoaded(name: String) {
    registry.counter("sluice_script_load_total", "script", name).increment()
  }
}
