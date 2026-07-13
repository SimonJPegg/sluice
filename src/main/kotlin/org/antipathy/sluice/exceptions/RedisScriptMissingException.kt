package org.antipathy.sluice.exceptions

class RedisScriptMissingException(name: String) : RuntimeException("$name is missing in scripts")
