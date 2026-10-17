package com.example.analyst.config;

import com.zaxxer.hikari.HikariDataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.*;

import javax.sql.DataSource;

@Configuration
public class DatabaseConfiguration {
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    DataSourceProperties migrationProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    DataSource dataSource(@Qualifier("migrationProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    @Bean(destroyMethod = "close")
    HikariDataSource analyticsDataSource(org.springframework.core.env.Environment env) {
        String user = env.getRequiredProperty("analytics.username");
        String password = env.getRequiredProperty("analytics.password");
        if (!user.equals("analyst_readonly")
                || password.isBlank()
                || password.equals(env.getProperty("spring.datasource.password"))) {
            throw new IllegalStateException(
                    "Analytics requires separate analyst_readonly credentials");
        }
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(env.getRequiredProperty("spring.datasource.url"));
        pool.setUsername(user);
        pool.setPassword(password);
        pool.setPoolName("analytics");
        pool.setMaximumPoolSize(
                Math.clamp(env.getProperty("analytics.pool-size", Integer.class, 3), 1, 5));
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(2000);
        pool.setReadOnly(true);
        pool.setConnectionInitSql("SET search_path = pg_catalog, public");
        return pool;
    }
}
