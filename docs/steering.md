# Sluice — Project Steering

This is the engineering standard for this project. Every commit, every review, every decision
is measured against this. If something contradicts this doc, this doc wins.

---

## Technology Stack

- **Language:** Kotlin 2.x, targeting JVM 21
- **HTTP:** Ktor (lightweight, coroutine-native, no magic)
- **Redis client:** Lettuce (async, reactive, Kotlin coroutine support)
- **Serialization:** kotlinx-serialization
- **Metrics:** Micrometer + Prometheus registry
- **Logging:** Logback with structured JSON output
- **Build:** Gradle (Kotlin DSL)
- **Linting:** detekt (strict config, complexity rules enabled)
- **Testing:** JUnit 6 + Testcontainers (Redis) + MockK
- **Container:** Docker multi-stage build, distroless base
- **Deployment:** Helm chart, GitHub Actions CI, GHCR for images
- **Target:** Kubernetes homelab, dedicated namespace

---

## Coding Style

### Naming
- `camelCase` for functions, properties, variables
- `PascalCase` for classes, interfaces, objects, sealed types
- `SCREAMING_SNAKE_CASE` for compile-time constants only
- Package names: lowercase, no underscores

### Functions
- Prefer pure functions. Side effects at the edges.
- If a function exceeds ~10 lines, it's doing more than one thing. Decompose.
- Exception: orchestration functions that sequence steps — but each step should be extracted.
- Single-expression functions where they improve readability. Don't force them.
- Extension functions for operations that feel like they belong to a type but don't own it.
- Suspend functions for anything involving I/O. Don't block threads.

### Types
- Sealed interfaces for decision types and error hierarchies. Exhaust the `when`.
- Data classes for value objects. Immutable by default (val, not var).
- No `var` unless there's a documented reason (concurrency primitive, builder pattern).
- Type aliases for clarity when a generic type is used repeatedly.
- Null safety: don't use `!!`. If you can't avoid null, use `?.let {}` or `requireNotNull()` with a message.

### Error Handling
- Explicit exception classes per failure mode. Each with a docstring explaining when it fires.
- `Result<T>` or sealed return types over thrown exceptions for expected failures.
- Thrown exceptions for programmer errors (illegal state, missing config at startup).
- Never swallow exceptions silently. Log + rethrow, or handle explicitly.
- `runCatching {}` only at boundaries (HTTP handler, entry points). Not in domain logic.

### Coroutines
- Structured concurrency always. No `GlobalScope`.
- Use `supervisorScope` when child failures shouldn't cancel siblings.
- Timeouts via `withTimeout` / `withTimeoutOrNull` — don't let Redis calls hang.
- `Dispatchers.IO` for blocking calls (if any). Ktor's default dispatcher for everything else.

### Dependencies
- Pin exact versions in `gradle/libs.versions.toml` (version catalog).
- No floating versions, no `+` ranges.
- Prefer well-known, actively maintained libraries. If something looks niche, justify it.

---

## Testing

- **JUnit 6** with descriptive test names (`should deny when counter at limit`).
- **Isolated tests.** No shared mutable state between tests. No test ordering.
- **Testcontainers** for Redis integration tests. Real Redis, not mocks, for the counter store.
- **MockK** for unit-testing components in isolation (mock the CounterStore in API tests).
- **Parametrize** when testing multiple inputs against the same behaviour (`@ParameterizedTest`).
- **Test error paths first.** The happy path is obvious. The interesting behaviour is what happens when things break.
- **Test concurrency.** Coroutines make this easy — launch 100 concurrent requests at the same key and assert the count is correct.
- **Coverage proportional to blast radius.** The Lua script and counter logic need exhaustive testing. The health endpoint does not.

---

## Design Principles

- **Composition over inheritance.** Always. Interfaces at boundaries, composed via constructors.
- **Sealed hierarchies for state.** If there are N possible outcomes, model them as N subtypes. Let the compiler enforce exhaustiveness.
- **Make the state machine visible.** State transitions should be readable from the type signatures, not buried in if/else chains.
- **Define once, derive outputs.** Policy configuration defines behaviour. Metrics, documentation, and validation derive from it.
- **Constrain toward correctness.** The API should make it hard to call incorrectly. Required fields, typed IDs, clear error responses with actionable guidance.
- **No reflection, no annotation magic.** A grad who understands Kotlin should be able to trace the code from entry point to Redis and back without consulting framework docs.

---

## Documentation

- **Every public function/class** gets a single-line KDoc explaining *why* it exists.
- **Comment *why*** when it's not obvious from reading the code. Never comment *what*.
- **ADRs** in `docs/decisions/`. Format: Context, Decision, Consequences. One page max. Number chronologically.
- **README** is the landing page for a hiring manager. Clear, concise, shows you understand the problem.

---

## Git & Commits

- **Branch names:** `feature/<short-description>` or `fix/<short-description>`. No issue IDs (public repo, no tracker).
- **Commit messages:** Imperative mood, 50-char subject. Body explains what and why, not how. Wrap at 72.
- **One logical change per commit.** Don't mix refactors with features.
- **No WIP commits on main.** Squash or rewrite before merging.
- **Tag releases** with semver: `v1.0.0`, `v1.1.0`, etc.

---

## Complexity Rules

- Function > 10 lines → probably doing two things. Decompose.
- File > 200 lines → ask whether it's doing too many things.
- Cyclomatic complexity > 10 → detekt will flag it. Fix before committing.
- If `when` has more than 5 branches, the abstraction might be wrong.
- These are triggers to *think*, not hard limits.

---

## Self-Review Checklist

Before pushing:

1. Does every `when` on a sealed type exhaust all cases (no `else` branch)?
2. Are suspend functions only called from coroutine contexts?
3. Is every external call (Redis) wrapped in a timeout?
4. Are error cases tested, not just happy paths?
5. Does my addition meet this standard, even if I was lazy an hour ago?
6. Would someone debug this at 2am without me? (docstrings, logging, error messages)
7. Is detekt passing clean?
8. Can I explain every line if asked in an interview?

---

## How I Work On This

This project is for learning. AI (Kiro, Copilot, whatever) does not write the code.

- **Explain** concepts, patterns, tradeoffs. Point me at docs.
- **Help** me debug when I'm stuck. Ask me what I've tried first.
- **Review** what I've written. Hold me to the standard in this doc.
- **Do not write code for me.** Not functions, not tests, not "here's a starting point."
- If I ask "how do I do X?" — explain the approach, show the relevant API signature, link the docs. I type it.
- If I paste code and ask "is this right?" — review it, critique it, suggest improvements. Don't rewrite it.
- Scaffolding (build config, CI YAML) is an exception — that's plumbing, not learning.

The point is that I can explain every line because I wrote every line.

---

## What This Project Is Not

- Not a production rate limiter for a real company. It's a portfolio piece.
- Not a framework. It's a service. Don't over-abstract for consumers that don't exist.
- Not an excuse to learn 5 new things at once. Learn Kotlin. Use boring choices for everything else.
- Not AI-generated slop. Every line must be understood, explainable, and defensible.

---

## Quality Bar

> "Would a senior engineer at Monzo/Wise look at this repo and say: that person knows what they're doing?"

If the answer isn't yes, it's not done.
