package com.stockpilot.order;

import com.stockpilot.common.AppException;
import com.stockpilot.common.DbErrors;
import com.stockpilot.common.ErrorCode;
import com.stockpilot.inventory.AdjustmentRepository;
import com.stockpilot.inventory.Reason;
import com.stockpilot.product.Product;
import com.stockpilot.product.ProductRepository;
import com.stockpilot.product.ProductRepository.Page;
import com.stockpilot.workspace.Ctx;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Order business rules. Stock strategy: RESERVE AT CREATION (see {@link OrderStatus.Action}).
 *
 * <p>Transaction boundaries: every public mutating method runs inside ONE database transaction
 * (via {@link TransactionTemplate}); any exception rolls the whole thing back, so an order and its stock
 * effects are written together or not at all.
 *
 * <p>Concurrency: product rows are locked with {@code SELECT ... FOR UPDATE} in id order (no deadlocks) and the
 * stock decrement is a conditional UPDATE guarded by {@code stock_quantity + delta >= 0}; a CHECK constraint is the
 * final backstop. Order transitions lock the order row first, so two cancels of one order are serialised and the
 * second sees the order already cancelled.
 */
@Service
public class OrderService {
    public record OrderView(Order order, List<OrderItem> items) {}

    public record CreateResult(OrderView view, boolean created) {}

    public record ItemInput(UUID productId, int quantity) {}

    static final int MAX_LINE_QUANTITY = 100_000;

    private final OrderRepository orders;
    private final ProductRepository products;
    private final AdjustmentRepository adjustments;
    private final TransactionTemplate tx;

    public OrderService(OrderRepository orders, ProductRepository products, AdjustmentRepository adjustments, TransactionTemplate tx) {
        this.orders = orders;
        this.products = products;
        this.adjustments = adjustments;
        this.tx = tx;
    }

    // ------------------------------------------------------------------ create

    public CreateResult create(Ctx ctx, List<ItemInput> items, String notes, String idempotencyKey) {
        try {
            return tx.execute(status -> doCreate(ctx, items, notes, idempotencyKey));
        } catch (RuntimeException e) {
            // Two requests with the same Idempotency-Key raced past the checks: the loser hits the unique index.
            // Its transaction is already rolled back, so replay the winner's order.
            if (idempotencyKey != null && "orders_idempotency_uq".equals(DbErrors.uniqueViolation(e))) {
                return orders.findByIdempotencyKey(ctx.workspaceId(), idempotencyKey)
                        .map(o -> new CreateResult(view(o), false)).orElseThrow(() -> e);
            }
            throw e;
        }
    }

    private CreateResult doCreate(Ctx ctx, List<ItemInput> input, String notes, String idempotencyKey) {
        // 1. Combine duplicate lines for the same product (sum quantities) before anything touches stock.
        Map<UUID, Integer> wanted = new LinkedHashMap<>();
        for (ItemInput i : input) {
            wanted.merge(i.productId(), i.quantity(), Integer::sum);
        }
        for (Map.Entry<UUID, Integer> e : wanted.entrySet()) {
            if (e.getValue() > MAX_LINE_QUANTITY) {
                throw AppException.validation("Validation failed",
                        List.of(Map.of("field", "items", "message", "Combined quantity for a product cannot exceed " + MAX_LINE_QUANTITY)));
            }
        }

        // 2. Retry of an already-processed request: return the original order, change nothing.
        if (idempotencyKey != null) {
            var existing = orders.findByIdempotencyKey(ctx.workspaceId(), idempotencyKey);
            if (existing.isPresent()) return new CreateResult(view(existing.get()), false);
        }

        // 3. Lock the products (workspace-scoped, id order) so price and stock cannot change underneath us.
        Map<UUID, Product> locked = new LinkedHashMap<>();
        products.lockAll(ctx.workspaceId(), wanted.keySet()).forEach(p -> locked.put(p.id(), p));

        // A same-key request that was waiting on these locks may now find the first one committed.
        if (idempotencyKey != null) {
            var existing = orders.findByIdempotencyKey(ctx.workspaceId(), idempotencyKey);
            if (existing.isPresent()) return new CreateResult(view(existing.get()), false);
        }

        // 4. Validate EVERY line and report all problems together.
        List<Map<String, Object>> missing = new ArrayList<>();
        List<Map<String, Object>> inactive = new ArrayList<>();
        List<Map<String, Object>> short_ = new ArrayList<>();
        for (Map.Entry<UUID, Integer> e : wanted.entrySet()) {
            Product p = locked.get(e.getKey());
            if (p == null) {
                missing.add(Map.of("productId", e.getKey(), "message", "Product not found in this workspace"));
            } else if (!p.active()) {
                inactive.add(Map.of("productId", p.id(), "sku", p.sku(), "message", p.name() + " is inactive and cannot be ordered"));
            } else if (p.stockQuantity() < e.getValue()) {
                short_.add(Map.of("productId", p.id(), "sku", p.sku(), "requested", e.getValue(), "available", p.stockQuantity(),
                        "message", "Insufficient stock for " + p.name() + " (requested " + e.getValue() + ", available " + p.stockQuantity() + ")"));
            }
        }
        if (!missing.isEmpty()) throw AppException.validation("One or more products do not exist in this workspace", missing);
        if (!short_.isEmpty()) {
            short_.addAll(inactive);
            throw new AppException(ErrorCode.INSUFFICIENT_STOCK, "Insufficient stock for one or more items", short_);
        }
        if (!inactive.isEmpty()) throw new AppException(ErrorCode.INACTIVE_PRODUCT, "One or more products are inactive", inactive);

        // 5. Server-side pricing from the locked rows. Nothing price-related is read from the client.
        BigDecimal total = BigDecimal.ZERO;
        Map<UUID, BigDecimal> lineTotals = new LinkedHashMap<>();
        for (Map.Entry<UUID, Integer> e : wanted.entrySet()) {
            BigDecimal line = locked.get(e.getKey()).unitPrice().multiply(BigDecimal.valueOf(e.getValue()));
            lineTotals.put(e.getKey(), line);
            total = total.add(line);
        }

        // 6. Persist the order, its snapshots, the stock reservation and the audit rows (all or nothing).
        UUID orderId = orders.insertOrder(ctx.workspaceId(), ctx.userId(), total, notes, idempotencyKey);
        for (Map.Entry<UUID, Integer> e : wanted.entrySet()) {
            Product p = locked.get(e.getKey());
            int qty = e.getValue();
            orders.insertItem(ctx.workspaceId(), orderId, p.id(), p.name(), p.sku(), p.unitPrice(), qty, lineTotals.get(p.id()));
            int remaining = products.applyStockDelta(ctx.workspaceId(), p.id(), -qty).orElseThrow(() ->
                    new AppException(ErrorCode.INSUFFICIENT_STOCK, "Insufficient stock for " + p.name()));
            adjustments.insert(ctx.workspaceId(), p.id(), -qty, remaining, Reason.ORDER_RESERVE, null, orderId, ctx.userId());
        }
        return new CreateResult(view(orders.find(ctx.workspaceId(), orderId).orElseThrow()), true);
    }

