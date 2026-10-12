package com.stockpilot.order;

import java.math.BigDecimal;
import java.util.UUID;

/** Line item with immutable snapshots of the product's name, SKU and unit price at order time. */
public record OrderItem(
        UUID id,
        UUID orderId,
        UUID productId,
        String productName,
        String productSku,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal lineTotal) {}
