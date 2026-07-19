package org.antipathy.sluice.api.metrics

import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import org.antipathy.sluice.api.model.AllowedRequest
import org.antipathy.sluice.api.model.DeniedRequest
import org.antipathy.sluice.api.model.FailedRequest
import org.antipathy.sluice.api.model.InvalidKeyRequest
import org.antipathy.sluice.api.model.InvalidPolicyRequest
import org.antipathy.sluice.api.model.MissingKeyRequest
import org.antipathy.sluice.api.model.MissingPolicyRequest
import org.antipathy.sluice.api.model.PolicyNotFoundRequest
import org.antipathy.sluice.core.policy.AlgorithmType
import org.antipathy.sluice.core.policy.FailType
import org.antipathy.sluice.core.policy.Policy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PrometheusMetricsTest {

  private val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
  private val metrics = PrometheusMetrics(registry)
  private val defaultPolicy =
      Policy("somePolicy", 100u, FailType.OPEN, 1.minutes, AlgorithmType.FIXED_WINDOW)

  @Test
  fun `should increment counter for allowed evaluation`() {
    val allowed = AllowedRequest(1, 100, 1.seconds)
    metrics.trackEvaluation(defaultPolicy, allowed, 1.seconds)
    val counter =
        registry.counter("sluice_request_outcomes", "policy", defaultPolicy.id, "result", "allowed")
    assertEquals(1.0, counter.count())
  }

  @Test
  fun `should increment counter for denied evaluation`() {
    val denied = DeniedRequest(1.seconds)
    metrics.trackEvaluation(defaultPolicy, denied, 1.seconds)
    val counter =
        registry.counter("sluice_request_outcomes", "policy", defaultPolicy.id, "result", "denied")
    assertEquals(1.0, counter.count())
  }

  @Test
  fun `should increment counter for failed evaluation`() {
    val failed = FailedRequest("Gremlins")
    metrics.trackEvaluation(defaultPolicy, failed, 1.seconds)
    val counter =
        registry.counter("sluice_request_outcomes", "policy", defaultPolicy.id, "result", "failed")
    assertEquals(1.0, counter.count())
  }

  @Test
  fun `should record duration for evaluation`() {
    val allowed = AllowedRequest(1, 100, 1.seconds)
    metrics.trackEvaluation(defaultPolicy, allowed, 1.seconds)

    val timer =
        registry.timer("sluice_request_duration", "policy", defaultPolicy.id, "result", "allowed")
    assertEquals(1, timer.count())
  }

  @Test
  fun `should increment validation error counter with correct tag for each error type`() {
    val cases =
        mapOf(
            MissingKeyRequest() to "missing_key",
            MissingPolicyRequest() to "missing_policy",
            PolicyNotFoundRequest("unknown") to "policy_not_found",
            InvalidKeyRequest("bad key") to "invalid_key",
            InvalidPolicyRequest("bad policy") to "invalid_policy",
        )

    cases.forEach { (error, expectedTag) ->
      metrics.trackValidationError(error)
      val counter = registry.counter("sluice_validation_errors", "error", expectedTag)
      assertEquals(1.0, counter.count(), "Expected counter for $expectedTag to be 1.0")
    }
  }

  @Test
  fun `should record store duration`() {
    metrics.trackStoreDuration("evaluate", 50.milliseconds)
    val timer = registry.timer("sluice_store_command_duration", "command", "evaluate")
    assertEquals(1, timer.count())
  }

  @Test
  fun `should increment store error counter`() {
    val error = "key_missing"
    metrics.trackStoreError(error)
    val count = registry.counter("sluice_store_error", "type", error)
    assertEquals(1.0, count.count())
  }

  @Test
  fun `should increment script load failure counter`() {
    metrics.trackLuaScriptLoadFailure(AlgorithmType.FIXED_WINDOW.name)
    val count =
        registry.counter("sluice_script_load_failed", "script", AlgorithmType.FIXED_WINDOW.name)
    assertEquals(1.0, count.count())
  }

  @Test
  fun `should set policy loaded timestamp gauge`() {
    metrics.trackPolicyLoaded(defaultPolicy, Instant.fromEpochMilliseconds(0))
    val gauge =
        registry.get("sluice_policy_loaded_timestamp").tag("policy", defaultPolicy.id).gauge()
    assertEquals(0.0, gauge.value())
  }
}
