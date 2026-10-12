package com.stockpilot.product;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProductRepository {
    public enum Sort {
        NAME("name"), SKU("sku"), PRICE("unit_price"), STOCK("stock_quantity"), CREATED("created_at");
        final String column;

        Sort(String column) {
            this.column = column;
        }
    }

    public record Filter(String q, Boolean active, Boolean lowStock, Sort sort, boolean desc, int limit, int offset) {}

    private static final String COLS = "id, workspace_id, name, sku, description, unit_price, stock_quantity, "
            + "low_stock_threshold, is_active, created_at, updated_at";

    private final JdbcClient jdbc;

    public ProductRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    static Product map(ResultSet rs, int n) throws SQLException {
        return new Product(
                rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class),
                rs.getString("name"), rs.getString("sku"), rs.getString("description"),
                rs.getBigDecimal("unit_price"), rs.getInt("stock_quantity"), rs.getInt("low_stock_threshold"),
                rs.getBoolean("is_active"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    public record Page<T>(List<T> rows, long total) {}

    public Page<Product> list(UUID workspaceId, Filter f) {
        StringBuilder where = new StringBuilder("workspace_id = :w");
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("w", workspaceId);
        if (f.q() != null && !f.q().isBlank()) {
            where.append(" AND (name ILIKE :q ESCAPE '\\' OR sku ILIKE :q ESCAPE '\\' OR description ILIKE :q ESCAPE '\\')");
            params.put("q", "%" + f.q().trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (f.active() != null) {
            where.append(" AND is_active = :active");
            params.put("active", f.active());
        }
        if (f.lowStock() != null) {
            where.append(f.lowStock() ? " AND stock_quantity <= low_stock_threshold" : " AND stock_quantity > low_stock_threshold");
        }
        long total = jdbc.sql("SELECT count(*) FROM products WHERE " + where).params(params).query(Long.class).single();
        String sql = "SELECT " + COLS + " FROM products WHERE " + where + " ORDER BY " + f.sort().column
                + (f.desc() ? " DESC" : " ASC") + ", id LIMIT :limit OFFSET :offset";
        List<Product> rows = jdbc.sql(sql).params(params).param("limit", f.limit()).param("offset", f.offset())
                .query(ProductRepository::map).list();
        return new Page<>(rows, total);
    }

    public Optional<Product> find(UUID workspaceId, UUID id) {
        return jdbc.sql("SELECT " + COLS + " FROM products WHERE id = :id AND workspace_id = :w")
                .param("id", id).param("w", workspaceId).query(ProductRepository::map).optional();
    }

    public Product insert(UUID workspaceId, String name, String sku, String description, BigDecimal price,
                          int stock, int threshold, boolean active) {
        return jdbc.sql("INSERT INTO products (workspace_id, name, sku, description, unit_price, stock_quantity, "
                        + "low_stock_threshold, is_active) VALUES (:w,:n,:s,:d,:p,:st,:t,:a) RETURNING " + COLS)
                .param("w", workspaceId).param("n", name).param("s", sku).param("d", description).param("p", price)
                .param("st", stock).param("t", threshold).param("a", active)
                .query(ProductRepository::map).single();
    }

    /** Applies only the supplied columns. Stock is deliberately not updatable here. */
    public Optional<Product> update(UUID workspaceId, UUID id, Map<String, Object> columns) {
        List<String> sets = new ArrayList<>();
        columns.keySet().forEach(c -> sets.add(c + " = :" + c));
        sets.add("updated_at = now()");
        return jdbc.sql("UPDATE products SET " + String.join(", ", sets)
                        + " WHERE id = :id AND workspace_id = :w RETURNING " + COLS)
                .params(columns).param("id", id).param("w", workspaceId)
                .query(ProductRepository::map).optional();
    }

    /**
     * Locks the products (scoped to the workspace) until the transaction ends. Rows are locked in id order so
     * two concurrent multi-item orders can never deadlock each other.
     */
    public List<Product> lockAll(UUID workspaceId, Collection<UUID> ids) {
        return jdbc.sql("SELECT " + COLS + " FROM products WHERE workspace_id = :w AND id IN (:ids) ORDER BY id FOR UPDATE")
                .param("w", workspaceId).param("ids", ids).query(ProductRepository::map).list();
    }

    /**
     * Atomic conditional stock change: the WHERE clause refuses any change that would take stock below zero,
     * so correctness never depends on a prior read. Returns the new stock, or empty if refused / not found.
     */
    public Optional<Integer> applyStockDelta(UUID workspaceId, UUID productId, int delta) {
        return jdbc.sql("""
                UPDATE products SET stock_quantity = stock_quantity + :d, updated_at = now()
                WHERE id = :id AND workspace_id = :w AND stock_quantity + :d >= 0
                RETURNING stock_quantity""")
                .param("d", delta).param("id", productId).param("w", workspaceId)
                .query(Integer.class).optional();
    }
}
