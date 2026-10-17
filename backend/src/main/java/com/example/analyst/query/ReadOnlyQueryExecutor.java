package com.example.analyst.query;

import static com.example.analyst.query.QueryFailure.Category.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

import javax.sql.DataSource;

@Service
public final class ReadOnlyQueryExecutor {
    private final DataSource dataSource;
    private final ObjectMapper mapper;
    private final int timeout, lockTimeout, maxBytes;

    public ReadOnlyQueryExecutor(
            @Qualifier("analyticsDataSource") DataSource dataSource,
            ObjectMapper mapper,
            Environment env) {
        this.dataSource = dataSource;
        this.mapper = mapper;
        timeout =
                Math.clamp(
                        env.getProperty("analytics.statement-timeout-ms", Integer.class, 2000),
                        1,
                        10000);
        lockTimeout =
                Math.clamp(
                        env.getProperty("analytics.lock-timeout-ms", Integer.class, 500),
                        1,
                        timeout);
        maxBytes =
                Math.clamp(
                        env.getProperty("analytics.max-response-bytes", Integer.class, 1048576),
                        256,
                        1048576);
    }

    public QueryResponse execute(SqlValidationService.ValidatedQuery query, String requestId) {
        long start = System.nanoTime();
        try (Connection c = dataSource.getConnection()) {
            c.setReadOnly(true);
            c.setAutoCommit(false);
            try {
                // Check the actual authenticated role on every checkout, not merely the configured
                // username.
                try (Statement s = c.createStatement();
                        ResultSet r =
                                s.executeQuery(
                                        "SELECT current_user, rolsuper, rolcreaterole, rolcreatedb,"
                                            + " rolbypassrls FROM pg_roles WHERE"
                                            + " rolname=current_user")) {
                    if (!r.next()
                            || !r.getString(1).equals("analyst_readonly")
                            || r.getBoolean(2)
                            || r.getBoolean(3)
                            || r.getBoolean(4)
                            || r.getBoolean(5)) throw new QueryFailure(DATABASE_UNAVAILABLE);
                }
                try (Statement s = c.createStatement()) {
                    s.execute("SET TRANSACTION READ ONLY");
                    s.execute("SET LOCAL statement_timeout = '" + timeout + "ms'");
                    s.execute("SET LOCAL lock_timeout = '" + lockTimeout + "ms'");
                    s.execute("SET LOCAL search_path = pg_catalog, public");
                    s.execute("SET LOCAL standard_conforming_strings = on");
                }
                List<QueryResponse.ResultColumn> columns = new ArrayList<>();
                List<List<Object>> rows = new ArrayList<>();
                boolean truncated = false;
                try (PreparedStatement s = c.prepareStatement(query.sql())) {
                    s.setMaxRows(1001);
                    s.setFetchSize(1001);
                    s.setQueryTimeout((timeout + 999) / 1000 + 1);
                    try (ResultSet r = s.executeQuery()) {
                        ResultSetMetaData md = r.getMetaData();
                        Set<String> names = new HashSet<>();
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            if (!names.add(md.getColumnLabel(i)))
                                throw new QueryFailure(INVALID_SQL);
                            columns.add(
                                    new QueryResponse.ResultColumn(
                                            md.getColumnLabel(i), md.getColumnTypeName(i)));
                        }
                        int bytes = mapper.writeValueAsBytes(columns).length;
                        while (r.next()) {
                            if (rows.size() == 1000) {
                                truncated = true;
                                break;
                            }
                            List<Object> row = new ArrayList<>();
                            for (int i = 1; i <= md.getColumnCount(); i++) row.add(value(r, md, i));
                            bytes += mapper.writeValueAsBytes(row).length + 1;
                            if (bytes > maxBytes) throw new QueryFailure(QUERY_LIMIT_EXCEEDED);
                            rows.add(row);
                        }
                    }
                }
                QueryResponse result =
                        new QueryResponse(
                                columns,
                                rows,
                                rows.size(),
                                truncated,
                                (System.nanoTime() - start) / 1_000_000,
                                requestId);
                if (mapper.writeValueAsBytes(result).length > maxBytes)
                    throw new QueryFailure(QUERY_LIMIT_EXCEEDED);
                return result;
            } finally {
                c.rollback();
            }
        } catch (QueryFailure e) {
            throw e;
        } catch (SQLException e) {
            String state = e.getSQLState();
            if ("57014".equals(state) || "55P03".equals(state))
                throw new QueryFailure(QUERY_TIMEOUT);
            if ("42501".equals(state)) throw new QueryFailure(UNAUTHORIZED_SQL);
            if (state != null && (state.startsWith("22") || state.startsWith("42")))
                throw new QueryFailure(INVALID_SQL);
            throw new QueryFailure(DATABASE_UNAVAILABLE);
        } catch (Exception e) {
            throw new QueryFailure(INTERNAL_ERROR);
        }
    }

    private Object value(ResultSet r, ResultSetMetaData m, int i) throws SQLException {
        if ("timestamptz".equals(m.getColumnTypeName(i)))
            return r.getObject(i, java.time.OffsetDateTime.class);
        return switch (m.getColumnType(i)) {
            case Types.BIGINT,
                    Types.INTEGER,
                    Types.SMALLINT,
                    Types.NUMERIC,
                    Types.DECIMAL,
                    Types.BOOLEAN,
                    Types.BIT,
                    Types.DOUBLE,
                    Types.FLOAT,
                    Types.REAL ->
                    r.getObject(i);
            case Types.DATE -> r.getObject(i, java.time.LocalDate.class);
            case Types.TIMESTAMP -> r.getObject(i, java.time.LocalDateTime.class);
            case Types.TIMESTAMP_WITH_TIMEZONE -> r.getObject(i, java.time.OffsetDateTime.class);
            case Types.VARCHAR, Types.CHAR, Types.LONGVARCHAR -> r.getString(i);
            case Types.NULL -> null;
            default -> throw new QueryFailure(QUERY_LIMIT_EXCEEDED);
        };
    }
}
