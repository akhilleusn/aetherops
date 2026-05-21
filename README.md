# AetherOps

AetherOps is a backend reliability platform that helps engineering teams investigate production incidents. It ingests structured log events, masks secrets before storage, groups repeated errors into incidents, and generates evidence-backed AI analysis, runbook suggestions, and postmortem drafts — all scoped per tenant with monthly usage metering.

The AI layer currently uses a deterministic mock provider (`mock-v1`). The interfaces are designed as swap points: connecting a real LLM requires adding one `@Primary` bean. No external API calls are made today.

---

## Quick Start (Docker)

No Java or PostgreSQL installation required.

### Prerequisites

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) (or Docker Engine + Compose plugin)
- `curl` and `jq` — for the demo script

### 1. Start the stack

```bash
docker compose up -d
```

> **First run** downloads base images and builds both JARs inside Docker (~3–5 minutes). Subsequent starts take ~10 seconds.

### 2. Run the failure demo

```bash
./demo/run-demo.sh
```

The script creates a tenant, registers a project and API key, simulates payment service failures, and drives the full incident → evidence → AI analysis → runbook → postmortem pipeline. Open [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html) to explore the API interactively.

### 3. Stop

```bash
docker compose down       # stop containers, keep data
docker compose down -v    # stop containers and delete all data
```

---

## What it does today

- **Tenant management** — register tenants, assign plans (FREE / STARTER / TEAM / GROWTH)
- **Log ingestion with secret masking** — Bearer tokens, API keys, passwords, and JWTs are masked to `[REDACTED]` before any storage
- **Automatic incident grouping** — repeated errors from the same service fingerprint into a single incident; severity escalates as events accumulate
- **Evidence packs** — structured assembly of log events linked to an incident, used as the input to all AI features
- **AI analysis** — evidence-backed analysis with calibrated confidence score, grounded unknowns, and safe next-check recommendations
- **Runbook suggestions** — phased steps (TRIAGE → INVESTIGATE → VERIFY) grounded in real evidence item IDs
- **Postmortem drafts** — structured drafts with incident summary, chronological timeline, likely cause, impact, data gaps, and follow-up actions; AI-enriched when an analysis exists
- **Usage metering** — per-tenant monthly counters for each AI operation; HTTP 429 when a plan limit is reached

---

## What it does not do yet

- **Real AI via Claude (optional)** — set `ANTHROPIC_API_KEY=sk-ant-...` in your `.env` or environment to enable `claude-sonnet-4-20250514`; the deterministic mock provider is used automatically when the key is absent
- **No authentication** — all endpoints are open; API key security is a planned addition
- **No billing** — `aiMonthlyBudgetCents` exists on the tenant model but is not wired to any payment processor
- **No dashboard or frontend UI** — API only
- **No OpenTelemetry or distributed tracing** — trace and span IDs are stored on log events but not used for analysis
- **No autonomous remediation** — the system only suggests; it never executes actions
- **No email, Slack, or webhook notifications** — alerting is a planned addition

---

## Modules

| Module | Package | Responsibility |
|--------|---------|----------------|
| `ingestion` | `com.aetherops.ingestion` | Receives log events, applies secret masking, triggers incident grouping |
| `masking` | `com.aetherops.masking` | Regex-based secret masking (Bearer tokens, API keys, JWTs, JDBC URLs) |
| `incident` | `com.aetherops.incident` | Incident CRUD, fingerprint-based event grouping, severity escalation |
| `evidence` | `com.aetherops.evidence` | Assembles evidence packs from log events linked to an incident |
| `analysis` | `com.aetherops.analysis` | AI analysis with safety evaluation harness; append-only per incident |
| `runbook` | `com.aetherops.runbook` | Phased runbook suggestions grounded in evidence item IDs |
| `postmortem` | `com.aetherops.postmortem` | Structured postmortem drafts; enriched from AI analysis when available |
| `usage` | `com.aetherops.usage` | Monthly operation counters with per-plan limits; HTTP 429 on exhaustion |
| `tenant` | `com.aetherops.tenant` | Tenant registration and plan management |
| `common` | `com.aetherops.common` | `ApiResponse<T>`, `ErrorResponse`, `GlobalExceptionHandler`, `FingerprintUtil` |

---

## Design notes

- **Modular monolith** — all modules compile into one JAR; no inter-service network calls. Module boundaries are enforced by package structure. Individual modules can be extracted into services later without rewriting the domain logic.
- **Append-only AI outputs** — each call to `/analyze`, `/runbook`, and `/postmortem` inserts a new row. Previous versions are preserved for audit and for comparing outputs as evidence grows between calls.
- **Mock provider pattern** — `AIProvider`, `RunbookGenerator`, and `PostmortemDraftGenerator` are interfaces. The deterministic `mock-v1` implementations require no external API. Swap in a real provider by adding a `@Primary` bean that implements the same interface.
- **Safety evaluation harness** — every AI output is validated before being returned: secret leakage, hallucinated evidence references, unsafe recommendations, and confidence miscalibration are all checked. Issues are logged with a risk level; the response is still returned (not suppressed).
- **Tenant isolation by UUID column** — every table carries a `tenant_id` UUID column. There are no foreign key constraints to the `tenants` table. This means data can exist for a tenant UUID before the tenant is formally registered, which keeps integration tests simple without mocking.

---

## Tech stack

