# AetherOps — End-to-End Demo (curl)

This script walks the full incident response lifecycle against a running AetherOps instance.
It is the human-readable counterpart to `AetherOpsEndToEndFlowTest`, which runs the same
flow automatically and verifies every assertion programmatically.

**What this demo proves:**

| Step | Capability |
|------|-----------|
| 1 | Tenant registration and plan assignment |
| 2 | Structured log ingestion with automatic secret masking |
| 3 | Automatic incident grouping from repeated error patterns |
| 4 | Evidence pack assembly — secrets absent from stored summaries |
| 5 | Evidence-backed AI analysis with calibrated confidence |
| 6 | Safe, phased runbook suggestion (TRIAGE → INVESTIGATE → VERIFY) |
| 7 | Structured postmortem draft, AI-enriched when analysis exists |
| 8–9 | Idempotent GET endpoints for latest runbook and postmortem |
| 10 | Per-tenant monthly usage metering |

---

## Prerequisites

- AetherOps running on `localhost:8080`
  (`mvn spring-boot:run` after exporting `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`)
- [`jq`](https://jqlang.github.io/jq/download/) installed for JSON parsing

```bash
export BASE="http://localhost:8080/api/v1"
```

---

## Step 1 — Register a tenant

```bash
TENANT=$(curl -s -X POST "$BASE/tenants" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "AcmeCorp",
    "plan": "TEAM",
    "aiMonthlyBudgetCents": 0,
    "retentionDays": 30
  }')

echo "$TENANT" | jq .
TENANT_ID=$(echo "$TENANT" | jq -r '.data.id')
echo "Tenant ID: $TENANT_ID"
```

**Expected response (abbreviated):**
```json
{
  "success": true,
  "data": {
    "id": "a1b2c3d4-...",
    "name": "AcmeCorp",
    "plan": "TEAM",
    "retentionDays": 30
  }
}
```

**What this proves:** Tenant isolation is the foundation of the platform. Every subsequent API
call passes `tenantId` as a query parameter, and every row in every table carries that UUID.
No data crosses tenant boundaries.

---

## Step 2 — Ingest log events with embedded secrets

Ingest three `ERROR` events from `payment-svc`. All three use the same error pattern and all
three contain a raw Bearer token and API key. Two things are demonstrated simultaneously:

1. **Masking fires on every ingest** — secrets do not reach storage on any event, not just
   specially crafted edge cases.
2. **Identical patterns group into one incident** — the grouping engine fingerprints the
   *masked* message, so all three events normalise to the same fingerprint and merge.

```bash
# The same message is used for all three events deliberately.
# This mirrors how real systems behave: the same error fires repeatedly.
# The embedded secrets prove masking is not optional or conditional.
MSG="Database connection pool exhausted — Bearer eyJhbGciOiJSUzI1NiJ9.secret.payload api_key=sk-prod-SECRET123"

curl -s -X POST "$BASE/events" \
  -H "Content-Type: application/json" \
  -d "{\"tenantId\":\"$TENANT_ID\",\"serviceId\":\"payment-svc\",\"level\":\"ERROR\",\"message\":\"$MSG\",\"occurredAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}" \
  | jq '{id: .data.id, message: .data.message}'

curl -s -X POST "$BASE/events" \
  -H "Content-Type: application/json" \
  -d "{\"tenantId\":\"$TENANT_ID\",\"serviceId\":\"payment-svc\",\"level\":\"ERROR\",\"message\":\"$MSG\",\"occurredAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}" \
  | jq '{id: .data.id, message: .data.message}'

curl -s -X POST "$BASE/events" \
  -H "Content-Type: application/json" \
  -d "{\"tenantId\":\"$TENANT_ID\",\"serviceId\":\"payment-svc\",\"level\":\"ERROR\",\"message\":\"$MSG\",\"occurredAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}" \
  | jq '{id: .data.id, message: .data.message}'
```

**Expected response for each event — secrets are gone before the response is returned:**
```json
{
  "id": "...",
  "message": "Database connection pool exhausted — Bearer [REDACTED] api_key=[REDACTED]"
}
```

**What this proves:** `SecretMaskingService` runs before `LogEventService` writes to the
database. The raw Bearer token and API key never touch storage. The ingestion response itself
already reflects the masked form — there is no window in which the raw value is accessible.

---

## Step 3 — Verify incident grouping

All three events normalise to the same fingerprint, so the grouping engine creates one
incident on the first event and updates it on each repeat. Severity starts at `MEDIUM`
(single ERROR) and escalates to `HIGH` at three events.

```bash
INCIDENTS=$(curl -s "$BASE/incidents?tenantId=$TENANT_ID")
echo "$INCIDENTS" | jq '.data.content[] | {id, title, serviceId, severity, status}'

INCIDENT_ID=$(echo "$INCIDENTS" | jq -r '.data.content[0].id')
echo "Incident ID: $INCIDENT_ID"
```

**Expected response:**
```json
[{
  "id": "...",
  "title": "[ERROR] payment-svc: Database connection pool exhausted — Bearer [REDACTED] ...",
  "serviceId": "payment-svc",
  "severity": "HIGH",
  "status": "OPEN"
}]
```

**What this proves:** Three separate ingest calls produce one incident, not three.
Because grouping uses the masked, normalised message, secret values cannot fragment incidents
or leak into the incident fingerprint.
Severity escalates automatically as the event count grows.

---

## Step 4 — Fetch the evidence pack (verify masking in storage)

```bash
PACK=$(curl -s "$BASE/incidents/$INCIDENT_ID/evidence-pack?tenantId=$TENANT_ID")
echo "$PACK" | jq '.data | {
  incidentId,
  totalEvidenceCount,
  evidence: [.evidence[] | {id, level, summary}]
}'
```

**Expected response:**
```json
{
  "totalEvidenceCount": 3,
  "evidence": [
    {"level": "ERROR", "summary": "Database connection pool exhausted — Bearer [REDACTED] api_key=[REDACTED]"},
    {"level": "ERROR", "summary": "Database connection pool exhausted — Bearer [REDACTED] api_key=[REDACTED]"},
    {"level": "ERROR", "summary": "Database connection pool exhausted — Bearer [REDACTED] api_key=[REDACTED]"}
  ]
}
```

Save an evidence item ID — it will appear in the analysis and timeline later:
```bash
EVIDENCE_ID=$(echo "$PACK" | jq -r '.data.evidence[0].id')
echo "Evidence ID: $EVIDENCE_ID"
```

**What this proves:** The raw secrets are absent from every stored evidence summary. No
downstream feature — analysis, runbook, or postmortem — ever receives the raw credentials.
The evidence pack is the only input the AI layer sees.

---

## Step 5 — Generate AI analysis

```bash
ANALYSIS=$(curl -s -X POST "$BASE/incidents/$INCIDENT_ID/analyze?tenantId=$TENANT_ID")
echo "$ANALYSIS" | jq '.data | {
  id,
  model,
  confidenceScore,
  analysisVersion,
  evidenceCount,
  likelyCause,
  unknowns,
  recommendedNextChecks
}'
```

**Expected response (abbreviated):**
```json
{
  "model": "mock-v1",
  "confidenceScore": 0.65,
  "analysisVersion": 1,
  "evidenceCount": 3,
  "likelyCause": "Based on log evidence [...]: Database connection pool exhausted ...",
  "unknowns": [
    "No metrics available — cannot assess resource utilisation or error rates",
    "No distributed traces available — cannot identify upstream or downstream service propagation",
    "No deployment history available — cannot correlate incident with recent changes"
  ],
  "recommendedNextChecks": [
    "Review service logs for the affected time window",
    "Check recent deployment history for the affected service",
    "Verify health of dependent services and databases",
    "Inspect connection pool and query latency metrics"
  ]
}
```

**What this proves:**
- `model: "mock-v1"` — the current provider is a deterministic mock; no external API calls.
- `evidenceRefs` (not shown above) contains the actual UUIDs from Step 4 — no hallucination.
- `confidenceScore` is capped at 0.65 because only log evidence is available; missing data sources are honestly listed in `unknowns`.
- `recommendedNextChecks` contains only read-only, observational actions.

---

## Step 6 — Generate runbook suggestion

```bash
RUNBOOK=$(curl -s -X POST "$BASE/incidents/$INCIDENT_ID/runbook?tenantId=$TENANT_ID")
echo "$RUNBOOK" | jq '.data | {
  runbookVersion,
  generationBasis,
  generatorVersion,
  stepCount: (.steps | length),
  phases: ([.steps[].phase] | unique)
}'

# View all steps with phase and action
echo "$RUNBOOK" | jq '.data.steps[] | {phase, action}'
```

**Expected response:**
```json
{
  "runbookVersion": 1,
  "generationBasis": "log-only",
  "generatorVersion": "mock-v1",
  "stepCount": 10,
  "phases": ["INVESTIGATE", "TRIAGE", "VERIFY"]
}
```

**What this proves:**
- Steps are organised into three phases: **TRIAGE** (assess and escalate), **INVESTIGATE**
  (evidence-grounded deep-dive), **VERIFY** (confirm root cause before acting).
- The INVESTIGATE steps that reference specific log events include the real evidence item
  UUID from Step 4 — they are grounded in actual stored data, not generic advice.
- No step suggests a destructive or autonomous action. Every step is read-only and
  safe for an on-call engineer to follow.

---

## Step 7 — Generate postmortem draft

Because `/analyze` ran in Step 5, the postmortem will be **AI-enriched**: it uses
`likelyCause` and `dataGaps` from the existing analysis rather than falling back to
evidence-only defaults. This is reflected in `analysisEnriched: true`.

```bash
POSTMORTEM=$(curl -s -X POST "$BASE/incidents/$INCIDENT_ID/postmortem?tenantId=$TENANT_ID")
echo "$POSTMORTEM" | jq '.data | {
  draftVersion,
  analysisEnriched,
  generatorVersion,
  incidentSummary,
  likelyCause,
  impact,
  dataGaps,
  followUpActions,
  timelineLength: (.timeline | length)
}'
```

**Expected response (abbreviated):**
```json
{
  "draftVersion": 1,
  "analysisEnriched": true,
  "generatorVersion": "mock-v1",
  "incidentSummary": "An incident was detected in service 'payment-svc' ...",
  "likelyCause": "Based on log evidence [...]: ... (Confidence: 65% — derived from AI analysis ...)",
  "impact": "Service 'payment-svc' experienced errors for ... 3 log event(s) recorded. Severity: HIGH.",
  "dataGaps": [
    "No metrics available ...",
    "No distributed traces available ...",
    "No deployment history available ..."
  ],
  "followUpActions": [
    "Review and document the confirmed root cause once the investigation is complete.",
    "Add a metric alert for elevated error rates in 'payment-svc' ...",
    "..."
  ],
  "timelineLength": 5
}
```

Inspect the timeline to see evidence-grounded entries:
```bash
echo "$POSTMORTEM" | jq '.data.timeline[] | {timestamp, description, evidenceRef}'
```

**What this proves:**
- `analysisEnriched: true` confirms the postmortem consumed the AI analysis from Step 5.
- The timeline is sorted chronologically; each log evidence item appears with its UUID,
  cross-referencing the evidence pack from Step 4.
- `likelyCause` cites the confidence score — it is transparently labelled as
  machine-assisted, not a confirmed human diagnosis.
- `followUpActions` are all safe, non-destructive improvement suggestions.
- The raw Bearer token and API key from Step 2 do not appear anywhere in this response.

---

## Step 8 — Fetch the latest runbook

```bash
curl -s "$BASE/incidents/$INCIDENT_ID/runbook/latest?tenantId=$TENANT_ID" \
  | jq '.data | {runbookVersion, incidentId, tenantId, stepCount: (.steps | length)}'
```

**Expected response:**
```json
{
  "runbookVersion": 1,
  "incidentId": "...",
  "tenantId": "..."
}
```

**What this proves:** The GET endpoint returns the stored runbook without re-running
generation. Re-generating and re-fetching are separate operations by design — the on-call
engineer reads the existing suggestion; a new version is only created on an explicit POST.

---

## Step 9 — Fetch the latest postmortem

```bash
curl -s "$BASE/incidents/$INCIDENT_ID/postmortem/latest?tenantId=$TENANT_ID" \
  | jq '.data | {draftVersion, incidentId, tenantId, analysisEnriched}'
```

**Expected response:**
```json
{
  "draftVersion": 1,
  "incidentId": "...",
  "tenantId": "...",
  "analysisEnriched": true
}
```

**What this proves:** Each draft is immutable once written. Re-fetching does not regenerate.
The `draftVersion` number makes the full generation history inspectable.

---

## Step 10 — Check monthly usage

After running Steps 5, 6, and 7, the usage counters for this tenant reflect one of each
operation type.

```bash
curl -s "$BASE/tenants/$TENANT_ID/usage" | jq '.data'
```

**Expected response:**
```json
{
  "tenantId": "...",
  "plan": "TEAM",
  "period": "YYYY-MM",
  "usage": {
    "AI_ANALYSIS":           {"used": 1, "limit": 300},
    "RUNBOOK_GENERATION":    {"used": 1, "limit": 300},
    "POSTMORTEM_GENERATION": {"used": 1, "limit": 150}
  }
}
```

**What this proves:** Every AI operation is metered per tenant per calendar month.
The TEAM plan has high limits (300/300/150), so this demo does not approach them.
A FREE plan tenant (limit 5/10/5) would see `"limit": 5` for `AI_ANALYSIS`. A GROWTH plan
tenant would see `"limit": -1`, meaning unlimited. Usage counters are isolated per tenant
— running this demo does not affect any other tenant's counts.

To see what a rejected request looks like, register a FREE tenant and call `/analyze` six
times. The sixth call returns HTTP 429 with an `ErrorResponse` body.

---

## What you just demonstrated

```
Raw log event (Bearer token + API key embedded)
    │
    ▼
SecretMaskingService ─────────────────────► [REDACTED] before storage
    │
    ▼
IncidentGroupingService ──────────────────► 1 incident, 3 evidence items
    │                                        severity escalates to HIGH
    ▼
EvidencePackService ──────────────────────► structured, secret-safe evidence pack
    │
    ▼
AIAnalysisService (mock-v1) ──────────────► evidence-backed analysis
    │                                        calibrated confidence (0.65)
    │                                        grounded unknowns
    │                                        safe next-check recommendations
    ▼
RunbookService (mock-v1) ─────────────────► TRIAGE / INVESTIGATE / VERIFY steps
    │                                        each INVESTIGATE step cites a real evidence ID
    ▼
PostmortemService (mock-v1, AI-enriched) ─► structured postmortem draft
    │                                        timeline + likelyCause + impact
    │                                        dataGaps + followUpActions
    ▼
UsageLimitService ────────────────────────► AI_ANALYSIS: 1/300
                                            RUNBOOK_GENERATION: 1/300
                                            POSTMORTEM_GENERATION: 1/150
```

Every AI output was validated by the internal safety harness before being returned.
No raw secrets appeared in any response. No destructive action was suggested at any step.
The AI provider is `mock-v1` — deterministic, no external API calls.
