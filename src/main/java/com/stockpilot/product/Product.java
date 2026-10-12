package com.stockpilot.product;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Row view of a product. Money is BigDecimal (numeric(12,2)); never a float. */
public record Product(
        UUID id,
        UUID workspaceId,
        String name,
        String sku,
        String description,
        BigDecimal unitPrice,
        int stockQuantity,
        int lowStockThreshold,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {

    public boolean lowStock() {
        return stockQuantity <= lowStockThreshold;
    }
}
