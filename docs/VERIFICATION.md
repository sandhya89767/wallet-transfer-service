# Local verification record

Verified on **2026-09-13 (Asia/Kolkata)** in this workspace. This is local evidence, **not** a public deployment result.

## Environment

- macOS arm64, Temurin Java 25 compiling with Java 21 release target.
- Maven 3.9.11, Spring Boot 3.5.6.
- Colima Docker engine 29.5.2; actual PostgreSQL 17 containers (no H2).
- Production Docker image compiles and runs on Temurin Java 21.
- Colima VM configured with 4 CPUs / 6 GiB; local load results are not free-host benchmarks.

## Tests and results

| Check | Observed result |
|---|---|
| `./mvnw clean verify` with Colima socket variables | **PASS: 2 unit + 17 PostgreSQL integration cases; 0 skipped** |
| Deterministic foreign-key KEY SHARE / wallet-lock regression | PASS; concurrent claims both finish using NO KEY UPDATE |
| 50 concurrent get-or-create operations | One database wallet / one returned ID |
| 30 concurrent same-key transfers | Full results identical; one debit and credit / one database transfer |
| 30 conflicting-body retries sharing a key | Exactly 15 conflicts; one persisted movement |
| 400 opposite-direction transfers | All succeed, sum unchanged, no negative balances |
| 100 competing debits against limited funds | 10 successes / 90 terminal declines |
| Credit failure injected via temporary PostgreSQL CHECK | Debit and idempotency row roll back; corrected retry succeeds |
| Recipient BIGINT overflow | Clean terminal decline; both balances unchanged |
| Invalid tokens, ownership, UUID, fractional/string/null/overflow money | Appropriate 4xx; no unauthorized movement |
| Docker production build | PASS |
| Compose build/start/readiness | Both services healthy |
| Live container runtime user | `uid=10001(app)` |
| Two consecutive HTTP acceptance bursts | **541 requests each, all assertions PASS** |
| Two independent app containers sharing PostgreSQL | **541 requests alternating instances, all assertions PASS** |
| Database audit after three bursts | 15 wallets; 1,260,000 total paise; 1,203 successful transfers; 120 declines; zero duplicate user IDs or idempotency keys |
| App restart followed by replay of a committed transfer | Original ID/result returned; every wallet balance unchanged |
| Actual stdout JSON | Every captured line parses as JSON; all six request/domain event types include correlation IDs |
| Live `/metrics` | Latency histogram buckets and domain counters exported; no 5xx request samples on peer |
| Controlled PostgreSQL outage | Readiness and wallet writes return 503; write response includes Retry-After; liveness remains 200; services recover after database restart |
| Final constrained peer: PORT=10000, 512 MiB, 1 CPU | Healthy; fourth 541-request alternating-instance burst passes; observed memory about 181 MiB |

Four complete bursts: **2,164 HTTP requests**, all expected statuses/assertions satisfied. Burst client p99 values: 510.98 ms and 411.47 ms (single instance); 773.63 ms (alternating two instances); 937.13 ms (final constrained peer). These include client queueing/network/container contention and are not server p99 or production capacity claims. Domain counters are process-local and reset on restart; balances and transfer results are persistent. The deliberate outage generates expected 503 samples; the zero-5xx observation above applies to normal burst traffic before fault injection.

Example actual correlated event (demo identifiers):

```json
{"event":"transfer.declined","correlation_id":"burst-cc380763-a7a7-4eee-8393-3243e1b08897","transfer_id":"f9388896-7c42-40d3-8034-6d7f4635e5ce","amount_paise":200001,"reason":"INSUFFICIENT_FUNDS"}
```

## Reproduce

Follow the README test, Compose and two-instance commands. Unit/Failsafe reports are generated under target and intentionally not committed; CI uploads them with logs and metrics. The scripts generate fresh users/keys for each run (or consume an operator-issued fresh token bundle). Money from seed creation is excluded from transfer conservation comparisons.

## Limitations

- Public GitHub/Render/managed database provisioning, hosted CI, public logs/recording and deployed probes remain **pending**.
- No R3 reversal endpoint; no real funding/payment provider, managed identity, durable telemetry outbox, or production financial certification is claimed.
- Extreme overload can produce bounded-timeout 503 responses; clients must retry with the same key.