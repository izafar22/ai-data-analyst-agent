package com.example.analyst.metadata;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SchemaService {
    private static final List<String> ALLOWED = List.of(
        "categories", "customers", "order_items", "orders", "payments", "products", "refunds");
    private final JdbcTemplate jdbc;

    public SchemaService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Table> tables() { return ALLOWED.stream().map(this::table).toList(); }

    public Table table(String name) {
        if (!ALLOWED.contains(name)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var columns = jdbc.query("""
            SELECT a.attname, pg_catalog.format_type(a.atttypid, a.atttypmod) AS type,
                   NOT a.attnotnull AS nullable,
                   EXISTS (SELECT 1 FROM pg_constraint k
                           WHERE k.conrelid = c.oid AND k.contype = 'p'
                             AND a.attnum = ANY(k.conkey)) AS primary_key
            FROM pg_attribute a
            JOIN pg_class c ON c.oid = a.attrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relname = ?
              AND a.attnum > 0 AND NOT a.attisdropped
            ORDER BY a.attnum
            """, (rs, row) -> new Column(rs.getString("attname"), rs.getString("type"),
                rs.getBoolean("nullable"), rs.getBoolean("primary_key")), name);
        var foreignKeys = jdbc.query("""
            SELECT a.attname AS source_column, target.relname AS target_table,
                   b.attname AS target_column
            FROM pg_constraint k
            JOIN pg_class source ON source.oid = k.conrelid
            JOIN pg_namespace ns ON ns.oid = source.relnamespace
            JOIN pg_class target ON target.oid = k.confrelid
            JOIN pg_namespace nt ON nt.oid = target.relnamespace
            CROSS JOIN LATERAL unnest(k.conkey, k.confkey) AS keys(source_key, target_key)
            JOIN pg_attribute a ON a.attrelid = source.oid AND a.attnum = keys.source_key
            JOIN pg_attribute b ON b.attrelid = target.oid AND b.attnum = keys.target_key
            WHERE k.contype = 'f' AND ns.nspname = 'public' AND nt.nspname = 'public'
              AND source.relname = ?
            ORDER BY a.attname
            """, (rs, row) -> new ForeignKey(rs.getString("source_column"),
                rs.getString("target_table"), rs.getString("target_column")), name)
            .stream().filter(fk -> ALLOWED.contains(fk.referencedTable())).toList();
        return new Table(name, columns, foreignKeys);
    }

    public record Table(String name, List<Column> columns, List<ForeignKey> foreignKeys) {}
    public record Column(String name, String type, boolean nullable, boolean primaryKey) {}
    public record ForeignKey(String column, String referencedTable, String referencedColumn) {}
}
