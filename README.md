<!-- Badges -->
<p align="center">
  <img src="https://img.shields.io/badge/Java-17-ED8B00?style=flat-square&logo=openjdk&logoColor=white" alt="Java 17"/>
  <img src="https://img.shields.io/badge/Spring_Boot-3.4.1-6DB33F?style=flat-square&logo=springboot&logoColor=white" alt="Spring Boot 3.4.1"/>
  <img src="https://img.shields.io/badge/PostgreSQL-17-4169E1?style=flat-square&logo=postgresql&logoColor=white" alt="PostgreSQL"/>
  <img src="https://img.shields.io/badge/Redis-7-DC382D?style=flat-square&logo=redis&logoColor=white" alt="Redis"/>
  <img src="https://img.shields.io/badge/Apache_Kafka-3-231F20?style=flat-square&logo=apachekafka&logoColor=white" alt="Apache Kafka"/>
  <img src="https://img.shields.io/badge/Docker-ready-2496ED?style=flat-square&logo=docker&logoColor=white" alt="Docker"/>
  <img src="https://img.shields.io/badge/Kubernetes-manifests-326CE5?style=flat-square&logo=kubernetes&logoColor=white" alt="Kubernetes"/>
  <img src="https://img.shields.io/badge/License-MIT-yellow?style=flat-square" alt="License MIT"/>
  <img src="https://img.shields.io/badge/Tests-339_passing-brightgreen?style=flat-square&logo=checkmarx&logoColor=white" alt="339 tests passing"/>
</p>

<h1 align="center">AetherOps</h1>

<p align="center">
  AetherOps turns backend failures into evidence-backed AI investigations — with confidence scores, explicit unknowns, and safe runbooks. Built for Spring Boot teams.
</p>

<!-- TODO: add a screenshot of the live landing page here, e.g.
![AetherOps landing page](docs/screenshot.png) -->

---

## Live Demo

