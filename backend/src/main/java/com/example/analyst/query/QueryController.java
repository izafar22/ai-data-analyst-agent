package com.example.analyst.query;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Profile("local")
@RestController
@RequestMapping("/api/v1/queries")
public final class QueryController {
    private final QueryService service;

    public QueryController(QueryService service, org.springframework.core.env.Environment env) {
        this.service = service;
        if (!"127.0.0.1".equals(env.getProperty("server.address")))
            throw new IllegalStateException("Local SQL diagnostics require loopback binding");
    }

    public record QueryRequest(String sql) {}

    public record ErrorResponse(String category, String message, String requestId) {}

    @PostMapping("/execute")
    public ResponseEntity<?> execute(@RequestBody QueryRequest request) {
        String id = UUID.randomUUID().toString();
        try {
            return ResponseEntity.ok(service.execute(request.sql(), id));
        } catch (QueryFailure e) {
            return error(e.category(), id);
        }
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> malformed() {
        return error(QueryFailure.Category.INVALID_SQL, UUID.randomUUID().toString());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected() {
        return error(QueryFailure.Category.INTERNAL_ERROR, UUID.randomUUID().toString());
    }

    private ResponseEntity<?> error(QueryFailure.Category category, String id) {
        int status =
                switch (category) {
                    case INVALID_SQL -> 400;
                    case UNAUTHORIZED_SQL -> 403;
                    case QUERY_TIMEOUT -> 408;
                    case QUERY_LIMIT_EXCEEDED -> 413;
                    case DATABASE_UNAVAILABLE -> 503;
                    case INTERNAL_ERROR -> 500;
                };
        return ResponseEntity.status(status)
                .body(new ErrorResponse(category.name(), "Query could not be completed", id));
    }
}
