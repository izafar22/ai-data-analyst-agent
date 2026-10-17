# AI Data Analyst Agent

Phase 1 foundation for a PostgreSQL e-commerce analyst: Java 21 / Spring Boot 3, Spring JDBC, Flyway, React / TypeScript / Vite, and Tailwind CSS. The UI displays API health, seven authorized tables, column types, primary keys, and foreign-key relationships.

## Start locally

Prerequisites: Java 21, Node.js 22, Docker Desktop (running), and a POSIX shell. The backend includes Maven Wrapper; no system Maven installation is required. Initial builds need internet access.

From the repository root:

```sh
cp .env.example .env
# Edit .env and set POSTGRES_PASSWORD to your own local password.
docker compose up -d
docker compose ps
set -a
. ./.env
set +a
export DB_URL="jdbc:postgresql://localhost:${POSTGRES_PORT:-5432}/${POSTGRES_DB:-analyst}"
cd backend
./mvnw spring-boot:run
```

Flyway applies V1 (DDL) and V2 (sample data) automatically at backend startup. In a second terminal, from the repository root:

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

Backend integration tests start an isolated PostgreSQL 16 Testcontainer, run real Flyway migrations, and verify health, metadata, allowlist rejection, seed consistency, constraints, and foreign keys. Docker is required; tests fail rather than silently skip when it is unavailable. Frontend tests check all seven table headings, health, and connection/HTTP failures. GitHub Actions runs both suites and the frontend production build on pull requests and pushes to `main`.

## Architecture and boundaries

The backend is a modular monolith organized by feature under `com.example.analyst`. Phase 1 implements `metadata` (fixed catalog queries and an explicit table allowlist) and `health` (Actuator-backed health). Future features remain `analysis`, `agent`, `semantic`, `query`, `visualization`, `conversation`, `security`, and `config`; empty scaffolding is omitted. The frontend consumes same-origin API endpoints.

The data model contains `customers`, `categories`, `products`, `orders`, `order_items`, `payments`, and `refunds`, with identity primary keys, foreign keys, monetary/status checks, and indexes on relationship and order filtering columns. V2 includes 12 fictional customers, 3 categories, 6 products, 45 orders, 90 items, 45 payment attempts, and 3 partial refunds. Fixed dates span September 1–October 15, 2026, making the data deterministic rather than shifting on each installation. Emails use the reserved `example.test` domain. All demo monetary values use one assumed currency; currency conversion is not modeled.

Approved semantic rule for future analysis: **gross booked sales** is `SUM(order_items.quantity * order_items.unit_price)` for orders with status `PAID`, `SHIPPED`, or `DELIVERED`. It is not net revenue or cash received. Central metric definitions belong in the future `semantic` feature; no metric or query execution API is enabled in Phase 1.

Phase 1 uses the local migration/owner account for startup and fixed catalog metadata queries. It is not an analytical execution account. Before any AI execution is enabled, migration credentials and a dedicated read-only analytical role must be separated, and PostgreSQL AST validation, table/column/function allowlists, statement timeouts, bounded results, and tenant authorization must be implemented. Schema descriptions and user questions are untrusted. The LLM will propose SQL only and will never receive credentials or unrestricted execution access.

Spring AI, ECharts, analysis/conversation endpoints, natural-language SQL, authentication, and tenant isolation are deliberately deferred. This is a local demo foundation, not a production deployment. Schema access currently has a static shared allowlist, not user-specific authorization. Database constraints do not enforce cross-row refund totals or complete payment/order business workflows.

## Configuration and troubleshooting

- `.env` is ignored by Git and read by Compose. Export it in the backend shell as shown above; Spring Boot does not automatically load root `.env` files.
- `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, and `POSTGRES_PORT` configure Compose. `DB_URL` configures JDBC; `SERVER_PORT` can override backend port 8080 (update the Vite proxy too).
- If Docker is unavailable, start Docker Desktop and run `docker info`. For Testcontainers discovery on macOS, try `export DOCKER_HOST="unix://$HOME/.docker/run/docker.sock"` if the default socket is absent.
- Inspect database startup with `docker compose logs postgres`. Wait for the container to be healthy before starting the backend.
- Port 5432 already in use: change `POSTGRES_PORT` in `.env`, restart Compose, and export the matching `DB_URL`.
- A persistent volume retains its original credentials. Changing `.env` does not change an existing PostgreSQL password. Use the original password or explicitly recreate disposable demo data.
- Stop PostgreSQL with `docker compose down`; the volume remains. **`docker compose down -v` permanently deletes the demo database volume.**
- Missing tables: inspect backend Flyway startup logs. Do not edit previously applied migrations; add a new migration for future changes.

See [AGENTS.md](AGENTS.md) and [Issue #1](https://github.com/izafar22/ai-data-analyst-agent/issues/1). Phase 2 must wait for review.
