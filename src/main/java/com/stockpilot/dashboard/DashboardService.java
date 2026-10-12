package com.stockpilot.dashboard;

import com.stockpilot.inventory.Adjustment;
import com.stockpilot.inventory.AdjustmentRepository;
import com.stockpilot.order.Order;
import com.stockpilot.order.OrderDtos.CreatorDto;
import com.stockpilot.order.OrderRepository;
import com.stockpilot.order.OrderStatus;
import com.stockpilot.workspace.Ctx;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Metric definitions (identical for UI and API):
 * <ul>
 *   <li>activeProducts: products with is_active = true</li>
 *   <li>lowStockProducts: ACTIVE products with stock_quantity &lt;= low_stock_threshold</li>
 *   <li>ordersByStatus: orders CREATED in [from, to] (UTC days, inclusive), grouped by their current status</li>
 *   <li>orderValue: sum of totals of orders created in the range whose status is NOT cancelled
 *       (pending + confirmed + completed); valueByStatus gives the per-status split</li>
 *   <li>recentOrders / recentAdjustments: latest 5 in the workspace, regardless of the range</li>
 * </ul>
 */
@Service
public class DashboardService {
    public record RecentOrder(UUID id, String orderNumber, OrderStatus status, String total, CreatorDto createdBy, Instant createdAt) {}

    public record Dashboard(String from, String to, long activeProducts, long lowStockProducts,
                            Map<String, Long> ordersByStatus, String orderValue, Map<String, String> valueByStatus,
                            List<RecentOrder> recentOrders, List<Adjustment> recentAdjustments) {}

    private record StatusAgg(String status, long count, BigDecimal value) {}

    private final JdbcClient jdbc;
    private final OrderRepository orders;
    private final AdjustmentRepository adjustments;

    public DashboardService(JdbcClient jdbc, OrderRepository orders, AdjustmentRepository adjustments) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.adjustments = adjustments;
    }

    public Dashboard get(Ctx ctx, LocalDate fromIn, LocalDate toIn) {
        LocalDate to = toIn != null ? toIn : LocalDate.now(ZoneOffset.UTC);
        LocalDate from = fromIn != null ? fromIn : to.minusDays(29);
        if (from.isAfter(to)) {
            throw com.stockpilot.common.AppException.validation("Validation failed",
                    List.of(Map.of("field", "to", "message", "'to' must not be before 'from'")));
        }
        OffsetDateTime start = from.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime endExclusive = to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        long active = jdbc.sql("SELECT count(*) FROM products WHERE workspace_id = :w AND is_active")
                .param("w", ctx.workspaceId()).query(Long.class).single();
        long low = jdbc.sql("SELECT count(*) FROM products WHERE workspace_id = :w AND is_active AND stock_quantity <= low_stock_threshold")
                .param("w", ctx.workspaceId()).query(Long.class).single();
        List<StatusAgg> aggs = jdbc.sql("""
                SELECT status, count(*) AS n, COALESCE(sum(total), 0) AS value FROM orders
                WHERE workspace_id = :w AND created_at >= :from AND created_at < :to GROUP BY status""")
                .param("w", ctx.workspaceId()).param("from", start).param("to", endExclusive)
                .query((rs, n) -> new StatusAgg(rs.getString("status"), rs.getLong("n"), rs.getBigDecimal("value"))).list();

        Map<OrderStatus, Long> counts = new EnumMap<>(OrderStatus.class);
        Map<OrderStatus, BigDecimal> values = new EnumMap<>(OrderStatus.class);
        for (OrderStatus s : OrderStatus.values()) {
            counts.put(s, 0L);
            values.put(s, BigDecimal.ZERO.setScale(2));
        }
        for (StatusAgg a : aggs) {
            OrderStatus s = OrderStatus.fromDb(a.status());
            counts.put(s, a.count());
            values.put(s, a.value().setScale(2));
        }
        BigDecimal orderValue = values.entrySet().stream().filter(e -> e.getKey() != OrderStatus.CANCELLED)
                .map(Map.Entry::getValue).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);

        Map<String, Long> countsOut = new LinkedHashMap<>();
        Map<String, String> valuesOut = new LinkedHashMap<>();
        counts.forEach((k, v) -> countsOut.put(k.db(), v));
        values.forEach((k, v) -> valuesOut.put(k.db(), v.toPlainString()));

        List<RecentOrder> recent = orders.recent(ctx.workspaceId(), 5).stream().map(DashboardService::recent).toList();
        return new Dashboard(from.toString(), to.toString(), active, low, countsOut, orderValue.toPlainString(), valuesOut,
                recent, adjustments.recent(ctx.workspaceId(), 5));
    }

    private static RecentOrder recent(Order o) {
        return new RecentOrder(o.id(), o.displayNumber(), o.status(), o.total().toPlainString(),
                new CreatorDto(o.createdById(), o.createdByName()), o.createdAt());
    }
}
