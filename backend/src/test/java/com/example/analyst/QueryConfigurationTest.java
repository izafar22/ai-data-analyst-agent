package com.example.analyst;

import static org.assertj.core.api.Assertions.*;

import com.example.analyst.config.DatabaseConfiguration;
import com.example.analyst.query.QueryController;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

class QueryConfigurationTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(DatabaseConfiguration.class)
                    .withPropertyValues(
                            "spring.datasource.url=jdbc:postgresql://localhost/unused",
                            "spring.datasource.username=analyst_migration",
                            "spring.datasource.password=migration-secret");

    @Test
    void rejectsOwnerAndSharedCredentials() {
        runner.withPropertyValues(
                        "analytics.username=analyst_migration",
                        "analytics.password=separate-secret")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues(
                        "analytics.username=analyst_readonly",
                        "analytics.password=migration-secret")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("analytics.username=analyst_readonly")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsPublicDiagnosticBinding() {
        assertThatThrownBy(
                        () ->
                                new QueryController(
                                        null,
                                        new MockEnvironment()
                                                .withProperty("server.address", "0.0.0.0")))
                .isInstanceOf(IllegalStateException.class);
    }
}
