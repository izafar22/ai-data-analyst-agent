package com.example.analyst.query;

public final class QueryFailure extends RuntimeException {
    public enum Category {
        INVALID_SQL,
        UNAUTHORIZED_SQL,
        QUERY_TIMEOUT,
        QUERY_LIMIT_EXCEEDED,
        DATABASE_UNAVAILABLE,
        INTERNAL_ERROR
    }

    private final Category category;

    public QueryFailure(Category category) {
        super(category.name());
        this.category = category;
    }

    public Category category() {
        return category;
    }
}
