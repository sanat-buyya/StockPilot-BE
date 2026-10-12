package com.stockpilot.order;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;
import java.util.Set;

public enum OrderStatus {
    PENDING, CONFIRMED, COMPLETED, CANCELLED;

    @JsonValue
    public String db() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static OrderStatus fromDb(String s) {
        return valueOf(s.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * Stock strategy: RESERVE AT CREATION.
     * <ul>
     *   <li>create   - stock is deducted (a pending order holds its reservation)</li>
     *   <li>confirm  - no stock effect</li>
     *   <li>complete - no stock effect (the deduction is kept)</li>
     *   <li>cancel   - reserved stock is returned exactly once (pending or confirmed orders only)</li>
     * </ul>
     */
    public enum Action {
        CONFIRM(Set.of(PENDING), CONFIRMED),
        COMPLETE(Set.of(CONFIRMED), COMPLETED),
        CANCEL(Set.of(PENDING, CONFIRMED), CANCELLED);

        private final Set<OrderStatus> from;
        private final OrderStatus to;

        Action(Set<OrderStatus> from, OrderStatus to) {
            this.from = from;
            this.to = to;
        }

        public boolean allowedFrom(OrderStatus current) {
            return from.contains(current);
        }

        public Set<OrderStatus> from() {
            return from;
        }

        public OrderStatus to() {
            return to;
        }

        public boolean releasesStock() {
            return this == CANCEL;
        }

        public String verb() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
