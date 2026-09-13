# Wallet Service

A concurrency-safe wallet and peer-to-peer transfer API built with Spring Boot, JDBC, and PostgreSQL. All money values are integer paise.

## Run locally

Prerequisites: Docker Engine 25+ with Compose v2+, and Python 3.9+ for the burst script. Java is not needed on the host when using Docker.

```bash
docker compose up --build -d --wait
```

Wait for the app to become healthy, then run all live concurrency probes:

```bash
./scripts/burst.sh all
```

To probe a deployed service with a privately supplied, fresh token bundle:

```bash
BASE_URL=https://your-service.example BURST_TOKENS_FILE=review-tokens.json ./scripts/burst.sh all
```

Each run checks 50 concurrent get-or-creates, 30 identical retries plus a conflicting body, 400 cross-transfers, 40 overdrafts, ownership/status reads, and input validation. It fails on any unexpected HTTP status, incomplete result set, differing replay body, incorrect balance delta, or nonconservation. Python uses exact integers and independent response buffers. Client p99 is reported, not claimed as a production capacity benchmark.

The local Compose configuration has a **public demo-only signing key** and enables exercise seed funding. Do not deploy that key publicly. The application itself requires `WALLET_AUTH_SECRET` (32+ bytes), and defaults to **seeding disabled**. Auth uses expiring HMAC-SHA256 tokens bound to a user ID; a bare `Bearer alice` is rejected. Offline issuance is restricted to the operator possessing the secret. There is no public token-issuance endpoint.

`POST /wallets` accepts optional positive `initial_balance_paise` only when `WALLET_ALLOW_SEEDING=true`. Missing body, `{}`, omitted or null seed values mean zero. Fractional/string seeds are rejected. This creates demo money once per new wallet, not on retries. Conservation applies to transfers, not explicit seed creation. This is an exercise service, not a real-money funding system.

## API

```bash
# Local demo only; for deployment set your private key outside source control.
export WALLET_AUTH_SECRET=local-demo-only-change-before-deploy-0123456789
ALICE_TOKEN=$(python3 scripts/issue_token.py alice)

# Get or create Alice's wallet
curl -sS http://localhost:8080/wallets \
  -H "Authorization: Bearer $ALICE_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"initial_balance_paise":100000}'

# Read a wallet owned by Alice
curl -sS http://localhost:8080/wallets/WALLET_ID \
  -H "Authorization: Bearer $ALICE_TOKEN"

# Transfer integer paise from Alice's wallet
curl -sS http://localhost:8080/transfers \
  -H "Authorization: Bearer $ALICE_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"from":"FROM_ID","to":"TO_ID","amount_paise":5000,"idempotency_key":"checkout-123"}'

# Read a transfer visible to Alice
curl -sS http://localhost:8080/transfers/TRANSFER_ID \
  -H "Authorization: Bearer $ALICE_TOKEN"
```

A debit without enough funds is persisted as `DECLINED` with `failure_reason: INSUFFICIENT_FUNDS`. Recipient overflow declines with `BALANCE_LIMIT_EXCEEDED`. Both return HTTP 200 and are terminal/idempotent. Successful creates and replays also return HTTP 200 with identical bodies. Wallet get-or-create returns 201 (including repeats). Reusing a transfer key with another valid body returns 409. Missing wallets return 404, unauthorized ownership 403, invalid/expired tokens 401, malformed/decimal/string/null amounts 400. Database outages/timeouts produce retryable 503; retry the **same** key. GET transfer is visible only to its sender or recipient.

## Test and package

The checked-in `mvnw` downloads Maven 3.9.11 into the user's Maven cache when Maven is not installed globally.

```bash
./mvnw test                # unit tests only; does NOT prove the money invariants
./mvnw verify              # includes Testcontainers/PostgreSQL concurrency tests
./mvnw package
```

The Testcontainers tests require Docker and fail rather than skip if it is unavailable. CI runs `verify`, builds/starts Compose, runs live bursts, and uploads test reports and logs. No H2 or mocked database is used for concurrency assertions.

For macOS Colima, start Colima and expose its Docker socket to Testcontainers:

```bash
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
./mvnw clean verify
```

Homebrew's standalone `docker-compose` works in place of `docker compose` if the plugin is not registered.

Optional two-instance check (start the main stack first):

