# Contributing to AetherOps

Thank you for taking the time to contribute. This document covers everything you need to get a working development environment, the project's branching and commit conventions, and the pull request process.

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Local Development Setup](#local-development-setup)
3. [Environment Variables](#environment-variables)
4. [Running the Application](#running-the-application)
5. [Running Tests](#running-tests)
6. [Code Style](#code-style)
7. [Branching Strategy](#branching-strategy)
8. [Commit Message Format](#commit-message-format)
9. [Pull Request Guidelines](#pull-request-guidelines)
10. [What Not to Contribute](#what-not-to-contribute)

---

## Prerequisites

| Tool | Minimum version | Notes |
|---|---|---|
| JDK | 17 (Temurin recommended) | `java -version` must print `17.x` |
| Maven | 3.9+ | Included via `./mvnw` wrapper — no separate install needed |
| Docker | 24+ | Required for Testcontainers and `docker-compose up` |
| Git | 2.40+ | |

Docker must be running before you execute any tests or `docker-compose` commands.

---

## Local Development Setup

```bash
# 1. Fork the repository on GitHub, then clone your fork
git clone https://github.com/<your-username>/aetherops.git
cd aetherops

# 2. Add the upstream remote so you can pull future changes
git remote add upstream https://github.com/akhilleusn/aetherops.git

# 3. Copy the environment template and fill in your values
cp .env.example .env
```

Open `.env` in your editor and provide at minimum:

```
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/aetherops
SPRING_DATASOURCE_USERNAME=aetherops
SPRING_DATASOURCE_PASSWORD=<choose-a-local-password>
APP_JWT_SECRET=<any-32+-character-string-for-local-dev>
APP_TOTP_ENCRYPTIONKEY=<exactly-32-character-string>
```

The `ANTHROPIC_API_KEY` is optional — omit it and the application will use the mock AI provider automatically.

### Start the infrastructure stack

```bash
docker-compose up -d
```

This starts PostgreSQL, Redis, Kafka (+ Zookeeper), and Zipkin. Flyway migrations run automatically on first application startup.

To stop and remove the containers (data is preserved in Docker volumes):

```bash
docker-compose down
```

To wipe all data and start fresh:

```bash
docker-compose down -v
```

---

## Environment Variables

| Variable | Required | Description |
|---|---|---|
| `SPRING_DATASOURCE_URL` | Yes | JDBC URL for PostgreSQL |
| `SPRING_DATASOURCE_USERNAME` | Yes | PostgreSQL user |
| `SPRING_DATASOURCE_PASSWORD` | Yes | PostgreSQL password |
| `APP_JWT_SECRET` | Yes | HMAC signing key (≥ 32 chars) |
| `APP_TOTP_ENCRYPTIONKEY` | Yes | AES key for TOTP seeds (exactly 32 chars) |
| `ANTHROPIC_API_KEY` | No | Claude API key — omit to use the mock AI provider |
| `SPRING_KAFKA_BOOTSTRAPSERVERS` | No | Defaults to `localhost:9092` |
| `SPRING_DATA_REDIS_HOST` | No | Defaults to `localhost` |
| `SPRING_DATA_REDIS_PORT` | No | Defaults to `6379` |

---

## Running the Application

```bash
./mvnw spring-boot:run
```

The API is available at `http://localhost:8080`.  
Swagger UI: `http://localhost:8080/swagger-ui.html`  
Zipkin UI: `http://localhost:9411`

For faster iteration during development, IntelliJ IDEA users can run `AetherOpsApplication.java` directly with the `local` Spring profile and hot-reload via DevTools.

---

## Running Tests

```bash
# Run the full integration test suite (267 tests)
./mvnw clean test
```

Testcontainers starts isolated PostgreSQL and Redis containers automatically — you do not need to configure anything beyond having Docker running. The containers are shared across all tests in the suite via a singleton pattern in `AbstractIntegrationTest` and are never stopped mid-run.

**Important:** The test profile (`application-test.yml`) overrides several properties (Kafka auto-startup, rate-limit thresholds, TOTP key). Do not rely on `.env` values for test runs — the test profile is self-contained.

To run a single test class:

```bash
./mvnw test -Dtest=AuthIntegrationTest
```

To run a specific method:

```bash
./mvnw test -Dtest=AuthIntegrationTest#login_returnsJwtOnValidCredentials
```

---

## Code Style

- **Formatting:** Follow standard Java conventions. No tabs — 4-space indent throughout. Line length ≤ 120 characters.
- **Naming:** Domain objects use full words (`Incident`, not `Inc`). Boolean fields use the `is` prefix only when the name would otherwise be ambiguous.
- **Comments:** Only when the *why* is non-obvious. Do not comment what the code does — well-named identifiers already do that.
- **No unused imports:** Remove them before opening a PR. IntelliJ: `Ctrl+Alt+O` / `⌥⌘O`.
- **No `@SuppressWarnings` without a comment** explaining the specific suppression reason and why it is safe.
- **Null safety:** Prefer `Optional` at service boundaries. Never return `null` from a public method when an empty collection or `Optional.empty()` is appropriate.
- **Transactions:** `@Transactional` belongs on the service layer, not the controller or repository. Keep transactions as short as possible — no remote calls (HTTP, Kafka publish) inside a `@Transactional` boundary except via the Outbox pattern.

---

## Branching Strategy

| Branch | Purpose |
|---|---|
| `master` | Production-ready code. Direct pushes are restricted. |
| `feat/<short-description>` | New features (`feat/totp-backup-codes`) |
| `fix/<short-description>` | Bug fixes (`fix/circuit-breaker-fallback`) |
| `chore/<short-description>` | Dependency updates, tooling, non-functional changes |
| `docs/<short-description>` | Documentation only |

Always branch off the latest `master`:

```bash
git fetch upstream
git checkout -b feat/my-feature upstream/master
```

---

## Commit Message Format

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<optional scope>): <short summary in present tense>

<optional body — explain WHY, not WHAT>
```

**Types:** `feat`, `fix`, `docs`, `chore`, `test`, `refactor`, `perf`

**Examples:**

```
feat(auth): add TOTP backup codes for account recovery

fix(analysis): re-throw business exceptions from Resilience4j fallback
Previously UsageLimitExceededException was swallowed by the @Bulkhead
fallback, causing a 200 response instead of 429.

test(audit): add integration tests for null-enum filter edge case

chore: upgrade Resilience4j to 2.2.0
```

- Summary line ≤ 72 characters
- Use the imperative mood: "add", "fix", "remove" — not "added", "fixes", "removed"
- Reference GitHub issues where relevant: `Closes #42`

---

## Pull Request Guidelines

### Before opening a PR

- [ ] All 267 integration tests pass locally (`./mvnw clean test`)
- [ ] No new compiler warnings introduced
- [ ] New behaviour is covered by at least one integration test
- [ ] `CONTRIBUTING.md` still accurate for any setup changes you made

### PR title

Use the same Conventional Commits format as commit messages:

```
feat(incident): add bulk status transition endpoint
```

### PR description

Use this template:

```markdown
## What
<!-- One or two sentences: what does this PR change? -->

## Why
<!-- The motivation — a bug, a missing feature, a performance issue -->

## How
<!-- Key implementation decisions. Skip the obvious. -->

## Test plan
- [ ] Existing tests still pass
- [ ] Added test: `<TestClass>#<methodName>`
```

### Review process

1. Open a PR against `master`.
2. The CI pipeline runs automatically: Maven test suite + Docker image build.
3. Address review comments with new commits (do not force-push during review — it loses comment context).
4. Once approved and CI is green the PR is merged with a squash commit.

### Commit hygiene on a PR branch

Iterative "WIP" commits are fine during development. Before requesting review, squash noisy commits into logical units:

```bash
git rebase -i upstream/master
```

Each commit in the final PR should pass the test suite independently.

---

## What Not to Contribute

- **Real credentials or API keys** — even in comments or test fixtures. Use the placeholder pattern from `secret.yaml`.
- **Changes to `AbstractIntegrationTest`** without discussing in an issue first — it affects all 267 tests.
- **New `@Transactional` on controller methods** — transactions belong on the service layer.
- **Disabling or skipping tests** without a documented reason and a follow-up issue.
- **Breaking changes to existing REST API contracts** without a versioning strategy agreed in advance.
