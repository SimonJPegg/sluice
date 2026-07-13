# Sluice

A standalone rate limiting service. You ask "can I do this?" over HTTP, it tells you yes or no with a bit of context.

## Status

Work in progress. Building this to learn Kotlin properly.

Currently implemented:
- Fixed window algorithm (in-memory + Redis Lua script)
- Sliding window counter algorithm (in-memory + Redis Lua script)
- Strategy pattern — algorithms are pluggable, stores are thin dispatchers
- Concurrent access tested and proven (ConcurrentHashMap.compute + Redis atomicity)
- Fail-open/fail-closed error handling when Redis is unavailable

Coming: sliding window log, token bucket, HTTP API, metrics, Helm chart.

## Tech

Kotlin 2.x, Ktor, Lettuce (Redis), Micrometer. Runs on JVM 21. Will deploy to a homelab Kubernetes cluster eventually.

## Why

Most rate limiters are either a library or a gateway feature (not testable). This is a service that owns its decisions and exposes them as observable metrics.

Also I wanted a project where concurrency, atomicity, and time semantics matter.