| Component | Choice |
|-----------|--------|
| Language | Java 17 |
| Framework | Spring Boot 3.4.1 |
| Database | PostgreSQL 14+ |
| Migrations | Flyway |
| ORM | Spring Data JPA (Hibernate 6) |
| Validation | Jakarta Bean Validation |
| API docs | Springdoc OpenAPI 2.7.0 (Swagger UI) |
| Boilerplate reduction | Lombok |
| Test database | H2 (in-memory, PostgreSQL mode) |
| Build | Maven 3.8+ |

---

## Running locally

### Prerequisites

| Tool | Version |
|------|---------|
| Java | 17+ |
| Maven | 3.8+ |
| PostgreSQL | 14+ |

### 1. Create the database

```sql
CREATE DATABASE aetherops;
CREATE USER aetherops_user WITH PASSWORD 'change_me';
GRANT ALL PRIVILEGES ON DATABASE aetherops TO aetherops_user;
```

### 2. Set environment variables

```bash
# Copy the example and fill in your values
cp .env.example .env

export DB_URL=jdbc:postgresql://localhost:5432/aetherops
export DB_USERNAME=aetherops_user
export DB_PASSWORD=change_me
```

On Windows (PowerShell):
```powershell
$env:DB_URL      = "jdbc:postgresql://localhost:5432/aetherops"
$env:DB_USERNAME = "aetherops_user"
$env:DB_PASSWORD = "change_me"
```

### 3. Start the application

```bash
mvn spring-boot:run
```

Flyway applies all migrations in `src/main/resources/db/migration/` on startup. The server starts on port 8080 by default.

---

## Running tests

```bash
mvn test
```

Tests use an H2 in-memory database with `@ActiveProfiles("test")`. No PostgreSQL instance is needed. Flyway is disabled in the test profile; Hibernate creates the schema from entity annotations using `ddl-auto: create-drop`.

Current status: **134 tests, 0 failures.**

---

## API

### Documentation

| URL | Description |
|-----|-------------|
| http://localhost:8080/swagger-ui.html | Swagger UI — interactive endpoint explorer |
| http://localhost:8080/api-docs | OpenAPI JSON spec |
| http://localhost:8080/actuator/health | Health check |
| http://localhost:8080/actuator/metrics | Metrics |

### Endpoints

**Tenants**

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/tenants` | Register a new tenant |
| `GET` | `/api/v1/tenants` | List all tenants (paginated) |
| `GET` | `/api/v1/tenants/{id}` | Get tenant by ID |
| `GET` | `/api/v1/tenants/{id}/usage` | Monthly usage summary for a tenant (`?period=YYYY-MM`, defaults to current month) |

**Log Events**

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/events` | Ingest a log event — secrets masked before storage, incident grouping triggered |
| `GET` | `/api/v1/events` | List log events for a tenant (`?tenantId`, optional `?serviceId`, `?level`) |

**Incidents**

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/incidents` | Manually create an incident |
| `GET` | `/api/v1/incidents` | List incidents for a tenant (`?tenantId`, optional filters) |
| `GET` | `/api/v1/incidents/{id}` | Get incident by ID |
| `PATCH` | `/api/v1/incidents/{id}` | Update status, severity, title, or description |
| `GET` | `/api/v1/incidents/{id}/evidence-pack` | Assembled evidence pack for an incident (`?tenantId`) |

**AI Analysis** *(mock-v1 provider — no external API calls)*

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/incidents/{id}/analyze` | Run AI analysis on the evidence pack — creates a new version each call (`?tenantId`) |

**Runbook Suggestions** *(mock-v1 provider)*

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/incidents/{id}/runbook` | Generate a runbook suggestion — creates a new version each call (`?tenantId`) |
| `GET` | `/api/v1/incidents/{id}/runbook/latest` | Get the most recently generated runbook (`?tenantId`) |

**Postmortem Drafts** *(mock-v1 provider)*

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/incidents/{id}/postmortem` | Generate a postmortem draft — AI-enriched if an analysis exists (`?tenantId`) |
| `GET` | `/api/v1/incidents/{id}/postmortem/latest` | Get the most recently generated postmortem draft (`?tenantId`) |

---

## Response shapes

**Success — `ApiResponse<T>`**
```json
{ "success": true, "data": { ... }, "timestamp": "2026-05-19T..." }
```

**Not found (404)**
```json
{ "timestamp": "...", "status": 404, "error": "Not Found", "message": "Incident not found: ...", "path": "/api/v1/incidents/..." }
```

**Validation error (422)**
```json
{
  "timestamp": "...", "status": 422, "error": "Unprocessable Entity",
  "message": "Validation failed", "path": "...",
  "details": { "fieldName": "error message" }
}
```

**Usage limit exceeded (429)**
```json
{
  "timestamp": "...", "status": 429, "error": "Too Many Requests",
  "message": "Monthly AI_ANALYSIS limit of 5 reached for tenant ... (plan: FREE). Upgrade your plan to continue.",
  "path": "/api/v1/incidents/.../analyze"
}
```

---

## Plan limits

| Plan | AI Analysis | Runbook | Postmortem |
|------|------------|---------|------------|
| FREE | 5 / month | 10 / month | 5 / month |
| STARTER | 50 / month | 50 / month | 25 / month |
| TEAM | 300 / month | 300 / month | 150 / month |
| GROWTH | unlimited | unlimited | unlimited |

Limits are enforced in `UsageLimitService`. Usage counters are per-tenant, per-calendar-month. A failed generation does not count against the limit (the increment is rolled back with the transaction). The `GET /api/v1/tenants/{id}/usage` endpoint returns current counts alongside the plan limits.
