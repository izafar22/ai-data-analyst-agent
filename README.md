# AI Data Analyst Agent

Phase 2 secure SQL engine on the Phase 1 foundation for a PostgreSQL e-commerce analyst: Java 21 / Spring Boot 3, Spring JDBC, Flyway, React / TypeScript / Vite, and Tailwind CSS. The UI displays API health, seven authorized tables, column types, primary keys, and foreign-key relationships.

## Start locally

Prerequisites: Java 21, Node.js 22, Docker Desktop (running), and a POSIX shell. The backend includes Maven Wrapper; no system Maven installation is required. Initial builds need internet access.

From the repository root:

```sh
cp .env.example .env
# Edit .env: set POSTGRES_PASSWORD and a DIFFERENT ANALYTICS_PASSWORD.
docker compose up -d
docker compose ps
set -a
. ./.env
set +a
export DB_URL="jdbc:postgresql://localhost:${POSTGRES_PORT:-5432}/${POSTGRES_DB:-analyst}"
cd backend
./mvnw spring-boot:run
```

Flyway applies V1 (DDL) and V2 (sample data) automatically at backend startup using the migration account. **Before querying or checking health on the first start**, wait for the backend's `Started AnalystApplication` log, then provision the analytical role in a second terminal from the repository root:

```sh
set -a
. ./.env
set +a
./database/provision-readonly.sh
```

This same idempotent command works on fresh volumes and existing Phase 1 volumes; it does not delete data. For an existing volume, keep its original `POSTGRES_USER` / `POSTGRES_PASSWORD`, add a different `ANALYTICS_PASSWORD` to `.env`, start the updated backend, and run provisioning. No container-init-only script is used. The bootstrap reads the analytics secret through the container environment and psql variable quoting; it does not embed passwords in SQL files or shell arguments. Restart the backend after changing credentials so pooled connections cannot retain the old login. Missing/unprovisioned analytics credentials fail closed; the migration account is never a fallback. Health may be DOWN until provisioning completes.

Provisioning runs in one transaction as the database owner and revokes PUBLIC CONNECT/TEMP and public-schema CREATE/EXECUTE/default grants. Use this only in the dedicated demo database, not a shared database with unrelated applications. The script grants SELECT explicitly on the seven business tables, not schema-wide or future-table SELECT. It removes inherited roles and applies restrictive role defaults. Database owners/superusers remain trusted administrators and can change these controls.

In another terminal, from the repository root:

```sh
cd frontend
npm ci
npm run dev
```

Open http://localhost:5173. Vite proxies `/api` to http://localhost:8080. The backend must be running for live data. The production bundle requires a reverse proxy that routes `/api` to the backend; the Vite development proxy is not included in the bundle.

Check the API:

```sh
curl -fsS http://localhost:8080/api/v1/health
curl -fsS http://localhost:8080/api/v1/schema/tables
curl -fsS http://localhost:8080/api/v1/schema/tables/order_items
```

`GET /api/v1/health` returns `{"status":"UP"}` when healthy, or HTTP 503 when unhealthy, without component details. `/actuator/health` is also available. The list endpoint returns a JSON array of seven table objects. The detail endpoint returns one object:

```json
{
  "name": "products",
  "columns": [
    {"name": "id", "type": "bigint", "nullable": false, "primaryKey": true}
  ],
  "foreignKeys": [
    {"column": "category_id", "referencedTable": "categories", "referencedColumn": "id"}
  ]
}
```

The example abbreviates the column list. Unknown, private, and non-allowlisted table names return HTTP 404. Metadata never includes row values, database comments, or column defaults.

## Tests and builds

From the repository root:

```sh
cd backend
./mvnw verify
cd ../frontend
npm ci
npm test
npm run build
```

Backend integration tests start isolated PostgreSQL 16 Testcontainers, run real Flyway migrations and the provisioning SQL (including repeated provisioning), and verify health, metadata, seed consistency, constraints, and foreign keys. Phase 2 tests cover accepted SQL and adversarial ASTs, direct role privilege denials, inherited-table isolation, timeouts, pool recovery, result/input caps, type mapping, sanitized errors, and the disabled non-local endpoint. Docker is required; tests fail rather than silently skip when it is unavailable. Frontend tests check all seven table headings, health, and connection/HTTP failures. GitHub Actions runs both suites and the frontend production build on pull requests and pushes to `main`.

## Architecture and boundaries

