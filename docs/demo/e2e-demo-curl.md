# AetherOps — End-to-End Demo (curl)

This script walks the full incident response lifecycle against a live AetherOps instance.
It is the human-readable counterpart to `AetherOpsEndToEndFlowTest`, which runs the
same flow automatically in CI.

**What this demo proves:**

| Step | Capability |
|------|-----------|
| 1 | Tenant registration |
| 2 | Structured log ingestion with automatic secret masking |
| 3 | Automatic incident grouping from repeated error patterns |
| 4 | Evidence pack assembly and secret-safe storage |
| 5 | Evidence-backed AI analysis with calibrated confidence |
| 6 | Safe, phased runbook suggestion (TRIAGE → INVESTIGATE → VERIFY) |
| 7 | Structured postmortem draft, AI-enriched when analysis exists |
| 8–9 | Idempotent GET endpoints for latest runbook and postmortem |

---

## Prerequisites

- AetherOps running on `localhost:8080`  
  (`mvn spring-boot:run` after setting `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`)
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
    "aiMonthlyBudgetCents": 5000,
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

**What this proves:** Tenant isolation is the foundation of the platform. Every subsequent
API call scopes data to this `tenantId`.

---

## Step 2 — Ingest log events with embedded secrets

Ingest three `ERROR` events from `payment-svc`. The second event embeds a raw Bearer token
and API key — both will be masked before storage.

```bash
# Event 1: clean error
curl -s -X POST "$BASE/events" \
  -H "Content-Type: application/json" \
  -d "{
    \"tenantId\": \"$TENANT_ID\",
    \"serviceId\": \"payment-svc\",
    \"level\": \"ERROR\",
    \"message\": \"Database connection pool exhausted on db-primary-01\",
    \"occurredAt\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"
  }" | jq '{id: .data.id, message: .data.message}'

# Event 2: same pattern — will be grouped into the same incident
curl -s -X POST "$BASE/events" \
  -H "Content-Type: application/json" \
  -d "{
    \"tenantId\": \"$TENANT_ID\",
    \"serviceId\": \"payment-svc\",
    \"level\": \"ERROR\",
    \"message\": \"Database connection pool exhausted on db-primary-01\",
    \"occurredAt\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"
  }" | jq '{id: .data.id, message: .data.message}'

# Event 3: same pattern + embedded secrets (Bearer token + API key)
# The raw values will be stripped by the masking pipeline before any storage.
curl -s -X POST "$BASE/events" \
  -H "Content-Type: application/json" \
  -d "{
    \"tenantId\": \"$TENANT_ID\",
    \"serviceId\": \"payment-svc\",
    \"level\": \"ERROR\",
    \"message\": \"Database connection pool exhausted — Bearer eyJhbGciOiJSUzI1NiJ9.secret.payload api_key=sk-prod-SECRET123\",
    \"occurredAt\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"
  }" | jq '{id: .data.id, message: .data.message}'
```

**Expected response for event 3 (the secrets are gone):**
```json
{
  "id": "...",
  "message": "Database connection pool exhausted — Bearer [REDACTED] api_key=[REDACTED]"
}
```

**What this proves:** Secrets are masked at ingestion time before the message is written to
storage. The raw token and API key never touch the database. The ingestion response already
returns the masked form.

---

## Step 3 — Verify incident grouping

Events 1, 2, and 3 all normalise to the same error pattern, so the grouping engine creates
one incident and updates it on each repeat.

```bash
INCIDENTS=$(curl -s "$BASE/incidents?tenantId=$TENANT_ID")
echo "$INCIDENTS" | jq '.data.content[] | {id, title, serviceId, severity, status, eventCount: .eventCount}'

INCIDENT_ID=$(echo "$INCIDENTS" | jq -r '.data.content[0].id')
echo "Incident ID: $INCIDENT_ID"
```

**Expected response:**
```json
[{
  "id": "...",
  "title": "[ERROR] payment-svc: Database connection pool exhausted ...",
  "serviceId": "payment-svc",
  "severity": "HIGH",
  "status": "OPEN"
}]
```

**What this proves:** Repeated errors from the same service and error class group into a
single incident rather than flooding the system with noise. Severity escalates as the
event count grows (`MEDIUM` → `HIGH` at 3 events).

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

Save the first evidence item ID for later cross-checking:
```bash
EVIDENCE_ID=$(echo "$PACK" | jq -r '.data.evidence[0].id')
echo "Evidence ID: $EVIDENCE_ID"
```

