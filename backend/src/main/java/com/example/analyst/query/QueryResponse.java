package com.example.analyst.query;

import java.util.List;

public record QueryResponse(
        List<ResultColumn> columns,
        List<List<Object>> rows,
        int rowCount,
        boolean truncated,
        long executionTimeMs,
        String requestId) {
    public record ResultColumn(String name, String type) {}
}
