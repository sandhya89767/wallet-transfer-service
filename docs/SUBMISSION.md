# R2 submission checklist

**Status: local implementation verified; public submission not yet complete.**

The selected assignment is Wallet & P2P Transfer (exercise-bank pages 7–9), not the other assignments in the bank. No front end is required. The reversal endpoint is the R3 live extension and is intentionally not included in the R2 feature claim.

## Required owner-provided links

| Deliverable | Status |
|---|---|
| Public GitHub repository | **Pending — publish through your account** |
| Public deployed API URL | **Pending — deploy the image with managed PostgreSQL** |
| Public logs or accessible burst/logs recording | **Pending — capture from the deployed service** |
| Passing hosted CI run | **Pending — workflow added, not yet run on GitHub** |
| Fresh reviewer bearer-token bundle | **Pending — issue for the deployed key and share privately** |

Do not substitute localhost, fabricated URLs, a private dashboard link, or the local test report for these requirements.

## Included artifacts

- [README / run, test and API instructions](../README.md)
- [One-command HTTP burst entry point](../scripts/burst.sh)
- [Exact assertions and multi-instance burst runner](../scripts/burst.py)
- [One-page design and AI disclosure](DESIGN.md)
- [Local verification results](VERIFICATION.md)
- [Deployment steps](DEPLOYMENT.md) and [Render Blueprint](../render.yaml)
- [Container](../Dockerfile), [Compose](../compose.yml), [CI](../.github/workflows/ci.yml)

## Before sending

- [ ] Review the code and explain NO KEY UPDATE versus UPDATE, foreign-key locks, and the idempotency transaction boundary without relying on this checklist.
- [ ] Review the honest AI-directed versus AI-decided disclosure; add only decisions you actually made.
- [ ] Publish under your own account with genuine provenance; do not fabricate a human development history.
- [ ] Verify CI and public readiness after deployment.
- [ ] Run all HTTP bursts against the **public** URL; preserve the output and correlation ID.
- [ ] Verify post-redeploy same-key retry and persisted balances.
- [ ] Show live JSON logs, insufficient-funds/replay events, and metrics in an accessible recording/link.
- [ ] Supply a fresh token bundle and document expiry and the cold-start behavior.
- [ ] Confirm the chosen app/database plans are actually free and note any expiry/quota limits.

## Submission message template

Wallet & P2P Transfer — Spring Boot / PostgreSQL

- API: **fill actual public URL**
- Repository: **fill actual public repository URL**
- Logs/recording: **fill accessible evidence URL**
- Run probes: follow README with the privately supplied token bundle.
- Design: docs/DESIGN.md in the public repository.
- Verification: append actual deployed test date and results, not only local results.