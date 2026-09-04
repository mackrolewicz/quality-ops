# Triggering QualityOps runs from CI

`POST /api/v1/ci/runs` is a **Stripe-style idempotent** submit (ADR-007 §5). Every
call carries an `Idempotency-Key` header. The first call enqueues a run and stores
the `key -> run_id` mapping; **every** subsequent call with the same key **and the
same body** returns the *same* run with **HTTP 200**. Changing the body under a
reused key is a clean **`409 IDEMPOTENCY_KEY_CONFLICT`**.

- Retry the pipeline step freely — the same key returns the same run, nothing is
  double-triggered.
- 2D reuses the caller's JWT (`Authorization: Bearer <token>`). Scoped CI tokens
  are Phase 4.
- `Idempotency-Key` must match `[A-Za-z0-9_.\-]{1,200}`; blank / missing / oversize
  ⇒ `400 VALIDATION_ERROR`.
- `priority: "HIGH"` requires an OWNER/ADMIN token.

Poll `GET /api/v1/runs/{id}` until `status ∈ {PASSED, FAILED, CANCELLED}`. Fail the
job on anything other than `PASSED`. Or register a signed completion webhook
(`POST /api/v1/projects/{projectId}/webhooks`) and stop polling.

---

## GitHub Actions

```yaml
- name: Trigger QualityOps run
  env:
    QUALITYOPS_URL: ${{ vars.QUALITYOPS_URL }}
    QUALITYOPS_TOKEN: ${{ secrets.QUALITYOPS_TOKEN }}
  run: |
    IDEMPOTENCY_KEY="${{ github.run_id }}-${{ github.run_attempt }}"
    RUN_ID=$(curl -sS -X POST "$QUALITYOPS_URL/api/v1/ci/runs" \
      -H "Authorization: Bearer $QUALITYOPS_TOKEN" \
      -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
      -H 'Content-Type: application/json' \
      -d '{"projectId":"…","suiteId":"…","environmentId":"…"}' \
      | jq -r '.data.id')

    echo "run=$RUN_ID"
    while true; do
      STATUS=$(curl -sS "$QUALITYOPS_URL/api/v1/runs/$RUN_ID" \
        -H "Authorization: Bearer $QUALITYOPS_TOKEN" | jq -r '.data.status')
      echo "status=$STATUS"
      case "$STATUS" in
        PASSED) exit 0 ;;
        FAILED|CANCELLED) exit 1 ;;
      esac
      sleep 10
    done
```

Re-running the job re-uses `github.run_id` but bumps `github.run_attempt`, so a
genuine re-run gets a *new* run; a step retry within the same attempt re-uses the
key and returns the same run.

---

## GitLab CI

```yaml
qualityops:
  script:
    - IDEMPOTENCY_KEY="$CI_PIPELINE_ID-$CI_JOB_ID"
    - |
      RUN_ID=$(curl -sS -X POST "$QUALITYOPS_URL/api/v1/ci/runs" \
        -H "Authorization: Bearer $QUALITYOPS_TOKEN" \
        -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
        -H 'Content-Type: application/json' \
        -d '{"projectId":"…","suiteId":"…","environmentId":"…"}' | jq -r '.data.id')
    - |
      while true; do
        STATUS=$(curl -sS "$QUALITYOPS_URL/api/v1/runs/$RUN_ID" \
          -H "Authorization: Bearer $QUALITYOPS_TOKEN" | jq -r '.data.status')
        case "$STATUS" in
          PASSED) exit 0 ;;
          FAILED|CANCELLED) exit 1 ;;
        esac
        sleep 10
      done
  variables:
    QUALITYOPS_URL: "https://qualityops.example.com"
  # QUALITYOPS_TOKEN provided as a masked, protected CI/CD variable.
```

---

## Jenkins

No plugin — a `sh` step against the REST API (declarative or scripted pipeline).

```groovy
stage('QualityOps') {
  steps {
    withCredentials([string(credentialsId: 'qualityops-token', variable: 'QUALITYOPS_TOKEN')]) {
      sh '''
        IDEMPOTENCY_KEY="${BUILD_TAG}"
        RUN_ID=$(curl -sS -X POST "$QUALITYOPS_URL/api/v1/ci/runs" \
          -H "Authorization: Bearer $QUALITYOPS_TOKEN" \
          -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
          -H 'Content-Type: application/json' \
          -d '{"projectId":"…","suiteId":"…","environmentId":"…"}' | jq -r '.data.id')
        while true; do
          STATUS=$(curl -sS "$QUALITYOPS_URL/api/v1/runs/$RUN_ID" \
            -H "Authorization: Bearer $QUALITYOPS_TOKEN" | jq -r '.data.status')
          case "$STATUS" in
            PASSED) exit 0 ;;
            FAILED|CANCELLED) exit 1 ;;
          esac
          sleep 10
        done
      '''
    }
  }
}
```

`BUILD_TAG` is stable for a given build and changes on a genuine rebuild, so a
retried `sh` step re-uses the key (same run) while a rebuild gets a new run.

---

## Completion webhooks (optional — stop polling)

Register once per project:

```bash
curl -sS -X POST "$QUALITYOPS_URL/api/v1/projects/$PROJECT_ID/webhooks" \
  -H "Authorization: Bearer $QUALITYOPS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://ci.example.com/qualityops-hook","secret":"<>= 16 chars>"}'
```

Each delivery carries `X-QualityOps-Timestamp` and
`X-QualityOps-Signature: sha256=<hex HMAC-SHA256(secret, "<timestamp>.<body>")>`.
Verify the signature, dedupe on `X-QualityOps-Delivery`, and reject if
`|now - timestamp| > 300s`.
