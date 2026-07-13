package org.antipathy.sluice.core.exceptions

/** Thrown at startup if a Lua script isn't on the classpath. Fail fast, not on first request. */
class RedisScriptMissingException(name: String) : RuntimeException("$name is missing in scripts")
