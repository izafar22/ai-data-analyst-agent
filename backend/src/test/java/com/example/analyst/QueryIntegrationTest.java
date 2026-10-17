package com.example.analyst;

import static com.example.analyst.query.QueryFailure.Category.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.analyst.query.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

import javax.sql.DataSource;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Testcontainers
class QueryIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("analytics.password", () -> DatabaseTestSupport.PASSWORD);
        r.add("analytics.statement-timeout-ms", () -> 300);
        r.add("analytics.pool-size", () -> 1);
        r.add("analytics.lock-timeout-ms", () -> 100);
    }

    @Autowired QueryService service;
    @Autowired SqlValidationService validator;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate migration;

    @Autowired
    @Qualifier("analyticsDataSource")
    DataSource analytics;

    private static boolean provisioned;

    @BeforeEach
    void provision() throws Exception {
        if (!provisioned) {
            DatabaseTestSupport.provision(POSTGRES);
            DatabaseTestSupport.provision(POSTGRES);
            provisioned = true;
        }
    }

    QueryResponse run(String sql) {
        return service.execute(sql, UUID.randomUUID().toString());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SELECT id, name FROM customers ORDER BY id LIMIT 3",
                "SELECT id FROM orders WHERE status IN ('PAID','SHIPPED') AND id BETWEEN 1 AND 20",
                "SELECT id FROM orders WHERE customer_id IN (SELECT id FROM customers WHERE id >"
                        + " 3)",
                "SELECT TRUE AS yes, FALSE AS no",
                "SELECT id FROM orders WHERE NOT (status='CANCELLED')",
                "SELECT date_part('year', ordered_at) AS year FROM orders LIMIT 1",
                "SELECT o.status, sum(i.quantity * i.unit_price) AS total FROM orders o JOIN"
                        + " order_items i ON o.id=i.order_id GROUP BY o.status ORDER BY total DESC",
                "WITH totals AS (SELECT order_id, sum(quantity * unit_price) AS amount FROM"
                    + " order_items GROUP BY order_id) SELECT order_id, amount FROM totals ORDER BY"
                    + " order_id",
                "SELECT id FROM orders WHERE ordered_at >= TIMESTAMP '2026-10-01 00:00:00'",
                "SELECT date_trunc('month', ordered_at) AS month, count(*) AS orders FROM orders"
                        + " GROUP BY date_trunc('month', ordered_at)",
                "SELECT c.id FROM (SELECT id FROM customers WHERE id > 2) c ORDER BY c.id",
                "SELECT (SELECT max(price) AS maximum FROM products) AS highest",
                "SELECT * FROM categories",
                "SELECT count(*) AS total FROM orders WHERE (status='PAID' OR status='DELIVERED')"
                        + " AND id > 0",
                "SELECT id FROM public.orders WHERE status = 'PAID''; DROP TABLE orders; --'"
            })
    void allowedQueries(String sql) {
        assertThat(run(sql).columns()).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "INSERT INTO customers(name,email,created_at) VALUES ('x','x',now())",
                "UPDATE products SET price=0",
                "DELETE FROM orders",
                "DROP TABLE customers",
                "SELECT 1; SELECT 2",
                "SELECT * FROM pg_catalog.pg_authid",
                "SELECT * FROM information_schema.tables",
                "SELECT pg_sleep(1)",
                "SELECT pg_read_file('/etc/passwd')",
                "SELECT set_config('search_path','public',false)",
                "SELECT * FROM generate_series(1,10)",
                "WITH x AS (DELETE FROM orders RETURNING id) SELECT id FROM x",
                "SELECT * INTO new_table FROM orders",
                "SELECT id FROM orders FOR UPDATE",
                "SELECT id FROM orders FOR SHARE",
                "COPY orders TO STDOUT",
                "CALL unsafe()",
                "DO $$ BEGIN END $$",
                "SET ROLE analyst_migration",
                "BEGIN",
                "EXPLAIN ANALYZE SELECT * FROM orders",
                "SELECT password FROM customers",
                "SELECT c.secret FROM customers c",
                "SELECT c.secret FROM (SELECT id FROM customers) c",
                "WITH x AS (SELECT * FROM flyway_schema_history) SELECT * FROM x",
                "SELECT id FROM public.pg_class",
                "SELECT public.count(*) FROM orders",
                "SELECT * FROM customers c JOIN orders o ON 1=1",
                "SELECT id FROM customers c JOIN orders o ON c.id=o.customer_id",
                "SELECT c.id FROM customers c WHERE EXISTS (SELECT 1 FROM pg_class)",
                "SELECT id FROM orders UNION SELECT oid FROM pg_class",
                "SELECT id FROM orders; DROP TABLE orders --",
                "SELECT id AS x, status AS x FROM orders",
                "SELECT id FROM orders o ORDER BY pg_sleep(1)",
                "WITH orders AS (SELECT oid FROM pg_class) SELECT * FROM orders",
                "SELECT id::regclass FROM orders",
                "SELECT lo_import('/tmp/a')",
                "SELECT nextval('orders_id_seq')",
                "SELECT * FROM orders TABLESAMPLE SYSTEM(10)",
                "SELECT id FROM orders OFFSET 1",
                "SELECT o.id FROM orders o WHERE o.id=(SELECT o.id FROM customers c)",
                "WITH c AS (SELECT id FROM customers) SELECT email FROM c",
                "SELECT x.id FROM orders o",
                "SELECT id FROM orders WHERE id=1 /* comment */; DELETE FROM products",
                "SELECT id FROM orders FOR NO KEY UPDATE",
                "SELECT id FROM orders FOR KEY SHARE",
                "SELECT id FROM orders ORDER BY id; -- comment\n SELECT 1",
                "SELECT E'abc' AS escaped",
                "SELECT count(*) FILTER (WHERE pg_sleep(1) IS NULL) FROM orders",
                "SELECT id FROM orders WHERE id IN (SELECT oid FROM pg_class)"
            })
    void rejectsUnsafeSql(String sql) {
        assertThatThrownBy(() -> run(sql)).isInstanceOf(QueryFailure.class);
    }

    @Test
    void rolePrivilegesAndNewObjects() throws Exception {
        migration.execute("CREATE TABLE public.unexpected (id integer)");
        try (Connection c = analytics.getConnection();
                Statement s = c.createStatement()) {
            c.setReadOnly(false); // Prove ACL enforcement independently of JDBC read-only mode.
            s.execute("SET default_transaction_read_only=off");
            for (String sql :
                    List.of(
                            "UPDATE products SET price=0",
                            "DELETE FROM orders",
                            "INSERT INTO categories(name) VALUES ('bad')",
                            "TRUNCATE orders CASCADE",
                            "CREATE TABLE public.bad(id integer)",
                            "CREATE TEMP TABLE bad(id integer)",
                            "SELECT * FROM flyway_schema_history",
                            "SELECT * FROM unexpected",
                            "CREATE SCHEMA bad",
                            "SET ROLE " + POSTGRES.getUsername())) {
                assertThatThrownBy(() -> s.execute(sql)).isInstanceOf(SQLException.class);
            }
            assertThat(s.executeQuery("SELECT count(*) FROM products").next()).isTrue();
            s.execute("SET default_transaction_read_only=on");
        } finally {
            migration.execute("DROP TABLE public.unexpected");
        }
    }

    @Test
    void inheritedTablesDoNotBypassAllowlist() {
        migration.execute("CREATE TABLE public.hidden_child () INHERITS (public.categories)");
        try {
            migration.update("INSERT INTO hidden_child(id,name) VALUES (999,'hidden')");
            assertThat(run("SELECT id FROM categories").rowCount()).isEqualTo(3);
        } finally {
            migration.execute("DROP TABLE public.hidden_child");
        }
    }

    @Test
    void executorRejectsMigrationIdentity() {
        var executor =
                new ReadOnlyQueryExecutor(
                        migration.getDataSource(),
                        mapper,
                        new org.springframework.mock.env.MockEnvironment());
        assertThatThrownBy(() -> executor.execute(validator.validate("SELECT 1"), "identity-test"))
                .isInstanceOfSatisfying(
                        QueryFailure.class,
                        e -> assertThat(e.category()).isEqualTo(DATABASE_UNAVAILABLE));
    }

    @Test
    void rowCapAndTypedValues() {
        var capped =
                run(
                        "SELECT a.id AS a, b.id AS b, c.id AS c FROM orders a CROSS JOIN orders b"
                                + " CROSS JOIN orders c");
        assertThat(capped.rowCount()).isEqualTo(1000);
        assertThat(capped.truncated()).isTrue();
        assertThat(run("SELECT id FROM orders LIMIT 2").truncated()).isFalse();
        var values =
                run("SELECT price, id, name, price > 0 AS positive, NULL AS missing FROM products"
                                + " ORDER BY id LIMIT 1")
                        .rows()
                        .getFirst();
        assertThat(values.get(0)).isInstanceOf(BigDecimal.class);
        assertThat(values.get(1)).isInstanceOf(Long.class);
        assertThat(values.get(2)).isInstanceOf(String.class);
        assertThat(values.get(3)).isEqualTo(true);
        assertThat(values.get(4)).isNull();
        assertThat(run("SELECT ordered_at FROM orders LIMIT 1").rows().getFirst().getFirst())
                .isInstanceOf(java.time.OffsetDateTime.class);
    }

    @Test
    void timeoutsAndPoolRecovery() throws Exception {
        assertThatThrownBy(
                        () ->
                                run(
                                        "SELECT count(*) FROM orders a CROSS JOIN orders b CROSS"
                                            + " JOIN orders c CROSS JOIN orders d CROSS JOIN orders"
                                            + " e CROSS JOIN orders f"))
                .isInstanceOfSatisfying(
                        QueryFailure.class, e -> assertThat(e.category()).isEqualTo(QUERY_TIMEOUT));
        try (Connection owner = migration.getDataSource().getConnection();
                Statement lock = owner.createStatement()) {
            owner.setAutoCommit(false);
            lock.execute("LOCK TABLE products IN ACCESS EXCLUSIVE MODE");
            assertThatThrownBy(() -> run("SELECT id FROM products"))
                    .isInstanceOfSatisfying(
                            QueryFailure.class,
                            e -> assertThat(e.category()).isEqualTo(QUERY_TIMEOUT));
            owner.rollback();
        }
        assertThatThrownBy(() -> run("SELECT 1 / 0 AS bad")).isInstanceOf(QueryFailure.class);
        assertThat(run("SELECT id FROM products").rowCount()).isEqualTo(6);
        try (Connection c = analytics.getConnection();
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SHOW statement_timeout")) {
            assertThat(r.next()).isTrue();
            assertThat(r.getString(1)).isEqualTo("2s");
        }
    }

    @Test
    void numericPrecisionAndDateMapping() {
        var values =
                run("SELECT 12345678901234567890.123 AS exact, DATE '2026-10-01' AS day")
                        .rows()
                        .getFirst();
        assertThat(values.get(0)).isEqualTo(new BigDecimal("12345678901234567890.123"));
        assertThat(values.get(1)).isEqualTo(java.time.LocalDate.of(2026, 10, 1));
        assertThat(
                        run("SELECT a.id AS a, b.id AS b FROM orders a CROSS JOIN orders b LIMIT"
                                + " 1000")
                                .truncated())
                .isFalse();
        assertThatThrownBy(() -> run("SELECT 1e1000000000 AS huge"))
                .isInstanceOfSatisfying(
                        QueryFailure.class,
                        e -> assertThat(e.category()).isEqualTo(QUERY_LIMIT_EXCEEDED));
    }

    @Test
    void responseAndInputLimits() {
        String value = "x".repeat(1500);
        assertThatThrownBy(
                        () ->
                                run(
                                        "SELECT '"
                                                + value
                                                + "' AS payload FROM orders a CROSS JOIN orders b"))
                .isInstanceOfSatisfying(
                        QueryFailure.class,
                        e -> assertThat(e.category()).isEqualTo(QUERY_LIMIT_EXCEEDED));
        assertThatThrownBy(() -> run("SELECT '" + "x".repeat(16001) + "'"))
                .isInstanceOfSatisfying(
                        QueryFailure.class,
                        e -> assertThat(e.category()).isEqualTo(QUERY_LIMIT_EXCEEDED));
    }

    @Test
    void unavailableDatabaseIsSanitized() {
        com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername("analyst_readonly");
        config.setPassword("wrong-password");
        config.setConnectionTimeout(250);
        config.setInitializationFailTimeout(-1);
        config.setMaximumPoolSize(1);
        try (var pool = new com.zaxxer.hikari.HikariDataSource(config)) {
            var executor =
                    new ReadOnlyQueryExecutor(
                            pool, mapper, new org.springframework.mock.env.MockEnvironment());
            assertThatThrownBy(
                            () ->
                                    executor.execute(
                                            validator.validate("SELECT 1"), "unavailable-test"))
                    .isInstanceOfSatisfying(
                            QueryFailure.class,
                            e -> assertThat(e.category()).isEqualTo(DATABASE_UNAVAILABLE));
        }
    }

    @Test
    void httpTimeoutIsStructured() throws Exception {
        String sql =
                "SELECT count(*) FROM orders a CROSS JOIN orders b CROSS JOIN orders c CROSS JOIN"
                        + " orders d CROSS JOIN orders e CROSS JOIN orders f";
        mvc.perform(
                        post("/api/v1/queries/execute")
                                .contentType("application/json")
                                .content(mapper.writeValueAsString(Map.of("sql", sql))))
                .andExpect(status().isRequestTimeout())
                .andExpect(jsonPath("$.category").value("QUERY_TIMEOUT"))
                .andExpect(jsonPath("$.requestId").isString());
    }

    @Test
    void httpContract() throws Exception {
        mvc.perform(
                        post("/api/v1/queries/execute")
                                .contentType("application/json")
                                .content(
                                        mapper.writeValueAsString(
                                                Map.of("sql", "SELECT id FROM products LIMIT 1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(1))
                .andExpect(jsonPath("$.requestId").isString());
        mvc.perform(
                        post("/api/v1/queries/execute")
                                .contentType("application/json")
                                .content("{\"sql\":\"SELECT pg_sleep(1)\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.category").value("UNAUTHORIZED_SQL"));
        mvc.perform(
                        post("/api/v1/queries/execute")
                                .contentType("application/json")
                                .content("{\"sql\":\"SELECT 1\",\"tenantId\":\"other\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/api/v1/queries/execute")
                                .contentType("application/json")
                                .header("Origin", "https://untrusted.example")
                                .content("{\"sql\":\"SELECT 1\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/v1/queries/execute")
                                .contentType("application/json")
                                .content(" ".repeat(20001)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.category").value("QUERY_LIMIT_EXCEEDED"));
        mvc.perform(post("/api/v1/queries/execute").contentType("application/json").content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.category").value("INVALID_SQL"));
    }
}
