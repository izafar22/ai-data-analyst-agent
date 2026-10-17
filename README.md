# AI Data Analyst Agent

Natural language → validated SQL → PostgreSQL results → charts and explanations.

## Stack
Java 21, Spring Boot 3.x, Spring JDBC, Spring AI, PostgreSQL 16, Flyway, React, TypeScript, Vite, Tailwind CSS, Apache ECharts, Docker Compose, JUnit 5, Testcontainers, Vitest.

## Architecture
Modular monolith: `analysis`, `agent`, `metadata`, `semantic`, `query`, `visualization`, `conversation`, `security`, `config`.

The model generates SQL proposals only. A server-side validator and a restricted database role control execution. No arbitrary model-produced SQL may run without authorization.

## Milestones
1. Foundation: scaffold frontend/backend, Docker PostgreSQL, Flyway, seven e-commerce tables, deterministic demo data, schema discovery, health, tests.
2. SQL security and query execution.
3. Spring AI SQL planning.
4. Analyst UI and charts.
5. Bounded agent workflows, conversation memory, SSE.
6. Production hardening.

See [AGENTS.md](AGENTS.md) and [Phase 1 task](https://github.com/izafar22/ai-data-analyst-agent/issues).
