package com.example.analyst.query;

import static com.example.analyst.query.QueryFailure.Category.*;

import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.*;

import org.springframework.stereotype.Service;

import java.lang.reflect.Modifier;
import java.util.*;
import java.util.stream.Collectors;

/** Compiles a closed SQL subset. Neither raw input nor AST toString reaches JDBC. */
@Service
public final class SqlValidationService {
    private static final Map<String, List<String>> TABLES =
            Map.of(
                    "customers", List.of("id", "name", "email", "created_at"),
                    "categories", List.of("id", "name"),
                    "products", List.of("id", "category_id", "name", "price"),
                    "orders", List.of("id", "customer_id", "status", "ordered_at"),
                    "order_items",
                            List.of("id", "order_id", "product_id", "quantity", "unit_price"),
                    "payments", List.of("id", "order_id", "amount", "status", "paid_at"),
                    "refunds", List.of("id", "payment_id", "amount", "refunded_at"));
    private static final Map<String, String> OPERATORS =
            Map.ofEntries(
                    Map.entry("Addition", "+"),
                    Map.entry("Subtraction", "-"),
                    Map.entry("Multiplication", "*"),
                    Map.entry("Division", "/"),
                    Map.entry("EqualsTo", "="),
                    Map.entry("NotEqualsTo", "<>"),
                    Map.entry("GreaterThan", ">"),
                    Map.entry("GreaterThanEquals", ">="),
                    Map.entry("MinorThan", "<"),
                    Map.entry("MinorThanEquals", "<="),
                    Map.entry("AndExpression", "AND"),
                    Map.entry("OrExpression", "OR"));
    private static final Set<String> FUNCTIONS =
            Set.of("count", "sum", "avg", "min", "max", "date_trunc", "date_part");

    public static final class ValidatedQuery {
        private final String sql;

        private ValidatedQuery(String sql) {
            this.sql = sql;
        }

        String sql() {
            return sql;
        }
    }

    private record Compiled(String sql, List<String> columns) {}

    private static final class Scope {
        final Map<String, List<String>> sources = new LinkedHashMap<>();
        final Map<String, Compiled> ctes;

        Scope(Map<String, Compiled> ctes) {
            this.ctes = new LinkedHashMap<>(ctes);
        }
    }

    public ValidatedQuery validate(String sql) {
        if (sql == null || sql.isBlank()) throw new QueryFailure(INVALID_SQL);
        if (sql.length() > 16_000) throw new QueryFailure(QUERY_LIMIT_EXCEEDED);
        try {
            var statements = CCJSqlParserUtil.parseStatements(sql, p -> p.withTimeOut(1000));
            if (statements.size() != 1 || !(statements.get(0) instanceof Select select)) deny();
            return new ValidatedQuery(compile((Select) statements.get(0), Map.of(), 0).sql());
        } catch (QueryFailure e) {
            throw e;
        } catch (Exception | StackOverflowError e) {
            throw new QueryFailure(INVALID_SQL);
        }
    }

