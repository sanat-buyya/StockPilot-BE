package com.stockpilot.inventory;

import java.time.Instant;
import java.util.UUID;

/** One immutable audit row: how and why a product's stock changed, by whom, and the resulting level. */
public record Adjustment(
        UUID id,
        UUID productId,
        String productName,
        String productSku,
        int quantityChange,
        int resultingStock,
        String reasonType,
        String note,
        UUID orderId,
        Actor createdBy,
        Instant createdAt) {

    public record Actor(UUID id, String name) {}
}
