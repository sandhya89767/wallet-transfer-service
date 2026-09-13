# Wallet & P2P transfer — one-page design

## Data model

`wallets`: UUID key, unique user ID, nonnegative BIGINT balance in paise. `transfers`: UUID key, globally unique idempotency key, source/destination foreign keys, positive BIGINT amount, terminal success/decline result. Flyway manages constraints. Java uses `long`; JSON decimals, strings, null and overflow are rejected. Get-or-create uses `INSERT … ON CONFLICT DO NOTHING` then a new SELECT. Explicitly enabled demo seed funding occurs only on the winning wallet insert; transfers never mint money.

## Transfer mechanism

One READ COMMITTED transaction checks ownership/existence, claims the transfer key, locks both wallets in PostgreSQL UUID order with **FOR NO KEY UPDATE**, conditionally debits (`balance >= amount`), credits, and finalizes the transfer. Insufficient funds or recipient BIGINT overflow persist a terminal decline without movement. Failed credits roll back the debit and key claim; a database fault-injection test verifies this.

The precise lock mode matters: inserting a transfer first acquires foreign-key KEY SHARE locks. Upgrading those to FOR UPDATE can deadlock even with sorted wallet order. NO KEY UPDATE is compatible with KEY SHARE, but excludes concurrent balance writers. We never mutate wallet IDs or user IDs. Sorted acquisition avoids A→B/B→A cycles. Rejected: app read/subtract/write (lost updates); unsorted conditional debit-then-credit (opposite-direction deadlocks); SERIALIZABLE everywhere (unnecessary retries for this bounded two-row invariant). Conditional UPDATE itself is not lock-free.

## Idempotency

The unique key and money movement commit together. A duplicate insert waits for the winner; the subsequent READ COMMITTED statement sees its terminal result. Same key/intent returns the complete original response, including declines; different source/destination/amount returns 409 for valid authorized requests. Authentication and ownership checks precede replay. Process-local caches are not involved. Crash before commit rolls everything back; lost HTTP response after commit is recovered with the same key.

## Consistency and availability

The PostgreSQL primary is authoritative. Outages/timeouts reject writes (503; retry same key), sacrificing availability rather than accepting uncertain money movement. Readiness includes the database. No async-replica writes or active-active promise. A hot wallet serializes throughput; adding app replicas does not remove that bottleneck. All replicas share the signing key and database.

## Operations and cost

Multi-stage Java 21 image, UID 10001, health check, read-only Compose filesystem. Expiring HMAC bearer tokens bind identity; the signing key is never given to reviewers. JSON correlation logs and Prometheus histograms/domain counters reflect committed operations. After-commit logging is not a durable audit outbox: a process crash can lose telemetry, never the committed balances. Local real-PostgreSQL tests, live bursts across two replicas and restart replay passed; this is not a production throughput/SLA claim. Render Free + Neon Free targets ₹0 within quotas; cold starts and quota suspensions reduce availability. Public deployment is pending, not claimed.

## AI disclosure

**Human-directed:** implement the supplied wallet exercise in Spring Boot; verify it for submission. **AI-decided/implemented:** JDBC/PostgreSQL locking, signed token format, validation, tests, containers, burst runner and initial documentation. AI performed local execution and corrected the lock/metrics defects. Human design ownership beyond these directions has not been asserted; candidate review and understanding are still required. No fabricated commits or authorship history.