    private Compiled compile(Select select, Map<String, Compiled> inherited, int depth) {
        if (depth > 12) throw new QueryFailure(QUERY_LIMIT_EXCEEDED);
        if (!(select instanceof PlainSelect p)) throw new QueryFailure(UNAUTHORIZED_SQL);
        shape(
                p,
                "withItemsList",
                "selectItems",
                "fromItem",
                "joins",
                "where",
                "groupBy",
                "having",
                "orderByElements",
                "limit",
                "distinct");
        Scope scope = new Scope(inherited);
        List<String> with = new ArrayList<>();
        if (p.getWithItemsList() != null)
            for (WithItem<?> item : p.getWithItemsList()) {
                shape(item, "statement", "alias");
                String name = alias(item.getAlias());
                if (scope.ctes.containsKey(name) || TABLES.containsKey(name)) deny();
                if (!(item.getStatement() instanceof ParenthesedSelect nested))
                    throw new QueryFailure(UNAUTHORIZED_SQL);
                shape(nested, "select");
                Compiled compiled = compile(nested.getSelect(), scope.ctes, depth + 1);
                scope.ctes.put(name, compiled);
                with.add(q(name) + " AS (" + compiled.sql() + ")");
            }
        String from = p.getFromItem() == null ? "" : " FROM " + from(p.getFromItem(), scope, depth);
        if (p.getJoins() != null)
            for (Join join : p.getJoins()) {
                shape(
                        join,
                        "fromItem",
                        "onExpressions",
                        "inner",
                        "left",
                        "right",
                        "full",
                        "outer",
                        "cross",
                        "simple");
                String right = from(join.getRightItem(), scope, depth);
                String kind =
                        join.isLeft()
                                ? "LEFT"
                                : join.isRight() ? "RIGHT" : join.isFull() ? "FULL" : "INNER";
                if (join.isCross() || join.isSimple()) {
                    if (!join.getOnExpressions().isEmpty()) deny();
                    from += " CROSS JOIN " + right;
                } else {
                    if (join.getOnExpressions().isEmpty()) deny();
                    from +=
                            " "
                                    + kind
                                    + " JOIN "
                                    + right
                                    + " ON "
                                    + join.getOnExpressions().stream()
                                            .map(e -> expr(e, scope, depth))
                                            .collect(Collectors.joining(" AND "));
                }
            }
        List<String> projections = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (SelectItem<?> item : p.getSelectItems()) {
            shape(item, "expression", "alias");
            Expression e = item.getExpression();
            if (e
                    instanceof
                    AllColumns) { // Explicit expansion prevents schema additions from becoming
                // authorized.
                if (item.getAlias() != null) deny();
                if (e instanceof AllTableColumns all) {
                    shape(all, "table");
                    String source = tableQualifier(all.getTable());
                    if (!scope.sources.containsKey(source)) deny();
                    expand(source, scope.sources.get(source), projections, names);
                } else {
                    shape(e);
                    if (scope.sources.isEmpty()) deny();
                    scope.sources.forEach(
                            (source, columns) -> expand(source, columns, projections, names));
                }
            } else {
                String rendered = expr(e, scope, depth);
                String name =
                        item.getAlias() != null
                                ? alias(item.getAlias())
                                : e instanceof Column c
                                        ? id(c.getColumnName())
                                        : "expression_" + (names.size() + 1);
                names.add(name);
                projections.add(rendered + " AS " + q(name));
            }
        }
        if (names.isEmpty() || names.size() > 100 || new HashSet<>(names).size() != names.size())
            deny();
        String distinct = "";
        if (p.getDistinct() != null) {
            shape(p.getDistinct());
            distinct = "DISTINCT ";
        }
        String result =
                (with.isEmpty() ? "" : "WITH " + String.join(", ", with) + " ")
                        + "SELECT "
                        + distinct
                        + String.join(", ", projections)
                        + from;
        if (p.getWhere() != null) result += " WHERE " + expr(p.getWhere(), scope, depth);
        if (p.getGroupBy() != null) {
            shape(p.getGroupBy(), "groupByExpressions");
            result +=
                    " GROUP BY "
                            + expressions(p.getGroupBy().getGroupByExpressionList(), scope, depth);
        }
        if (p.getHaving() != null) result += " HAVING " + expr(p.getHaving(), scope, depth);
        if (p.getOrderByElements() != null) {
            List<String> order = new ArrayList<>();
            for (OrderByElement element : p.getOrderByElements()) {
                shape(element, "expression", "asc", "ascDescPresent", "nullOrdering");
                Expression e = element.getExpression();
                String value =
                        e instanceof Column c
                                        && c.getTable() == null
                                        && names.contains(id(c.getColumnName()))
                                ? q(id(c.getColumnName()))
                                : expr(e, scope, depth);
                order.add(
                        value
                                + (element.isAsc() ? " ASC" : " DESC")
                                + (element.getNullOrdering() == null
                                        ? ""
                                        : " NULLS "
                                                + (element.getNullOrdering()
                                                                == OrderByElement.NullOrdering
                                                                        .NULLS_FIRST
                                                        ? "FIRST"
                                                        : "LAST")));
            }
            result += " ORDER BY " + String.join(", ", order);
        }
        if (p.getLimit() != null) {
            shape(p.getLimit(), "rowCount");
            if (!(p.getLimit().getRowCount() instanceof LongValue l) || l.getValue() < 0) deny();
            result += " LIMIT " + ((LongValue) p.getLimit().getRowCount()).getValue();
        }
        return new Compiled(result, names);
    }