The backend is a modular monolith organized by feature under `com.example.analyst`. The foundation implements `metadata` (fixed catalog queries and an explicit table allowlist) and `health` (Actuator-backed health). Phase 2 adds `query` and `config`. Future features remain `analysis`, `agent`, `semantic`, `visualization`, `conversation`, and `security`; empty scaffolding is omitted. The frontend consumes same-origin API endpoints.

The data model contains `customers`, `categories`, `products`, `orders`, `order_items`, `payments`, and `refunds`, with identity primary keys, foreign keys, monetary/status checks, and indexes on relationship and order filtering columns. V2 includes 12 fictional customers, 3 categories, 6 products, 45 orders, 90 items, 45 payment attempts, and 3 partial refunds. Fixed dates span September 1–October 15, 2026, making the data deterministic rather than shifting on each installation. Emails use the reserved `example.test` domain. All demo monetary values use one assumed currency; currency conversion is not modeled.

Approved semantic rule for future analysis: **gross booked sales** is `SUM(order_items.quantity * order_items.unit_price)` for orders with status `PAID`, `SHIPPED`, or `DELIVERED`. It is not net revenue or cash received. Central metric definitions belong in the future `semantic` feature; no metric or query execution API is enabled in Phase 1.

The migration/owner datasource remains responsible for Flyway and fixed metadata queries. **It must never execute LLM-generated or user-supplied SQL.** Phase 2 introduces a separate Hikari analytical pool authenticated as `analyst_readonly` with a distinct required secret. The executor checks the actual role and privileged flags on every checkout. There is no owner fallback. Migration privileges are needed for startup migrations; future production deployment should move migrations to a separate deployment process and remove owner credentials from the long-running application.

## Local SQL diagnostics (Phase 2)

By default, `POST /api/v1/queries/execute` does not exist (HTTP 404). After provisioning, start the backend with the local profile to enable it:

