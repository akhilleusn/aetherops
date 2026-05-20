#!/usr/bin/env bash
set -euo pipefail

# ---------- configuration ---------------------------------------------------
AETHEROPS="${AETHEROPS_URL:-http://localhost:8080}"
SAMPLE_APP="${SAMPLE_APP_URL:-http://localhost:9090}"

# ---------- colours ---------------------------------------------------------
GRN='\033[0;32m'; YLW='\033[1;33m'; CYN='\033[0;36m'
RED='\033[0;31m'; BLD='\033[1m'; NC='\033[0m'

step() { echo -e "\n${BLD}${CYN}[$1]${NC} $2"; }
ok()   { echo -e "    ${GRN}✓${NC} $1"; }
info() { echo -e "    ${YLW}→${NC} $1"; }
fail() { echo -e "\n${RED}✗ $1${NC}"; exit 1; }

# ---------- dependency check ------------------------------------------------
for cmd in curl jq; do
  command -v "$cmd" >/dev/null 2>&1 || \
    fail "'$cmd' is required. Install it and retry (brew/apt/choco install $cmd)."
done

# ---------- wait for a service health endpoint ------------------------------
wait_for() {
  local name="$1" url="$2" tries=20
  printf "    Waiting for %s" "$name"
  for _ in $(seq 1 $tries); do
    if curl -sf "$url" >/dev/null 2>&1; then
      echo -e " ${GRN}✓${NC}"
      return 0
    fi
    printf "."
    sleep 3
  done
  echo ""
  fail "$name did not become healthy after $((tries * 3))s. Is 'docker compose up -d' running?"
}

# ---------- helpers ---------------------------------------------------------
post_json() {
  local url="$1" body="$2"; shift 2
  curl -sf -X POST "$url" -H "Content-Type: application/json" -d "$body" "$@"
}

post_cmd() {
  curl -sf -X POST "$1"
}

# ============================================================================
echo ""
echo -e "${BLD}╔══════════════════════════════════════════════╗${NC}"
echo -e "${BLD}║        AetherOps  —  Failure Demo            ║${NC}"
echo -e "${BLD}╚══════════════════════════════════════════════╝${NC}"

# ---------- [1] Wait for services -------------------------------------------
step "1/8" "Waiting for services to be ready..."
wait_for "AetherOps backend" "$AETHEROPS/actuator/health"
wait_for "sample-app"        "$SAMPLE_APP/actuator/health"

# ---------- [2] Create tenant -----------------------------------------------
step "2/8" "Creating tenant (TEAM plan)..."
TENANT=$(post_json "$AETHEROPS/api/v1/tenants" \
  '{"name":"Demo Corp","plan":"TEAM","aiMonthlyBudgetCents":0,"retentionDays":30}')
TENANT_ID=$(echo "$TENANT" | jq -r '.data.id')
ok "tenant_id = $TENANT_ID"

# ---------- [3] Create project ----------------------------------------------
step "3/8" "Creating project..."
PROJECT=$(post_json "$AETHEROPS/api/v1/projects" \
  "$(jq -cn --arg t "$TENANT_ID" '{tenantId: $t, name: "Payment Service"}')")
PROJECT_ID=$(echo "$PROJECT" | jq -r '.data.id')
ok "project_id = $PROJECT_ID"

# ---------- [4] Create API key ----------------------------------------------
step "4/8" "Creating API key..."
KEY_RESP=$(post_json \
  "$AETHEROPS/api/v1/projects/$PROJECT_ID/keys?tenantId=$TENANT_ID" '{}')
API_KEY=$(echo "$KEY_RESP"   | jq -r '.data.plaintext')
KEY_PFX=$(echo "$KEY_RESP"   | jq -r '.data.keyPrefix')
ok "key prefix = $KEY_PFX  (full key stored in \$API_KEY)"

# ---------- [5] Trigger failures in sample-app ------------------------------
step "5/8" "Triggering failures in sample-app..."
for endpoint in db-timeout error db-timeout; do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$SAMPLE_APP/simulate/$endpoint")
  info "GET /simulate/$endpoint  →  HTTP $STATUS"
done

# ---------- [6] Send 3 ERROR events to AetherOps ---------------------------
step "6/8" "Sending 3 ERROR events to AetherOps via API key..."
MSG="Database connection pool exhausted after 30000ms: HikariPool-1 connection acquisition timeout"

for i in 1 2 3; do
  TS=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
  BODY=$(jq -cn \
    --arg svc "payment-svc" \
    --arg msg "$MSG" \
    --arg ts  "$TS" \
    '{serviceId: $svc, level: "ERROR", message: $msg, occurredAt: $ts}')
  EV=$(post_json "$AETHEROPS/api/v1/events" "$BODY" -H "X-Api-Key: $API_KEY")
  info "event $i  →  id = $(echo "$EV" | jq -r '.data.id')"
done

# ---------- [7] Fetch the grouped incident ----------------------------------
step "7/8" "Fetching incident..."
sleep 1
INCIDENTS=$(curl -sf "$AETHEROPS/api/v1/incidents?tenantId=$TENANT_ID")
INCIDENT_ID=$(echo "$INCIDENTS" | jq -r '.data.content[0].id')
SEVERITY=$(echo "$INCIDENTS"    | jq -r '.data.content[0].severity')
COUNT=$(echo "$INCIDENTS"       | jq -r '.data.content[0].eventCount')
TITLE=$(echo "$INCIDENTS"       | jq -r '.data.content[0].title')
ok "incident_id = $INCIDENT_ID"
info "title      = $TITLE"
info "severity   = $SEVERITY  (escalated after $COUNT events)"

# ---------- [8] Run the AI pipeline ----------------------------------------
step "8/8" "Running AI pipeline..."

info "POST /analyze ..."
ANALYSIS=$(post_cmd "$AETHEROPS/api/v1/incidents/$INCIDENT_ID/analyze?tenantId=$TENANT_ID")
CONFIDENCE=$(echo "$ANALYSIS" | jq -r '.data.confidenceScore')
info "confidence score = $CONFIDENCE"

info "POST /runbook ..."
RUNBOOK=$(post_cmd "$AETHEROPS/api/v1/incidents/$INCIDENT_ID/runbook?tenantId=$TENANT_ID")
STEPS=$(echo "$RUNBOOK" | jq '.data.steps | length')
info "runbook steps = $STEPS"

info "POST /postmortem ..."
POSTMORTEM=$(post_cmd "$AETHEROPS/api/v1/incidents/$INCIDENT_ID/postmortem?tenantId=$TENANT_ID")
ENRICHED=$(echo "$POSTMORTEM" | jq -r '.data.analysisEnriched')
info "postmortem analysisEnriched = $ENRICHED"

# ---------- done ------------------------------------------------------------
echo ""
echo -e "${BLD}${GRN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${BLD}${GRN} Demo complete.${NC}"
echo -e "${GRN} Incident:   $AETHEROPS/api/v1/incidents/$INCIDENT_ID${NC}"
echo -e "${GRN} Swagger UI: $AETHEROPS/swagger-ui.html${NC}"
echo -e "${BLD}${GRN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""
