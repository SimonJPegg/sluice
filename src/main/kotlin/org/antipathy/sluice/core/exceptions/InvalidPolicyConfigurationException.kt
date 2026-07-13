package org.antipathy.sluice.core.exceptions

/** So log readers know it's a config problem, not a runtime one. */
class InvalidPolicyConfigurationException(msg: String) : RuntimeException(msg)