```bash
docker compose -f compose.yml -f compose.verify.yml up -d --wait
BASE_URLS=http://localhost:8080,http://localhost:8081 ./scripts/burst.sh all
```

Results and remaining submission obligations: [docs/VERIFICATION.md](docs/VERIFICATION.md), [docs/SUBMISSION.md](docs/SUBMISSION.md).

### Hosted transient failures

The burst runner remains **strict by default** (`BURST_MAX_RETRIES=0`): any unexpected 503 fails the run. For a separate resilience check, set `BURST_MAX_RETRIES=3` alongside the public `BASE_URL` and private credentials. This retries only correlated application `503 temporarily_unavailable` responses, at most three times, with the exact same request body/idempotency key, honoring the API's numeric `Retry-After` with backoff and jitter. It does not retry authentication errors, conflicts, other server errors, or transport failures, and does not lower concurrency or remove assertions.

Every retry is printed without credentials. The final summary (also emitted on failure) includes HTTP status counts, per-attempt p99, and logical-operation p99 including retry delays. A retry-mode pass is **not** a strict zero-error or latency-SLA pass. Keep both results as evidence. Run deterministic runner tests with `python3 -m unittest discover -s scripts -p 'test_*.py'`.

## Observability

- Health: `/actuator/health`; readiness `/actuator/health/readiness` includes PostgreSQL; liveness is independent of PostgreSQL.
- Prometheus metrics: `/metrics` (also `/actuator/prometheus`); these read-only diagnostic endpoints are public for evaluation.
- Correlation: supply `X-Correlation-Id`, or the server generates and returns one.
- Logs: JSON on stdout. Domain events include `transfer.created`, `wallet.debited`, `wallet.credited`, `transfer.declined`, and `transfer.idempotent_replay`. `request.completed` records status and duration. No auth headers or tokens are logged.

Useful PromQL queries:

```promql
# Request rate
sum(rate(http_server_requests_seconds_count[5m]))

# p99 latency
histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket[5m])))

# 5xx error rate
sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))

# Domain counters
wallet_transfers_recorded_total
wallet_transfers_declined_total{reason="insufficient_funds"}
wallet_transfers_idempotent_replays_total
```

## Deployment

Use the [Render Blueprint](render.yaml) and [deployment runbook](docs/DEPLOYMENT.md) for a free Render container plus external managed PostgreSQL. The replacement public deployment is https://wallet-service-ohio.onrender.com (Free Ohio, pool 20). Optimized commit `2ac27cb` passed one strict 541-request hosted correctness run without retries and remained healthy; client p99 was **10.2 seconds**, not a low-latency SLA pass. Earlier failed runs and the successful real redeploy-persistence check are retained in [the verification record](docs/VERIFICATION.md). The public recording and private reviewer-token delivery remain pending. Configure:

| Variable | Value |
|---|---|
| `DATABASE_URL` | JDBC URL such as `jdbc:postgresql://host:5432/database?sslmode=require` |
| `DATABASE_USER` | PostgreSQL user |
| `DATABASE_PASSWORD` | PostgreSQL password |
| `WALLET_AUTH_SECRET` | Required private signing key, at least 32 bytes; same across replicas |
| `WALLET_ALLOW_SEEDING` | `true` for isolated exercise fixtures; defaults to `false` |
| `PORT` | Platform port, normally supplied automatically |
| `DB_POOL_SIZE` | Optional, default `20` |

`DATABASE_URL` must be a **JDBC URL**, with user/password supplied separately. Use a direct PostgreSQL endpoint for Flyway and connection session settings, not a transaction-pooling proxy. Use TLS for public database connections. Keep database/password values and token bundles out of source control.

Use `/actuator/health/readiness` as the platform health-check path. The image health check honors `PORT`. The Render blueprint generates a private signing key. The [public repository](https://github.com/sandhya89767/wallet-transfer-service) is published and [hosted CI passed](https://github.com/sandhya89767/wallet-transfer-service/actions/runs/34768207474) for optimized implementation commit `2ac27cb`. The public recording and private reviewer-token delivery remain pending; see the submission checklist. R3 reversal is deliberately left for the requested live follow-up, not represented as an implemented R2 feature.

See [docs/DESIGN.md](docs/DESIGN.md) for the one-page design and trade-off discussion.