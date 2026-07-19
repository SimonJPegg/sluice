package org.antipathy.sluice.api.model

import kotlin.time.Instant
import org.antipathy.sluice.core.policy.AlgorithmType
import org.antipathy.sluice.core.policy.Policy

/** Groups policy data that travels together during startup wiring — avoids long parameter lists. */
internal data class PolicyContext(
    val requiredAlgorithms: Set<AlgorithmType>,
    val policiesLoaded: Instant,
    val allPolicies: Set<Policy>,
)
