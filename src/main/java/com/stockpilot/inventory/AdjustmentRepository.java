package com.stockpilot.inventory;

import com.stockpilot.product.ProductRepository.Page;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AdjustmentRepository {
    public record Filter(UUID productId, Reason reason, OffsetDateTime from, OffsetDateTime toExclusive, int limit, int offset) {}

    private static final String SELECT = """
            SELECT a.id, a.product_id, p.name AS product_name, p.sku AS product_sku, a.quantity_change,
                   a.resulting_stock, a.reason_type, a.note, a.order_id, a.created_by, u.name AS created_by_name, a.created_at
            FROM inventory_adjustments a
            JOIN products p ON p.id = a.product_id AND p.workspace_id = a.workspace_id
            JOIN users u ON u.id = a.created_by""";

    private final JdbcClient jdbc;

    public AdjustmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Adjustment map(ResultSet rs, int n) throws SQLException {
        return new Adjustment(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class),
                rs.getString("product_name"), rs.getString("product_sku"), rs.getInt("quantity_change"),
                rs.getInt("resulting_stock"), rs.getString("reason_type"), rs.getString("note"),
                rs.getObject("order_id", UUID.class),
                new Adjustment.Actor(rs.getObject("created_by", UUID.class), rs.getString("created_by_name")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    public void insert(UUID workspaceId, UUID productId, int change, int resultingStock, Reason reason, String note,
                       UUID orderId, UUID userId) {
        jdbc.sql("""
                INSERT INTO inventory_adjustments
                  (workspace_id, product_id, quantity_change, resulting_stock, reason_type, note, order_id, created_by)
                VALUES (:w, :p, :c, :r, :t, :n, :o, :u)""")
                .param("w", workspaceId).param("p", productId).param("c", change).param("r", resultingStock)
                .param("t", reason.db()).param("n", note).param("o", orderId).param("u", userId).update();
    }

    public Page<Adjustment> list(UUID workspaceId, Filter f) {
        StringBuilder where = new StringBuilder("a.workspace_id = :w");
        Map<String, Object> params = new HashMap<>();
        params.put("w", workspaceId);
        if (f.productId() != null) {
            where.append(" AND a.product_id = :p");
            params.put("p", f.productId());
        }
        if (f.reason() != null) {
            where.append(" AND a.reason_type = :t");
            params.put("t", f.reason().db());
        }
        if (f.from() != null) {
            where.append(" AND a.created_at >= :from");
            params.put("from", f.from());
        }
        if (f.toExclusive() != null) {
            where.append(" AND a.created_at < :to");
            params.put("to", f.toExclusive());
        }
        long total = jdbc.sql("SELECT count(*) FROM inventory_adjustments a WHERE " + where).params(params).query(Long.class).single();
        List<Adjustment> rows = jdbc.sql(SELECT + " WHERE " + where + " ORDER BY a.created_at DESC, a.id DESC LIMIT :limit OFFSET :offset")
                .params(params).param("limit", f.limit()).param("offset", f.offset()).query(AdjustmentRepository::map).list();
        return new Page<>(rows, total);
    }

    public List<Adjustment> recent(UUID workspaceId, int limit) {
        return jdbc.sql(SELECT + " WHERE a.workspace_id = :w ORDER BY a.created_at DESC, a.id DESC LIMIT :l")
                .param("w", workspaceId).param("l", limit).query(AdjustmentRepository::map).list();
    }
}
