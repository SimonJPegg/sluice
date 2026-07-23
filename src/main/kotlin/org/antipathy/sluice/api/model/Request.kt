package org.antipathy.sluice.api.model

import kotlin.time.Duration
import kotlinx.serialization.Serializable
import org.antipathy.sluice.core.model.FailureCategory
import org.antipathy.sluice.core.policy.Policy

/** Top-level sealed hierarchy for all request pipeline states. */
sealed interface Request

/** The minimum input needed to make a decision. */
@Serializable
data class RateLimitRequest(
    val key: String,
    val policyId: String,
) : Request

/** Output of the validation step. Either valid or an error — forces exhaustive handling. */
sealed interface ValidationResult : Request

/** Validation failures that short-circuit the pipeline to an error response. */
sealed interface RequestWithError : ValidationResult

/** Client sent a blank key. */
data class MissingKeyRequest(val message: String = "No Key was provided") : RequestWithError

/** Client sent a blank policy ID. */
data class MissingPolicyRequest(val message: String = "No policy was provided") : RequestWithError

/** Policy ID doesn't exist in the registry. */
data class PolicyNotFoundRequest(val policyName: String) : RequestWithError

/** Key failed validation rules (length, character whitelist). */
data class InvalidKeyRequest(val message: String) : RequestWithError

/** Policy ID failed validation rules (length). */
data class InvalidPolicyRequest(val message: String) : RequestWithError

/** Validation passed. Carries the resolved policy ready for evaluation. */
data class ValidRequest(val key: String, val policy: Policy) : ValidationResult

/** Result of evaluation, mapped from core types to API types for serialisation. */
sealed interface ProcessedRequest : Request

/** Request permitted. Carries remaining budget for response headers. */
@Serializable
data class AllowedRequest(val remaining: Int, val limit: Int, val resetIn: Duration) :
    ProcessedRequest

/** Request denied. Carries retry timing for response headers. */
@Serializable data class DeniedRequest(val retryAfter: Duration) : ProcessedRequest

/** Internal error during evaluation. */
@Serializable
data class FailedRequest(
    val reason: String,
    val failureCategory: FailureCategory,
    val retryAfter: Duration?,
) : ProcessedRequest
