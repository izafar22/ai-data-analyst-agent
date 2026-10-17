package com.example.analyst;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;

final class DatabaseTestSupport {
    static final String PASSWORD = "test-analytics-only-secret";

    static void provision(PostgreSQLContainer<?> postgres) throws Exception {
        postgres.copyFileToContainer(
                MountableFile.forHostPath(
                        Path.of("../database/provision-readonly.sql").toAbsolutePath()),
                "/tmp/provision.sql");
        var result =
                postgres.execInContainer(
                        "psql",
                        "-U",
                        postgres.getUsername(),
                        "-d",
                        postgres.getDatabaseName(),
                        "-v",
                        "analytics_password=" + PASSWORD,
                        "-f",
                        "/tmp/provision.sql");
        if (result.getExitCode() != 0) throw new IllegalStateException(result.getStderr());
    }
}