    private String from(FromItem item, Scope scope, int depth) {
        String name;
        List<String> columns;
        String sql;
        if (item instanceof Table t) {
            shape(t, "partItems", "partDelimiters", "alias");
            name = id(t.getName());
            String schema = t.getSchemaName() == null ? null : id(t.getSchemaName());
            if (t.getNameParts().size() > 2 || (schema != null && !schema.equals("public"))) deny();
            if (schema == null && scope.ctes.containsKey(name)) {
                columns = scope.ctes.get(name).columns();
                sql = q(name);
            } else {
                columns = TABLES.get(name);
                if (columns == null) throw new QueryFailure(UNAUTHORIZED_SQL);
                sql = "ONLY public." + q(name);
            }
        } else if (item instanceof ParenthesedSelect nested) {
            shape(nested, "select", "alias");
            if (nested.getAlias() == null) deny();
            Compiled compiled = compile(nested.getSelect(), scope.ctes, depth + 1);
            name = alias(nested.getAlias());
            columns = compiled.columns();
            sql = "(" + compiled.sql() + ")";
        } else throw new QueryFailure(UNAUTHORIZED_SQL);
        String source = item.getAlias() == null ? name : alias(item.getAlias());
        if (scope.sources.putIfAbsent(source, columns) != null) deny();
        return sql + " AS " + q(source);
    }

    private String expr(Expression e, Scope scope, int depth) {
        if (depth > 40) throw new QueryFailure(QUERY_LIMIT_EXCEEDED);
        if (e instanceof Column c) {
            shape(c, "table", "columnName");
            String name = id(c.getColumnName());
            if (c.getTable() != null && c.getTable().getName() != null) {
                String source = tableQualifier(c.getTable());
                if (!scope.sources.containsKey(source) || !scope.sources.get(source).contains(name))
                    deny();
                return q(source) + "." + q(name);
            }
            List<String> matches =
                    scope.sources.entrySet().stream()
                            .filter(x -> x.getValue().contains(name))
                            .map(Map.Entry::getKey)
                            .toList();
            if (matches.size() != 1) deny();
            return q(matches.getFirst()) + "." + q(name);
        }
        if (e instanceof LongValue l) return Long.toString(l.getValue());
        if (e instanceof DoubleValue d) {
            java.math.BigDecimal number = new java.math.BigDecimal(d.toString());
            // Prevent a tiny exponent literal from expanding into gigabytes during compilation.
            if (number.precision() > 1000 || Math.abs((long) number.scale()) > 1000)
                throw new QueryFailure(QUERY_LIMIT_EXCEEDED);
            return number.toPlainString();
        }
        if (e instanceof StringValue s) {
            shape(s, "value");
            return "'" + s.getValue().replace("'", "''") + "'";
        }
        if (e instanceof NullValue) return "NULL";
        if (e instanceof BooleanValue b) return b.getValue() ? "TRUE" : "FALSE";
        if (e instanceof NotExpression n) {
            shape(n, "expression");
            return "(NOT " + expr(n.getExpression(), scope, depth + 1) + ")";
        }
        if (e instanceof Between b) {
            shape(b, "leftExpression", "not", "betweenExpressionStart", "betweenExpressionEnd");
            return "("
                    + expr(b.getLeftExpression(), scope, depth + 1)
                    + (b.isNot() ? " NOT BETWEEN " : " BETWEEN ")
                    + expr(b.getBetweenExpressionStart(), scope, depth + 1)
                    + " AND "
                    + expr(b.getBetweenExpressionEnd(), scope, depth + 1)
                    + ")";
        }
        if (e instanceof InExpression in) {
            shape(in, "leftExpression", "rightExpression", "not");
            String right;
            if (in.getRightExpression() instanceof ExpressionList<?> list)
                right = "(" + expressions(list, scope, depth + 1) + ")";
            else if (in.getRightExpression() instanceof ParenthesedSelect)
                right = expr(in.getRightExpression(), scope, depth + 1);
            else throw new QueryFailure(UNAUTHORIZED_SQL);
            return "("
                    + expr(in.getLeftExpression(), scope, depth + 1)
                    + (in.isNot() ? " NOT IN " : " IN ")
                    + right
                    + ")";
        }
        if (e instanceof DateTimeLiteralExpression d) {
            shape(d, "value", "type");
            if (!Set.of("DATE", "TIMESTAMP", "TIMESTAMPTZ").contains(d.getType().name())) deny();
            String value = d.getValue();
            if (value.length() < 2
                    || value.charAt(0) != '\''
                    || value.charAt(value.length() - 1) != '\'') deny();
            return d.getType().name()
                    + " '"
                    + value.substring(1, value.length() - 1).replace("'", "''")
                    + "'";
        }
        if (e instanceof BinaryExpression b
                && OPERATORS.containsKey(e.getClass().getSimpleName())) {
            shape(e, "leftExpression", "rightExpression");
            return "("
                    + expr(b.getLeftExpression(), scope, depth + 1)
                    + " "
                    + OPERATORS.get(e.getClass().getSimpleName())
                    + " "
                    + expr(b.getRightExpression(), scope, depth + 1)
                    + ")";
        }
        if (e instanceof ParenthesedExpressionList<?> list && list.size() == 1)
            return "(" + expr(list.getFirst(), scope, depth + 1) + ")";
        if (e instanceof SignedExpression s) {
            shape(s, "sign", "expression");
            if (s.getSign() != '+' && s.getSign() != '-') deny();
            return s.getSign() + "(" + expr(s.getExpression(), scope, depth + 1) + ")";
        }
        if (e instanceof IsNullExpression n) {
            shape(n, "leftExpression", "not");
            return "("
                    + expr(n.getLeftExpression(), scope, depth + 1)
                    + (n.isNot() ? " IS NOT NULL)" : " IS NULL)");
        }
        if (e instanceof Function f) {
            shape(f, "nameparts", "parameters", "allColumns", "distinct");
            String name = f.getName().toLowerCase(Locale.ROOT);
            if (!FUNCTIONS.contains(name)) deny();
            String args;
            if (f.isAllColumns()
                    || (f.getParameters() != null
                            && f.getParameters().size() == 1
                            && f.getParameters().getFirst() instanceof AllColumns)) {
                if (!name.equals("count") || f.isDistinct()) deny();
                if (f.getParameters() != null) {
                    Expression star = f.getParameters().getFirst();
                    if (star.getClass() != AllColumns.class) deny();
                    shape(star);
                }
                args = "*";
            } else
                args =
                        f.getParameters() == null
                                ? ""
                                : expressions(f.getParameters(), scope, depth + 1);
            return "pg_catalog." + name + "(" + (f.isDistinct() ? "DISTINCT " : "") + args + ")";
        }
        if (e instanceof ParenthesedSelect nested) {
            shape(nested, "select");
            Compiled c = compile(nested.getSelect(), scope.ctes, depth + 1);
            if (c.columns().size() != 1) deny();
            return "(" + c.sql() + ")";
        }
        throw new QueryFailure(UNAUTHORIZED_SQL);
    }

