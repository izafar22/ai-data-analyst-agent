package com.example.analyst;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class FoundationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void healthIncludesNoDatabaseDetails() throws Exception {
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk())
            .andExpect(content().json("{\"status\":\"UP\"}", true));
    }

    @Test
    void listsOnlySevenAuthorizedTablesWithoutRowData() throws Exception {
        jdbc.execute("CREATE TABLE public.private_notes (secret text)");
        try {
            mvc.perform(get("/api/v1/schema/tables")).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(7)))
                .andExpect(jsonPath("$[*].name", contains("categories", "customers", "order_items", "orders", "payments", "products", "refunds")))
                .andExpect(content().string(not(containsString("example.test"))))
                .andExpect(content().string(not(containsString("private_notes"))))
                .andExpect(content().string(not(containsString("flyway_schema_history"))));
        } finally {
            // Clean up even when an HTTP assertion fails; other tests share this container.
            jdbc.execute("DROP TABLE IF EXISTS public.private_notes");
        }
    }

    @Test
    void describesTypesPrimaryKeysAndRelationships() throws Exception {
        mvc.perform(get("/api/v1/schema/tables/order_items")).andExpect(status().isOk())
            .andExpect(jsonPath("$.columns[0].name").value("id"))
            .andExpect(jsonPath("$.columns[0].primaryKey").value(true))
            .andExpect(jsonPath("$.columns[0].nullable").value(false))
            .andExpect(jsonPath("$.columns[4].type").value("numeric(12,2)"))
            .andExpect(jsonPath("$.foreignKeys", hasSize(2)))
            .andExpect(jsonPath("$.foreignKeys[0].column").value("order_id"))
            .andExpect(jsonPath("$.foreignKeys[0].referencedTable").value("orders"))
            .andExpect(jsonPath("$.foreignKeys[0].referencedColumn").value("id"));
    }

    @Test
    void rejectsUnknownPrivateAndSqlLikeNames() throws Exception {
        for (String name : new String[]{"private_notes", "flyway_schema_history", "pg_authid", "CUSTOMERS", "customers' OR '1'='1"}) {
            mvc.perform(get("/api/v1/schema/tables/{name}", name)).andExpect(status().isNotFound());
        }
    }

    @Test
    void seedsRepeatableCoherentCommerceData() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(45);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items", Integer.class)).isEqualTo(90);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refunds", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM payments p WHERE p.amount <> (
                SELECT sum(quantity * unit_price) FROM order_items WHERE order_id = p.order_id)
            """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
            SELECT SUM(i.quantity * i.unit_price) FROM orders o
            JOIN order_items i ON i.order_id=o.id WHERE o.status IN ('PAID','SHIPPED','DELIVERED')
            """, BigDecimal.class)).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(2);
    }

    @Test
    void enforcesChecksAndForeignKeys() {
        assertThatThrownBy(() -> jdbc.update("UPDATE order_items SET quantity=0 WHERE id=1"))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE products SET category_id=99999 WHERE id=1"))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE orders SET status='INVALID' WHERE id=1"))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET amount=-1 WHERE id=1"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
