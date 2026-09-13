# Deployment runbook — owner account required

## 1. Review and publish

Review the implementation and AI disclosure. Publish the project to a public GitHub repository using your own account and authentic commit history. Do not upload the source exercise bank/internal rubric, signing key, database credentials or token bundles. Do not invent prior commits, author identities or claims of independent work. The generated GitHub Actions workflow runs database tests, Compose and HTTP bursts; wait for it to pass on GitHub rather than assuming the local result is a CI run.

## 2. Provision managed PostgreSQL

Choose a **Free** Neon project and a direct PostgreSQL connection (not the pooled/transaction proxy). Keep its user/password private. Use a JDBC URL of the form `jdbc:postgresql://HOST:5432/DATABASE?sslmode=require` and pass user/password separately. Copy values directly into the host's secrets UI; do not paste secrets into chat or commits. Prefer app and database regions near each other.

Neon's published Free plan is not a trial and requires no credit card; quotas/cold starts still apply. Render's free PostgreSQL is an alternative but **expires after 30 days**, so do not claim permanent database hosting. Review current [Neon pricing](https://neon.com/pricing) and [Render Free limits](https://render.com/docs/free) at signup. Do not upgrade to a paid plan just to complete this exercise.

## 3. Deploy the image

In Render, create a **Blueprint** from the public repository using [render.yaml](../render.yaml). Confirm the web service plan is **Free**, then provide `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` through Render's dashboard. Render generates `WALLET_AUTH_SECRET`; keep it private. Flyway runs at startup. The blueprint caps heap/metaspace/code cache for a small container, uses a DB pool of 10, and enables demo seeding for the exercise.

The image itself is built by Render from the same multi-stage Dockerfile verified locally; do not replace it with a native-runtime deployment. HTTP uses Render's `PORT`; both Docker and platform health checks reach `/actuator/health/readiness`. The app must report UP before testing. Store the resulting **real** public HTTPS URL in [SUBMISSION.md](SUBMISSION.md).

The generated key must stay identical across replicas/restarts. Changing it revokes old tokens. A production money service would replace this simple token scheme with a managed identity system and real funding controls; neither is claimed here.

## 4. Prepare reviewer tokens

On your own trusted machine, set `WALLET_AUTH_SECRET` privately to the same value used by the service. Generate a fresh bundle for each review run:

```bash
umask 077
python3 scripts/issue_token.py --bundle > review-tokens.json
```

Bundles contain signed tokens for five fresh users, valid for 24 hours. Give the **bundle**, not the signing key, to the reviewer using the agreed private channel. It is ignored by git. Reviewers need only Python 3.9+ and the public URL:

```bash
BASE_URL=https://YOUR-ACTUAL-SERVICE.onrender.com BURST_TOKENS_FILE=review-tokens.json ./scripts/burst.sh all
```

For individual requests, `python3 scripts/issue_token.py USER_ID` creates a one-hour token. Issue tokens for any additional identities requested during live probing. Public `/wallets` is never an identity-registration/token-issuance endpoint.

## 5. Public evidence

Warm the free host by visiting readiness before starting a burst. Render Free sleeps after idle time and cold starts can take about a minute; do not disguise that as zero downtime. Open Render's live logs and record the burst output beside the streaming JSON logs, including a `transfer.declined` and an idempotent replay with the matching `burst-...` correlation ID. Show `/metrics` too.

The PDF explicitly accepts a **screen recording** instead of public log access. Upload the recording to an accessible share link and test access in a signed-out browser. A private Render dashboard link alone does not satisfy this. Do not expose passwords, signing keys, reviewer tokens, or unrelated account data in the recording.

Re-run after a redeploy and confirm GET and a same-key replay return the persisted transfer without another debit. Record your actual deployed test date/results in the submission checklist. Local evidence is not proof of deployed behavior.

## Costs and capacity

Target ₹0 by staying within Free plans, keeping payment methods/paid upgrades out of this workflow and checking quotas. Free app CPU/memory, database scale-to-zero, bandwidth/build quotas and cold starts limit availability. A hot-wallet lock serializes transactions. The local p99 measurements are not a cloud throughput guarantee. No public account has been provisioned by this repository.