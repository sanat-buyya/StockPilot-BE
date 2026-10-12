package com.stockpilot.order;

import com.stockpilot.product.ProductRepository.Page;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {
    public record Filter(OrderStatus status, String q, OffsetDateTime from, OffsetDateTime toExclusive, int limit, int offset) {}

    private static final String SELECT = """
            SELECT o.id, o.order_number, o.workspace_id, o.status, o.total, o.notes, o.created_by, u.name AS created_by_name,
                   o.stock_released_at, o.created_at, o.updated_at, o.confirmed_at, o.completed_at, o.cancelled_at
            FROM orders o JOIN users u ON u.id = o.created_by""";
    private static final Pattern UUID_RE = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern NUMBER_RE = Pattern.compile("^(?i:ord-?)?(\\d{1,15})$");

    private final JdbcClient jdbc;

    public OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant ts(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static Order map(ResultSet rs, int n) throws SQLException {
        return new Order(rs.getObject("id", UUID.class), rs.getLong("order_number"), rs.getObject("workspace_id", UUID.class),
                OrderStatus.fromDb(rs.getString("status")), rs.getBigDecimal("total"), rs.getString("notes"),
                rs.getObject("created_by", UUID.class), rs.getString("created_by_name"), ts(rs, "stock_released_at"),
                ts(rs, "created_at"), ts(rs, "updated_at"), ts(rs, "confirmed_at"), ts(rs, "completed_at"), ts(rs, "cancelled_at"));
    }

    private static OrderItem mapItem(ResultSet rs, int n) throws SQLException {
        return new OrderItem(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
                rs.getObject("product_id", UUID.class), rs.getString("product_name"), rs.getString("product_sku"),
                rs.getBigDecimal("unit_price"), rs.getInt("quantity"), rs.getBigDecimal("line_total"));
    }

    public Optional<Order> find(UUID workspaceId, UUID id) {
        return jdbc.sql(SELECT + " WHERE o.id = :id AND o.workspace_id = :w")
                .param("id", id).param("w", workspaceId).query(OrderRepository::map).optional();
    }

    /** Row-locks the order for the rest of the transaction so concurrent transitions are serialised. */
    public Optional<Order> findForUpdate(UUID workspaceId, UUID id) {
        return jdbc.sql(SELECT + " WHERE o.id = :id AND o.workspace_id = :w FOR UPDATE OF o")
                .param("id", id).param("w", workspaceId).query(OrderRepository::map).optional();
    }

    public Optional<Order> findByIdempotencyKey(UUID workspaceId, String key) {
        return jdbc.sql(SELECT + " WHERE o.workspace_id = :w AND o.idempotency_key = :k")
                .param("w", workspaceId).param("k", key).query(OrderRepository::map).optional();
    }

    public List<OrderItem> items(Collection<UUID> orderIds) {
        if (orderIds.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT id, order_id, product_id, product_name, product_sku, unit_price, quantity, line_total
                FROM order_items WHERE order_id IN (:ids) ORDER BY product_name, id""")
                .param("ids", orderIds).query(OrderRepository::mapItem).list();
    }

    public UUID insertOrder(UUID workspaceId, UUID userId, BigDecimal total, String notes, String idempotencyKey) {
        return jdbc.sql("""
                INSERT INTO orders (workspace_id, created_by, total, notes, idempotency_key)
                VALUES (:w, :u, :t, :n, :k) RETURNING id""")
                .param("w", workspaceId).param("u", userId).param("t", total).param("n", notes).param("k", idempotencyKey)
                .query(UUID.class).single();
    }

    public void insertItem(UUID workspaceId, UUID orderId, UUID productId, String name, String sku, BigDecimal unitPrice,
                           int quantity, BigDecimal lineTotal) {
        jdbc.sql("""
                INSERT INTO order_items (order_id, workspace_id, product_id, product_name, product_sku, unit_price, quantity, line_total)
                VALUES (:o, :w, :p, :n, :s, :up, :q, :lt)""")
                .param("o", orderId).param("w", workspaceId).param("p", productId).param("n", name).param("s", sku)
                .param("up", unitPrice).param("q", quantity).param("lt", lineTotal).update();
    }

    /** Guarded status change: succeeds only if the order is still in one of the allowed source states. */
    public boolean transition(UUID workspaceId, UUID id, OrderStatus.Action action) {
        String stamp = switch (action) {
            case CONFIRM -> ", confirmed_at = now()";
            case COMPLETE -> ", completed_at = now()";
            case CANCEL -> ", cancelled_at = now(), stock_released_at = now()";
        };
        List<String> from = action.from().stream().map(OrderStatus::db).toList();
        int rows = jdbc.sql("UPDATE orders SET status = :to, updated_at = now()" + stamp
                        + " WHERE id = :id AND workspace_id = :w AND status IN (:from)")
                .param("to", action.to().db()).param("id", id).param("w", workspaceId).param("from", from).update();
        return rows == 1;
    }

    public Page<Order> list(UUID workspaceId, Filter f) {
        StringBuilder where = new StringBuilder("o.workspace_id = :w");
        Map<String, Object> params = new HashMap<>();
        params.put("w", workspaceId);
        if (f.status() != null) {
            where.append(" AND o.status = :status");
            params.put("status", f.status().db());
        }
        if (f.from() != null) {
            where.append(" AND o.created_at >= :from");
            params.put("from", f.from());
        }
        if (f.toExclusive() != null) {
            where.append(" AND o.created_at < :to");
            params.put("to", f.toExclusive());
        }
        if (f.q() != null && !f.q().isBlank()) {
            String q = f.q().trim();
            var number = NUMBER_RE.matcher(q);
            if (UUID_RE.matcher(q).matches()) {
                where.append(" AND o.id = :qid");
                params.put("qid", UUID.fromString(q));
            } else if (number.matches()) {
                where.append(" AND o.order_number = :qnum");
                params.put("qnum", Long.parseLong(number.group(1)));
            } else {
                where.append(" AND FALSE"); // not an order identifier
            }
        }
        long total = jdbc.sql("SELECT count(*) FROM orders o WHERE " + where).params(params).query(Long.class).single();
        List<Order> rows = jdbc.sql(SELECT + " WHERE " + where + " ORDER BY o.created_at DESC, o.order_number DESC LIMIT :limit OFFSET :offset")
                .params(params).param("limit", f.limit()).param("offset", f.offset()).query(OrderRepository::map).list();
        return new Page<>(rows, total);
    }

    public List<Order> recent(UUID workspaceId, int limit) {
        return jdbc.sql(SELECT + " WHERE o.workspace_id = :w ORDER BY o.created_at DESC, o.order_number DESC LIMIT :l")
                .param("w", workspaceId).param("l", limit).query(OrderRepository::map).list();
    }
}
