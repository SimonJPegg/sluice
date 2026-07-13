package org.antipathy.sluice.exceptions

/** Distinct from generic exceptions so log readers immediately know it's config, not runtime. */
class InvalidPolicyConfigurationException(msg: String): RuntimeException(msg)