    private String expressions(ExpressionList<?> list, Scope scope, int depth) {
        return list.stream().map(e -> expr(e, scope, depth + 1)).collect(Collectors.joining(", "));
    }

    private static void expand(
            String source, List<String> columns, List<String> out, List<String> names) {
        for (String column : columns) {
            out.add(q(source) + "." + q(column) + " AS " + q(column));
            names.add(column);
        }
    }

    private static String tableQualifier(Table t) {
        shape(t, "partItems", "partDelimiters");
        if (t.getNameParts().size() != 1) deny();
        return id(t.getName());
    }

    private static String alias(Alias a) {
        if (a == null) throw new QueryFailure(UNAUTHORIZED_SQL);
        shape(a, "name", "useAs");
        return id(a.getName());
    }

    private static String id(String raw) {
        if (raw == null || raw.isEmpty()) throw new QueryFailure(UNAUTHORIZED_SQL);
        if (raw.startsWith("\"") && raw.endsWith("\""))
            return raw.substring(1, raw.length() - 1).replace("\"\"", "\"");
        // Only standard identifiers; this check formats identifiers, not SQL validation.
        for (int i = 0; i < raw.length(); i++)
            if (!(Character.isLetterOrDigit(raw.charAt(i)) || raw.charAt(i) == '_')) deny();
        return raw.toLowerCase(Locale.ROOT);
    }

    private static String q(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static void deny() {
        throw new QueryFailure(UNAUTHORIZED_SQL);
    }

    /**
     * Reject every populated AST option not explicitly handled, including newly added parser
     * fields. Parser version is pinned. Parser bookkeeping is never rendered.
     */
    private static void shape(Object node, String... allowed) {
        Set<String> fields = Set.of(allowed);
        try {
            Object empty =
                    node instanceof Alias
                            ? new Alias(null)
                            : node.getClass().getDeclaredConstructor().newInstance();
            for (Class<?> type = node.getClass();
                    type != null && type.getName().startsWith("net.sf.jsqlparser");
                    type = type.getSuperclass()) {
                if (type.getSimpleName().equals("ASTNodeAccessImpl")) continue;
                for (var field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || fields.contains(field.getName()))
                        continue;
                    field.setAccessible(true);
                    if (!Objects.equals(field.get(node), field.get(empty))) deny();
                }
            }
        } catch (QueryFailure e) {
            throw e;
        } catch (ReflectiveOperationException e) {
            throw new QueryFailure(UNAUTHORIZED_SQL);
        }
    }
}