**[https://aetheropsai.com/landing.html](https://aetheropsai.com/landing.html)**

> Demo tenant available — click **Try Demo** on the landing page. No account required.

Fallback: [https://aetherops-production.up.railway.app/landing.html](https://aetherops-production.up.railway.app/landing.html)

---

## Architecture

```mermaid
flowchart TD
    Client["Client\n(Browser / API consumer)"]

    subgraph app["Spring Boot 3.4.1"]
        GW["API Gateway Filter\n(rate limit · auth · tenant resolution)"]
        Core["Application Core\n(REST controllers · services · domain)"]
    end

    PG[("PostgreSQL\n(HikariCP pool)")]
    Redis[("Redis\n(rate limiting · caching\n· distributed locks)")]

    subgraph kafka["Apache Kafka"]
        Topic["incident.created\n(+ other domain events)"]
        DLQ["Dead Letter Topic\n(failed events)"]
        Outbox["Transactional Outbox\nRelay"]
    end

    AI["Anthropic AI API\n(Claude)"]
    Zipkin["Zipkin\n(distributed tracing)"]
    Prometheus["Prometheus\n(metrics scrape)"]

    Client -->|HTTPS| GW
    GW --> Core
    Core -->|JDBC| PG
    Core -->|Lettuce| Redis
    Core -->|KafkaTemplate| Topic
    Topic -->|on failure| DLQ
    Outbox -->|polls + publishes| Topic
    Core -->|HTTP + circuit breaker\nretry · bulkhead| AI
    Core -->|spans| Zipkin
    Prometheus -->|scrapes /actuator/prometheus| Core

    style app fill:#1a1a2e,stroke:#6DB33F,color:#fff
    style kafka fill:#1a1a2e,stroke:#231F20,color:#fff
```

---

## Tech Stack

| Technology | Purpose |
|---|---|
| Java 17 + Spring Boot 3.4.1 | Core framework, dependency injection, auto-configuration |
| PostgreSQL + Flyway | Relational persistence with versioned schema migrations |
| Redis (Lettuce / Redisson) | Distributed rate limiting, response caching, distributed locks |
| Apache Kafka | Asynchronous event streaming, Dead Letter Queue, Transactional Outbox |
| Resilience4j | Circuit breaker, retry with exponential backoff, bulkhead isolation |
| Micrometer + Prometheus | Metrics collection, `/actuator/prometheus` scrape endpoint |
| Micrometer Tracing + Zipkin | End-to-end distributed tracing across service boundaries |
| Spring Security + JWT | Stateless authentication, role-based authorization |
| Testcontainers | Integration tests against real PostgreSQL and Redis instances |
| Docker + Kubernetes | Multi-stage image build, namespace-scoped K8s manifests, HPA |
| GitHub Actions | CI pipeline (test + build + push to GHCR) with PR quality checks |

---

## Key Features

### Security
- Stateless JWT authentication with sliding refresh tokens
- Two-factor authentication via TOTP (Google Authenticator compatible)
- Google OAuth 2.0 social login
- Per-IP rate limiting enforced at the servlet filter layer
- BCrypt password hashing with configurable strength

### Resilience
- Resilience4j circuit breaker around the Anthropic AI client — degrades gracefully to a static fallback response when the upstream is unavailable
- Retry with exponential backoff and jitter on transient AI API failures
- Bulkhead to cap concurrent AI requests and prevent thread-pool saturation

### Event-Driven
- Kafka event publishing on incident lifecycle transitions (`incident.created`, etc.)
- Dead Letter Queue for events that exhaust retries — no silent drops
- Transactional Outbox pattern ensures at-least-once delivery without dual-write inconsistency

### Architecture Patterns
- Event Sourcing and CQRS for incident state reconstruction and read-model separation
- Distributed Saga with compensating transactions for multi-step workflows
- Domain-Driven Design — bounded contexts, aggregates, domain events
- API Gateway filter for cross-cutting concerns (auth, tenant resolution, rate limiting)
- Strangler Fig approach for incremental replacement of legacy incident handling

### Observability
- Micrometer metrics on every key operation, scraped by Prometheus
- Zipkin distributed tracing with per-request trace and span propagation
- Spring Boot Actuator health groups: liveness, readiness, and per-dependency checks (DB, Redis, Kafka)

### Data
- Multi-tenancy enforced at the repository layer — every query is scoped by `tenantId`
- Soft delete with `deletedAt` timestamps — no hard data loss on entity removal
- Idempotency keys on mutation endpoints — safe to retry without duplicate side-effects
- Redisson distributed locks for cluster-safe critical sections

---

## Getting Started

### Prerequisites

- Java 17 ([Temurin](https://adoptium.net/))
- Docker (for Testcontainers and local infrastructure)
- Maven 3.9+

### Run Locally

```bash
# 1. Clone the repository
git clone https://github.com/akhilleusn/aetherops.git
cd aetherops

# 2. Configure environment variables
cp .env.example .env
# Open .env and fill in DB credentials, JWT secret, and optional API keys

# 3. Start infrastructure (PostgreSQL, Redis, Kafka, Zipkin)
docker-compose up -d

# 4. Run the application
mvn spring-boot:run
```

The API will be available at `http://localhost:8080`.  
Zipkin UI: `http://localhost:9411`

---

## Running Tests

```bash
mvn clean test
```

> **339 integration tests** run against real PostgreSQL and Redis instances via Testcontainers. Docker must be running — Testcontainers pulls and starts containers automatically.

Test coverage includes: authentication flows, JWT lifecycle, TOTP enrollment, Google OAuth, multi-tenant isolation, incident grouping, AI analysis (circuit breaker + fallback), audit logging, rate limiting, Kafka event publishing, Transactional Outbox, Evidence Packs, Postmortems, Runbooks, and more.

---

## Kubernetes Deployment

The `kubernetes/` directory contains production-ready manifests. Apply them in order:

```bash
# 1. Create the namespace
kubectl apply -f kubernetes/namespace.yaml

# 2. Fill in real base64-encoded values in the secret template
#    Encode each value with: echo -n "your-value" | base64
vi kubernetes/secret.yaml

# 3. Apply all manifests (namespace, configmap, secret, deployment, service, ingress, hpa, pdb)
kubectl apply -f kubernetes/

# 4. Verify pods are healthy
kubectl get pods -n aetherops
kubectl get hpa   -n aetherops
```

The HPA scales between 2 and 10 replicas based on CPU (70%) and memory (80%) utilisation. The PodDisruptionBudget guarantees at least one pod remains available during node maintenance or rolling updates.

---

## API Documentation

| Endpoint | Description |
|---|---|
| `http://localhost:8080/swagger-ui.html` | Interactive Swagger UI — browse and execute every API endpoint |
| `http://localhost:8080/actuator/health` | Liveness, readiness, and per-dependency health checks (DB, Redis, Kafka) |
| `http://localhost:8080/actuator/prometheus` | Prometheus metrics scrape endpoint |

Swagger UI is available in all profiles. The OpenAPI spec is generated at startup from `@Operation` and `@Schema` annotations on the controller layer.

---

## Project Structure

```
src/main/java/com/aetherops/
│
├── analysis/          # AI-powered root cause analysis
│   ├── controller/    #   POST /api/incidents/{id}/analyze
│   ├── service/       #   AIAnalysisService — Resilience4j circuit breaker + retry + bulkhead
│   └── domain/        #   AIAnalysisResponse, AnalysisResult
│
├── audit/             # Immutable audit trail for every tenant action
│   ├── controller/    #   GET /api/audit-logs (filterable by action, status, date range)
│   ├── service/       #   AuditLogService — JPA Specification / Criteria API queries
│   └── repository/    #   AuditLogRepository extends JpaSpecificationExecutor
│
├── auth/              # Authentication and identity
│   ├── controller/    #   /api/auth/** — register, login, refresh, logout, password reset
│   ├── service/       #   JwtService, TotpService, GoogleOAuthService, PasswordResetService
│   └── filter/        #   JwtAuthenticationFilter, IpRateLimitFilter
│
├── incident/          # Core incident lifecycle
│   ├── controller/    #   CRUD + grouping + status transitions
│   ├── service/       #   IncidentService, IncidentGroupingService
│   ├── event/         #   IncidentCreatedEvent, IncidentEventPublisher (Kafka)
│   └── repository/    #   Multi-tenant scoped queries
│
├── outbox/            # Transactional Outbox pattern
│   ├── entity/        #   OutboxEvent — persisted in same DB transaction as domain change
│   ├── relay/         #   OutboxRelayScheduler — polls and publishes to Kafka
│   └── repository/    #   OutboxEventRepository
│
├── saga/              # Distributed Saga orchestration
│   ├── orchestrator/  #   SagaOrchestrator — step sequencing with compensations
│   ├── step/          #   SagaStep implementations per bounded context
│   └── log/           #   SagaLog — durable saga state for crash recovery
│
├── tenant/            # Multi-tenancy
│   ├── controller/    #   Tenant provisioning and management APIs
│   ├── service/       #   TenantService — isolation enforcement
│   └── context/       #   TenantContextHolder (ThreadLocal tenant resolution)
│
├── usage/             # Usage limits and quota enforcement
│   ├── service/       #   UsageLimitService — per-tenant quota checks
│   └── repository/    #   UsageRecordRepository
│
└── webhook/           # Outbound webhook delivery
    ├── service/       #   WebhookService — delivery with retry
    └── repository/    #   WebhookEndpointRepository
```

---

## License

MIT License — see [LICENSE](LICENSE) for full text.

Copyright (c) 2025 AetherOps