    // -------------------------------------------------------------- transitions

    public OrderView apply(Ctx ctx, UUID orderId, OrderStatus.Action action) {
        return tx.execute(status -> {
            // Row lock: a concurrent confirm/complete/cancel of the same order waits here, then re-reads fresh state.
            Order order = orders.findForUpdate(ctx.workspaceId(), orderId).orElseThrow(() -> AppException.notFound("Order"));
            if (!action.allowedFrom(order.status())) {
                throw new AppException(ErrorCode.INVALID_TRANSITION,
                        "Cannot " + action.verb() + " an order that is " + order.status().db(),
                        Map.of("currentStatus", order.status().db(), "action", action.verb()));
            }
            if (action.releasesStock()) {
                releaseStock(ctx, order);
            }
            if (!orders.transition(ctx.workspaceId(), orderId, action)) {
                // Cannot happen while we hold the row lock; fail loudly (and roll back) rather than skip.
                throw new AppException(ErrorCode.CONFLICT, "Order was modified concurrently. Please retry.");
            }
            return view(orders.find(ctx.workspaceId(), orderId).orElseThrow());
        });
    }

    /** Returns every reserved line to stock and records it. Runs at most once per order (status guard + unique index). */
    private void releaseStock(Ctx ctx, Order order) {
        List<OrderItem> items = orders.items(List.of(order.id()));
        products.lockAll(ctx.workspaceId(), items.stream().map(OrderItem::productId).toList());
        for (OrderItem item : items) {
            int resulting = products.applyStockDelta(ctx.workspaceId(), item.productId(), item.quantity())
                    .orElseThrow(() -> AppException.notFound("Product"));
            adjustments.insert(ctx.workspaceId(), item.productId(), item.quantity(), resulting, Reason.ORDER_RELEASE, null, order.id(), ctx.userId());
        }
    }

    // -------------------------------------------------------------------- reads

    public OrderView get(Ctx ctx, UUID orderId) {
        return view(orders.find(ctx.workspaceId(), orderId).orElseThrow(() -> AppException.notFound("Order")));
    }

    public Page<OrderView> list(Ctx ctx, OrderRepository.Filter filter) {
        Page<Order> page = orders.list(ctx.workspaceId(), filter);
        Map<UUID, List<OrderItem>> byOrder = new LinkedHashMap<>();
        orders.items(page.rows().stream().map(Order::id).toList()).forEach(i -> byOrder.computeIfAbsent(i.orderId(), k -> new ArrayList<>()).add(i));
        return new Page<>(page.rows().stream().map(o -> new OrderView(o, byOrder.getOrDefault(o.id(), List.of()))).toList(), page.total());
    }

    private OrderView view(Order o) {
        return new OrderView(o, orders.items(List.of(o.id())));
    }
}
