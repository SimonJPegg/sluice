# Architecture Decisions

| # | Decision | Summary |
|---|----------|---------|
| 001 | [Algorithm selection](001-algorithm-selection.md) | All four algorithms, consumer picks per policy |
| 002 | [ConcurrentHashMap over Mutex](002-concurrenthashmap-over-mutex.md) | Per-key atomicity without managing locks |
| 003 | [Lua return contract](003-lua-return-contract.md) | Scripts return explicit allowed flag after sliding window log exposed a derivation bug |
| 004 | [Policy registry](004-policy-registry.md) | YAML at startup, all fields required, no defaults |
| 005 | [Logging levels](005-logging-levels.md) | Debug on hot path (off in prod), error for failures, info for lifecycle only |
