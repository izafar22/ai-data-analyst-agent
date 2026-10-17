package com.example.analyst.query;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public final class QueryService {
    private static final Logger LOG = LoggerFactory.getLogger(QueryService.class);
    private final SqlValidationService validator;
    private final ReadOnlyQueryExecutor executor;

    public QueryService(SqlValidationService validator, ReadOnlyQueryExecutor executor) {
        this.validator = validator;
        this.executor = executor;
    }

    public QueryResponse execute(String sql, String requestId) {
        long start = System.nanoTime();
        String status = "INTERNAL_ERROR";
        int rows = 0;
        try {
            QueryResponse response = executor.execute(validator.validate(sql), requestId);
            rows = response.rowCount();
            status = "SUCCESS";
            return response;
        } catch (QueryFailure e) {
            status = e.category().name();
            throw e;
        } finally {
            LOG.info(
                    "query requestId={} fingerprint={} durationMs={} status={} rowCount={}",
                    requestId,
                    fingerprint(sql),
                    (System.nanoTime() - start) / 1_000_000,
                    status,
                    rows);
        }
    }

    private String fingerprint(String sql) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            (sql == null ? "" : sql)
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "unavailable";
        }
    }
}