**What this proves:** The raw secrets are absent from every stored evidence summary. The
`[REDACTED]` marker confirms the masking pipeline fired. No downstream service — analysis,
runbook, or postmortem — ever sees the raw credentials.

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
    "No distributed traces available — ...",
    "No deployment history available — ..."
  ],
  "recommendedNextChecks": [
    "Review service logs for the affected time window",
    "Check recent deployment history for the affected service",
    "..."
  ]
}
```

**What this proves:**
- `evidenceRefs` are the actual evidence item UUIDs from step 4 — no hallucination.
- `confidenceScore` is capped at 0.65 for log-only evidence (honest calibration).
- `unknowns` explicitly acknowledges missing metrics, traces, and deployment history.
- `recommendedNextChecks` contains only safe, read-only investigative actions.

---

## Step 6 — Generate runbook suggestion

```bash
RUNBOOK=$(curl -s -X POST "$BASE/incidents/$INCIDENT_ID/runbook?tenantId=$TENANT_ID")
echo "$RUNBOOK" | jq '.data | {
  id,
  runbookVersion,
  generationBasis,
  generatorVersion,
  stepCount: (.steps | length),
  phases: [.steps[].phase] | unique
}'

# Print all steps
echo "$RUNBOOK" | jq '.data.steps[] | {phase, action}'
```

**Expected response:**
```json
{
  "runbookVersion": 1,
  "generationBasis": "log-only",
  "stepCount": 10,
  "phases": ["INVESTIGATE", "TRIAGE", "VERIFY"]
}
```

**What this proves:**
- Steps are organised into three phases: **TRIAGE** (assess and escalate), **INVESTIGATE**
  (evidence-grounded deep-dive), **VERIFY** (confirm root cause before acting).
- INVESTIGATE steps cite the real evidence item IDs from step 4 — they are grounded in
  actual data, not generic advice.
- No step suggests a destructive or autonomous action. Every step is read-only and
  observational — safe for a junior engineer to follow during an incident.

---

## Step 7 — Generate postmortem draft

Because `/analyze` ran in step 5, the postmortem will be **AI-enriched**: it pulls
`likelyCause` and `dataGaps` from the existing analysis rather than falling back to
evidence-only defaults.

```bash
POSTMORTEM=$(curl -s -X POST "$BASE/incidents/$INCIDENT_ID/postmortem?tenantId=$TENANT_ID")
echo "$POSTMORTEM" | jq '.data | {
  id,
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
  "incidentSummary": "An incident was detected in service 'payment-svc' ...",
  "likelyCause": "Based on log evidence [...]: ... (Confidence: 65% — derived from AI analysis ...)",
  "impact": "Service 'payment-svc' experienced errors for ... 3 log event(s) recorded. Severity: HIGH.",
  "dataGaps": [
    "No metrics available ...",
    "No distributed traces available ...",
    "No deployment history available ..."
  ],
  "followUpActions": [
    "Review and document the confirmed root cause ...",
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
- `analysisEnriched: true` — the postmortem consumed the AI analysis from step 5.
- The timeline is chronological, with each log evidence item appearing as a named entry
  and its UUID cross-referencing the evidence pack from step 4.
- `likelyCause` cites the AI confidence score, making it transparent that this is a
  machine-assisted draft, not a confirmed human diagnosis.
- `followUpActions` are all safe, non-destructive improvement suggestions — no
  "restart service", no "delete data", no autonomous remediation.
- The raw Bearer token and API key from step 2 do not appear anywhere in this response.

---

## Step 8 — Fetch the latest runbook

```bash
curl -s "$BASE/incidents/$INCIDENT_ID/runbook/latest?tenantId=$TENANT_ID" \
  | jq '.data | {id, runbookVersion, incidentId, tenantId, stepCount: (.steps | length)}'
```

**Expected response:**
```json
{
  "runbookVersion": 1,
  "incidentId": "...",
  "tenantId": "..."
}
```

**What this proves:** The GET endpoint returns the most recently generated runbook without
re-running generation. This is the read path used by an on-call engineer who wants to check
what steps have already been suggested.

---

## Step 9 — Fetch the latest postmortem

```bash
curl -s "$BASE/incidents/$INCIDENT_ID/postmortem/latest?tenantId=$TENANT_ID" \
  | jq '.data | {id, draftVersion, incidentId, tenantId, analysisEnriched}'
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

**What this proves:** The GET endpoint is idempotent. Re-fetching does not regenerate the
draft — it returns the stored version. This is important for audit trails: each draft is
immutable once written, and the version number makes the generation history clear.

---

## Summary — what you just demonstrated

```
Raw log event with embedded secrets
    │
    ▼
SecretMaskingService ──────────────────────────────► [REDACTED] before any storage
    │
    ▼
IncidentGroupingService ──────────────────────────► 1 incident, 3 evidence items
    │
    ▼
EvidencePackService ───────────────────────────────► structured evidence pack
    │
    ▼
AIAnalysisService (mock-v1) ───────────────────────► evidence-backed analysis
    │                                                  calibrated confidence
    │                                                  grounded unknowns
    ▼
RunbookService ────────────────────────────────────► phased safe runbook steps
    │                                                  TRIAGE / INVESTIGATE / VERIFY
    ▼
PostmortemService (AI-enriched) ───────────────────► structured postmortem draft
                                                       timeline + likelyCause + impact
                                                       dataGaps + followUpActions
```

Every output was evaluated by an internal safety harness before being returned.
No raw secrets appeared in any response. No destructive action was suggested at any step.
