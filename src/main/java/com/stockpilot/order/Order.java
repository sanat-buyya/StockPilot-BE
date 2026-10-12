package com.stockpilot.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Order(
        UUID id,
        long orderNumber,
        UUID workspaceId,
        OrderStatus status,
        BigDecimal total,
        String notes,
        UUID createdById,
        String createdByName,
        Instant stockReleasedAt,
        Instant createdAt,
        Instant updatedAt,
        Instant confirmedAt,
        Instant completedAt,
        Instant cancelledAt) {

    public String displayNumber() {
        return "ORD-%06d".formatted(orderNumber);
    }
}
