# Clock Skew and Multi-Replica Behaviour

Redis is the clock for all Redis-backed algorithms. Time-based decisions (window boundaries,
token refill calculations, timestamp comparisons) happen inside Lua scripts executing on a
single Redis node. Sluice replicas do not use their own system clocks for rate limit decisions.

This means:

- Multiple Sluice replicas can run concurrently without clock synchronisation concerns.
- If Redis's clock drifts, all replicas drift identically — decisions remain consistent.
- In-memory mode uses `System.currentTimeMillis()` on the JVM, so multi-instance in-memory
  deployments would be subject to clock skew. This is acceptable because in-memory mode is
  for single-node testing, not multi-replica production use.
