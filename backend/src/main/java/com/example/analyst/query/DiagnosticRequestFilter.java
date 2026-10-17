package com.example.analyst.query;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.*;
import java.util.UUID;

/** Bound the JSON body before Jackson allocation, including chunked requests. */
@Component
@Profile("local")
public class DiagnosticRequestFilter extends OncePerRequestFilter {
    private final ObjectMapper mapper;

    public DiagnosticRequestFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest r) {
        return !r.getRequestURI().equals("/api/v1/queries/execute");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        // A diagnostic curl-only interface: reject browser origins and DNS-rebinding hostnames.
        if (request.getHeader("Origin") != null
                || !(request.getServerName().equals("localhost")
                        || request.getServerName().equals("127.0.0.1"))) {
            fail(response, 403, "UNAUTHORIZED_SQL");
            return;
        }
        byte[] body = request.getInputStream().readNBytes(20_001);
        if (body.length > 20_000) {
            fail(response, 413, "QUERY_LIMIT_EXCEEDED");
            return;
        }
        chain.doFilter(
                new HttpServletRequestWrapper(request) {
                    @Override
                    public ServletInputStream getInputStream() {
                        ByteArrayInputStream input = new ByteArrayInputStream(body);
                        return new ServletInputStream() {
                            public int read() {
                                return input.read();
                            }

                            public boolean isFinished() {
                                return input.available() == 0;
                            }

                            public boolean isReady() {
                                return true;
                            }

                            public void setReadListener(ReadListener listener) {
                                throw new UnsupportedOperationException();
                            }
                        };
                    }

                    @Override
                    public BufferedReader getReader() {
                        return new BufferedReader(
                                new InputStreamReader(
                                        getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                    }
                },
                response);
    }

    private void fail(HttpServletResponse r, int status, String category) throws IOException {
        r.setStatus(status);
        r.setContentType("application/json");
        mapper.writeValue(
                r.getOutputStream(),
                new QueryController.ErrorResponse(
                        category, "Query could not be completed", UUID.randomUUID().toString()));
    }
}
