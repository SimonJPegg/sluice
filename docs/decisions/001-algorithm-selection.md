# 001 — Algorithm Selection

**Date:** 2026-07-12

## Context

Four well-known rate limiting algorithms. Each trades off precision, memory, and burst tolerance differently:

- **Fixed window** — one counter, one TTL. Dead simple. Can burst 2x at the boundary between windows.
- **Sliding window counter** — two fixed windows, weighted overlap. Good enough precision, bounded memory. The "just use this" default in most literature.
- **Sliding window log** — stores every timestamp. Exact counting but memory grows linearly with traffic.
- **Token bucket** — refills tokens at a steady rate. Allows bursts up to bucket capacity. Different mental model entirely (capacity, not count).

Question: pick one, or support all four?

## Decision

All four. The algorithm is a field on the policy — consumers choose the strategy that fits. Fixed window first because it's trivial and proves the interface works before we tackle the harder ones.

The interface is `evaluate(key, policy) → RateLimitResponse` regardless of which algorithm runs underneath. If that interface survives all four without changing, the design is right. If it breaks, we fix it when it breaks — not before.

## Consequences

Four Lua scripts. Each is small, each is independently testable.

Adding a fifth algorithm later: add an enum value, write the script, done. Nothing above the store layer changes.

No default algorithm — consumers must pick one explicitly. Forces them to think about whether they want burst tolerance or strict counting.

Fixed window first means we get end-to-end working fast. The others slot in behind the same interface. If they don't, that's the signal the abstraction was wrong.
