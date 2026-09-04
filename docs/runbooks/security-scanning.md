# Runbook: CI dependency & image scanning

Realises ADR-008 §9.

## What runs

| Check | Where | Gate |
|---|---|---|
| OWASP Dependency-Check (Java CVEs) | `security-scan` job, `mvn -Psecurity-scan verify` | `failBuildOnCVSS=7` → fails on CVSS ≥ 7 |
| Trivy image scan (api / worker / gateway) | `security-scan` job | `severity: HIGH,CRITICAL`, `exit-code: 1`, `ignore-unfixed: true` |
| `npm audit` (web production tree) | `web` job | `--audit-level=high --omit=dev` → fails on a high/critical advisory |

The `security-scan` Maven profile is **opt-in** — a plain `mvn verify` (the
`backend` / `backend-it` jobs) does not run Dependency-Check, so local builds stay
fast.

## NVD feed

Dependency-Check downloads the NVD database on first run (multi-minute). CI:

- caches `~/.m2/repository/org/owasp/dependency-check-data` (`actions/cache`);
- passes `NVD_API_KEY` (repo secret) to remove the anonymous rate limit.

On a prolonged NVD outage the job may fail to update its feed. There is **no
`|| true`** — re-run the job after NVD recovers. Do not disable the gate.

## Suppressions

Two committed, `CODEOWNERS`-guarded files:

- `.github/dependency-check-suppressions.xml` — every `<suppress>` MUST carry
  `until="YYYY-MM-DDZ"` and a comment (CVE, why it does not apply, linked issue,
  review date).
- `.trivyignore` — every line MUST end with `# review-by:YYYY-MM-DD` and state
  why.

**Time-boxed only. No permanent entries. No severity downgrades.** A real
advisory is either fixed (bump the dependency; for a forced transitive bump use
`apps/web/package.json` `overrides` or a Maven `<dependencyManagement>` pin) or
suppressed here with a review date.

## Exit-criteria demo — "CI fails on a planted vulnerable dependency"

One-off, **not** a committed job:

1. Add to `apps/api/pom.xml`:
   ```xml
   <dependency>
     <groupId>commons-collections</groupId>
     <artifactId>commons-collections</artifactId>
     <version>3.2.1</version>
   </dependency>
   ```
   (CVE-2015-6420, CVSS 7.5 — unsafe deserialization.)
2. Push the branch → the `security-scan` job **fails** on `failBuildOnCVSS=7`.
3. Remove the dependency → the job returns to green.
