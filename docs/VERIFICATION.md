# Verification record

Verified on **2026-09-13 (Asia/Kolkata)**. The initial sections describe local evidence. Hosted observations below preserve the failed attempts and the subsequent strict correctness pass; measured latency is not a low-latency SLA pass.

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

## Hosted observations — 2026-09-13

- Public API: https://wallet-service-43q9.onrender.com. Initially readiness/liveness returned 200 UP, metrics returned 200, and the unauthenticated root returned the expected 401.
- The owner's strict run `535c2109-70ce-4084-b1f6-3ab6f784cb4b` passed the wallet race and identical-transfer retries, then failed during contention with application 503. Full hosted invariants were not verified.
- A subsequent observed run `24b04994-01b1-43b9-b024-401895d9feb7` with `BURST_MAX_RETRIES=3` also **FAILED**. It passed wallet race/replay checks, but recorded 392 HTTP responses: 54×201, 178×200, 1×409, 65×503 and 94×502. Some queued work was cancelled after failure; this was not a complete 541-operation run. Per-attempt p99 was 12,787 ms; logical-operation p99 including retries was 30,473.89 ms.
- Readiness and metrics returned HTTP 502 in the immediate recovery check. Render Events/Logs are needed to establish the cause; memory exhaustion, process restart, database connectivity and pool/lock timeouts are possibilities, not confirmed diagnoses. Further stress runs were stopped.
- A later read-only check returned readiness 200 UP and metrics 200. Process uptime was about 517 seconds, indicating a newer process than the initial deployment. Current-process pool counters showed 0 active, 0 pending and 0 acquisition timeouts (maximum 10 connections); those counters do not explain failures in an earlier process. Public health recovered, but hosted acceptance still has not passed. Confirm the restart reason in Render Events.
- Subsequent authenticated dashboard inspection confirmed the incident: Render Events reports **HTTP health check timed out after 5 seconds at 21:03 IST**, then recovery at 21:04. `DataSource health check failed` logs at 15:32:31Z and 15:33:01Z show connection acquisition timed out after 5000 ms: pool total=10, active=10, idle=0, waiting=14 and 8 respectively. This establishes readiness connection-pool starvation under load; a memory-limit failure was not shown by this event. The app is in Render Oregon and the Neon endpoint is in AWS Ohio (`us-east-2`); cross-region latency is a likely contributor, not a measured sole cause. Co-locating a replacement free app in Ohio requires a new Render service and another full test, not a weaker health check.
- Runner regression: seven deterministic retry unit tests passed. An additional **local strict** 541-request run passed with no unexpected statuses, attempt p99 418.39 ms. This does not override the hosted failures.

## Ohio remediation attempts — 2026-09-13

- Created an owner-approved replacement at https://wallet-service-ohio.onrender.com on Render Free (Ohio, 0.1 CPU, 512 MiB), using the existing Neon database, unchanged readiness check, and constrained JVM. Existing services were left intact.
- With pool 10 and unchanged application code, strict run `cd2f7156-0155-43a0-82ea-0d4ddf937be5` failed: 175 responses (54×201, 118×200, 1×409, 2×503). Readiness remained UP and public metrics confirmed two pool-acquisition timeouts. Co-location alone was insufficient.
- Set only this replacement service's `DB_POOL_SIZE` to 20 and verified the effective setting using metrics. Strict run `505ace29-a96b-44bd-9e84-1d3d7413ec4b` also failed: 277 responses (54×201, 220×200, 1×409, 2×503), attempt p99 11,703.9 ms. Increasing the pool alone was insufficient.
- Code remediation combines the two immutable-wallet identity lookups into one SELECT and obtains the finalized transfer with UPDATE RETURNING, including database-generated timestamps, removing two SQL round trips from a successful new transfer. Ownership is still checked before the key claim, wallet rows are still locked in UUID order with NO KEY UPDATE, and the guarded debit, separate credit and single transaction are unchanged. Retryable logs now include only a safe exception type, never exception messages containing credentials.
- Post-change `./mvnw verify` passed all 19 Java unit/integration cases, including concurrent full-response equality and credit rollback; seven Python retry-runner unit tests passed. Hosted performance of this code change remains unverified until deployment and retest.

## Optimized Ohio hosted correctness pass — 2026-09-13

- Commit `2ac27cb` passed [GitHub CI](https://github.com/sandhya89767/wallet-transfer-service/actions/runs/34768207474) and deployed successfully to https://wallet-service-ohio.onrender.com. Effective pool size: 20; Render Free Ohio, unchanged 0.1 CPU / 512 MiB plan.
- Strict run `2d921c58-cb03-4ea7-b702-1e977d3bdbda` **PASSED all 541 logical requests / 541 HTTP attempts with zero automatic retries**. Counts: 54×201, 483×200, 1×409, 1×401, 2×400; no 5xx. Wallet race and full replay equality passed; contention produced 400 successes and 40 insufficient-funds declines; balances remained 100,000 paise each with total 200,000 conserved; declined replay and validation passed.
- Client attempt p99: **10,198.85 ms**; logical-operation p99: **10,198.92 ms**. This proves the assertions for this run, not a low-latency target or guaranteed capacity. Any assignment latency target must be assessed separately and this number disclosed.
- Post-test readiness/liveness returned 200 UP; metrics returned 200, pool timeouts=0, pending=0, recorded transfers=441, declines=40, idempotent replays=30. No post-test restart was observed in this check.
- Hosted post-redeploy persistence is verified below. An accessible recording and delivery of fresh reviewer tokens remain outstanding.

## Hosted redeploy persistence — 2026-09-13

- Prepared a fresh 7,500-paise transfer and recorded balances **92,500 / 27,500 paise** with correlation `persistence-0567f858-f42d-4489-85a2-e0cb369abe91`.
- Triggered an actual Render redeployment of commit `106d2b5` (deployment `dep-dajd2huk1f9s73d5s5m0`). After it became live, process-start metric changed from `1789316900.389` to `1789317460.429`.
- At **2026-09-13 16:40:59 UTC**, authenticated GET returned the exact original transfer `25566b28-bce8-4c78-9376-7a086af74103`. Reposting the same request and idempotency key returned that identical persisted response. Both wallet balances before and after replay remained **92,500 / 27,500**: **PASS, no second debit**.
- Reproducible operator utility: [submission_check.py](../scripts/submission_check.py). Hidden signing-key entry, separate reviewer/recording identities, owner-only private files, and Git exclusion; no signing key is saved. Four utility regression tests pass alongside seven retry tests.
- Five fresh reviewer-role tokens were generated and validated using read-only authenticated 404 probes, leaving the wallets uncreated for review. Expiry: **2026-09-14 16:36:51 UTC / 22:06:51 IST**. They have not yet been delivered; no tokens are included in this record.

## Limitations

- The [public GitHub repository](https://github.com/sandhya89767/wallet-transfer-service) is published. [Hosted CI passed](https://github.com/sandhya89767/wallet-transfer-service/actions/runs/34763347884) for implementation commit `a300e0e`.
- One optimized Ohio strict correctness run and the hosted redeploy-persistence check passed as recorded above. Public logs/recording and private reviewer-token delivery remain **pending**. Free-host latency/cold starts and the earlier failures must not be concealed.
- No R3 reversal endpoint; no real funding/payment provider, managed identity, durable telemetry outbox, or production financial certification is claimed.
- Extreme overload can produce bounded-timeout 503 responses; clients must retry with the same key.