```sh
# In the backend terminal, stop the previous instance first. Keep exported .env and DB_URL.
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

The local profile binds HTTP to `127.0.0.1`. Startup rejects a local-profile override to any other address. Requests with browser Origin headers or non-loopback Host names are rejected. This is a curl-oriented, local-development diagnostic endpoint, **not an authenticated public arbitrary-SQL API**. Never publish it through a reverse proxy, tunnel, port forwarding, or shared host. Local processes can access the entire approved demo dataset. Outside local development only trusted application flows may invoke the internal service after application authorization.

```sh
curl -fsS http://127.0.0.1:8080/api/v1/queries/execute \
  -H 'Content-Type: application/json' --data-binary @- <<'JSON'
{"sql":"SELECT o.status, sum(i.quantity * i.unit_price) AS gross_booked_sales FROM orders o JOIN order_items i ON i.order_id = o.id WHERE o.status IN ('PAID','SHIPPED','DELIVERED') GROUP BY o.status"}
JSON
```

The response contains `columns` (name / PostgreSQL type), positional `rows`, `rowCount`, `truncated`, `executionTimeMs`, and a server-generated `requestId`. Numeric JDBC values retain BigDecimal/Long precision in JSON number serialization (JavaScript clients must use a lossless JSON parser for numbers beyond their precision). Dates/timestamps serialize as ISO values; booleans and null remain typed. Duplicate output labels are rejected; give joined columns distinct aliases. Unaliased expressions receive `expression_N` names. Unknown JSON properties, including credentials, tenant IDs and authorization policies, are rejected.

Errors contain only `category`, a generic `message`, and `requestId`: INVALID_SQL (400), UNAUTHORIZED_SQL (403), QUERY_TIMEOUT (408), QUERY_LIMIT_EXCEEDED (413), DATABASE_UNAVAILABLE (503), INTERNAL_ERROR (500). SQL text, parameters, database error messages and secrets are never returned. Query audit logs contain correlation ID, SHA-256 fingerprint, duration, status and row count, not raw SQL or results. Fingerprints are correlation data, not anonymization; protect logs accordingly.

## Validation and execution security

`QueryController → QueryService → SqlValidationService → ReadOnlyQueryExecutor`. Only the validator can construct `ValidatedQuery`; the executor has no string-SQL entry point. SQL parsing uses pinned [JSqlParser 5.4](https://github.com/JSQLParser/JSqlParser), a maintained multi-dialect AST parser with PostgreSQL syntax support. It is not PostgreSQL's native parser and full PostgreSQL grammar is intentionally not supported.

The compiler parses the complete statement list, requires exactly one SELECT, resolves table/column references per scope, and emits SQL exclusively from approved AST nodes. It never executes original SQL or the parser's generic SQL rendering. Every populated AST option not explicitly handled is rejected against the pinned library's default node shape. Parser upgrades require security regression review. Identifiers and strings are quoted by the compiler; tables are schema-qualified with `ONLY` to exclude unauthorized inherited child-table rows, and allowed functions are qualified with `pg_catalog`. `*` expands only the fixed approved columns. Table allowlists and column lists are code-owned, not caller-supplied policies.

Supported: SELECT, DISTINCT, ordinary inner/left/right/full/cross joins, comparisons, arithmetic, AND/OR/NOT, IN, BETWEEN, IS NULL, grouping, HAVING, ordering, integer LIMIT, non-recursive SELECT CTEs, derived tables, and uncorrelated scalar/IN subqueries. CTEs cannot shadow approved tables or existing CTEs; duplicate/ambiguous references fail closed. Functions are limited to `count`, `sum`, `avg`, `min`, `max`, `date_trunc`, and `date_part`. Date/timestamp literals are supported. Schema-qualified column references must use a table alias (e.g. `o.id`).

Rejected: mutations, multiple statements, SELECT INTO, locks, session/transaction commands, EXPLAIN/COPY/CALL/DO, catalogs/other schemas, unknown columns, user/extension/volatile functions, set-returning functions, casts, windows, recursive CTEs, correlated subqueries, set operations, LATERAL, USING/NATURAL joins, OFFSET/FETCH, and other unimplemented grammar. Regex or LIMIT is not the security boundary. Authorization currently means a fixed seven-table demo policy; there are no tenants in this dataset. Multi-tenant use requires trusted identity-derived authorization and row policies before deployment; request-body tenant IDs cannot supply authority.

Analytics connections use read-only transactions plus PostgreSQL ACLs. `SET LOCAL statement_timeout` (2,000 ms default) and `lock_timeout` (500 ms) reset at rollback on success and failure. Hikari has 3 connections by default, configurable with `ANALYTICS_POOL_SIZE` and capped at 5; pool acquisition times out after 2 seconds. A JDBC cancellation timeout adds a fallback. Responses fetch at most 1,001 rows, return at most 1,000, and mark `truncated` only when an extra row exists. Serialized responses are capped at 1 MiB; overflow returns an error rather than partial data. Request bodies are capped at 20,000 bytes, SQL at 16,000 characters, and AST nesting is bounded. Parser timeout is 1 second.

**Row/response caps do not bound aggregate or join work.** Server timeouts limit runtime, not total CPU/memory/IO across clients; there is no EXPLAIN cost gate or workload resource governor. PostgreSQL catalog visibility and built-in function privileges are not globally removed from the role; the AST boundary blocks their arbitrary use. Direct possession of database credentials is outside the API trust boundary. Database/schema/function owners and the application code are trusted; administrative schema changes require allowlist and security review. This is a production-oriented foundation requiring architecture/security review, not a production multi-tenant service.

Spring AI, model keys, natural-language SQL, autonomous agents, analysis/conversation endpoints, and charts remain deferred. Do not start Phase 3 until Phase 2 is reviewed.

## Configuration and troubleshooting

- `.env` is ignored by Git and read by Compose. Export it in the backend shell as shown above; Spring Boot does not automatically load root `.env` files.
- `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, and `POSTGRES_PORT` configure Compose. `DB_URL` configures JDBC; `SERVER_PORT` can override backend port 8080 (update the Vite proxy too).
- If Docker is unavailable, start Docker Desktop and run `docker info`. For Testcontainers discovery on macOS, try `export DOCKER_HOST="unix://$HOME/.docker/run/docker.sock"` if the default socket is absent.
- Inspect database startup with `docker compose logs postgres`. Wait for the container to be healthy before starting the backend.
- Port 5432 already in use: change `POSTGRES_PORT` in `.env`, restart Compose, and export the matching `DB_URL`.
- A persistent volume retains its original credentials. Changing `.env` does not change an existing PostgreSQL password. Use the original password or explicitly recreate disposable demo data.
- Stop PostgreSQL with `docker compose down`; the volume remains. **`docker compose down -v` permanently deletes the demo database volume.**
- Missing tables: inspect backend Flyway startup logs. Do not edit previously applied migrations; add a new migration for future changes.

See [AGENTS.md](AGENTS.md) and [Issue #1](https://github.com/izafar22/ai-data-analyst-agent/issues/1). See [Issue #3](https://github.com/izafar22/ai-data-analyst-agent/issues/3) for Phase 2; Phase 3 must wait for review.
