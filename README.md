# AetherOps Backend

AI-assisted backend reliability platform — log ingestion, incident management, and secret masking.

## Running Locally

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
$env:DB_URL     = "jdbc:postgresql://localhost:5432/aetherops"
$env:DB_USERNAME = "aetherops_user"
$env:DB_PASSWORD = "change_me"
```

### 3. Start the application

```bash
mvn spring-boot:run
```

Flyway will automatically apply all migrations in `src/main/resources/db/migration/` on startup.

### Running Tests

```bash
mvn test
```

Tests use an H2 in-memory database with `@ActiveProfiles("test")` — no PostgreSQL needed.

### API Documentation

| URL | Description |
|-----|-------------|
| http://localhost:8080/swagger-ui.html | Swagger UI |
| http://localhost:8080/api-docs | OpenAPI JSON |
| http://localhost:8080/actuator/health | Health check |
| http://localhost:8080/actuator/metrics | Metrics |

## API Overview

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/tenants` | Register a new tenant |
| `GET` | `/api/v1/tenants` | List tenants (paginated) |
| `GET` | `/api/v1/tenants/{id}` | Get tenant by ID |
| `POST` | `/api/v1/events` | Ingest a log event |
| `GET` | `/api/v1/events` | List log events |
| `POST` | `/api/v1/incidents` | Create an incident |
| `GET` | `/api/v1/incidents` | List incidents |
| `GET` | `/api/v1/incidents/{id}` | Get incident by ID |
| `PATCH` | `/api/v1/incidents/{id}` | Update incident |

## Response Shapes

**Success**
```json
{ "success": true, "data": { ... }, "timestamp": "..." }
```

**Error**
```json
{ "timestamp": "...", "status": 404, "error": "Not Found", "message": "...", "path": "/api/v1/..." }
```

**Validation Error (422)**
```json
{ "timestamp": "...", "status": 422, "error": "Unprocessable Entity", "message": "Validation failed",
  "path": "...", "details": { "fieldName": "error message" } }
```
