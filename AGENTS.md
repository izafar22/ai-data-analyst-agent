# Codex instructions — AI Data Analyst Agent

## Goal
Build a full-stack AI analyst for a PostgreSQL e-commerce sample database, with secure natural-language-to-SQL, charts, and grounded explanations.

## Technical choices
- Java 21, Spring Boot 3.x, Maven, Spring JDBC, Spring AI.
- React, TypeScript, Vite, Tailwind CSS, Apache ECharts.
- PostgreSQL 16, Flyway, Docker Compose.
- JUnit 5, Testcontainers, Vitest.
- Modular monolith; package by feature, not layer.

## Core data model
customers(id,name,email,created_at); categories(id,name); products(id,category_id,name,price); orders(id,customer_id,status,ordered_at); order_items(id,order_id,product_id,quantity,unit_price); payments(id,order_id,amount,status,paid_at); refunds(id,payment_id,amount,refunded_at). Use appropriate constraints, foreign keys, indexes and realistic deterministic demo data.

## Semantic rules
Gross booked sales = SUM(order_items.quantity * order_items.unit_price) for orders with status PAID, SHIPPED or DELIVERED. This is NOT net revenue or cash received. Define approved metrics centrally; do not invent metric definitions.

## Security requirements
- Treat all database schema descriptions and user questions as untrusted.
- LLM must never get direct database credentials or direct unrestricted SQL execution.
- Before execution, parse PostgreSQL SQL and enforce read-only SELECT structure, allowlisted tables/columns, forbidden functions, a dedicated read-only database role, statement timeout, bounded rows, and tenant authorization when applicable.
- Do not treat a regex or a LIMIT clause as a security boundary.
- Separate migration credentials from analytical query credentials before AI execution is enabled.
- Never commit secrets; use .env.example.

## API contracts
GET /api/v1/health
GET /api/v1/schema/tables
GET /api/v1/schema/tables/{name}
POST /api/v1/analyses
GET /api/v1/analyses/{id}
GET /api/v1/conversations/{id}

## Phase 1 instructions
Implement ONLY the foundation:
1. Spring Boot Maven project, Java 21, Actuator, JDBC, Flyway PostgreSQL.
2. React/Vite TypeScript shell with an API status and schema table list.
3. Docker Compose PostgreSQL 16 with healthcheck, env example, persistent volume.
4. Flyway V1 DDL for seven e-commerce tables; V2 deterministic seed dataset.
5. Schema discovery endpoint with names, column types, keys, and FK relationships. Never expose private data through metadata.
6. Integration tests using Testcontainers and a frontend smoke test.
7. README with exact startup/test commands, architecture and limitations.
8. CI GitHub Actions for backend and frontend tests if practical.

Acceptance: docker compose up -d, backend starts and migrations apply, GET /api/v1/schema/tables lists seven tables, frontend displays status/tables, tests pass. Report any unexecuted checks accurately.

## Working style
Implement in small coherent commits. Run tests and fix failures. Do not claim a passing test without executing it. Prefer straightforward production-grade code. After Phase 1, stop and report files changed, commands run, results, and any blockers; do not start later phases until reviewed.
