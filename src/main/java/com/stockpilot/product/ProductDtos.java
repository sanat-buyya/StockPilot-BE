package com.stockpilot.product;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class ProductDtos {
    private ProductDtos() {}

    public static final String SKU_PATTERN = "^[A-Za-z0-9][A-Za-z0-9._\\-/]*$";

    public record CreateProductRequest(
            @NotBlank(message = "Name is required") @Size(max = 200, message = "Name must be at most 200 characters") String name,
            @NotBlank(message = "SKU is required") @Size(max = 64, message = "SKU must be at most 64 characters")
            @Pattern(regexp = SKU_PATTERN, message = "SKU may contain letters, digits, '.', '_', '-' and '/'") String sku,
            @Size(max = 2000, message = "Description must be at most 2000 characters") String description,
            @NotNull(message = "Unit price is required")
            @DecimalMin(value = "0", message = "Unit price cannot be negative")
            @DecimalMax(value = "1000000.00", message = "Unit price is too large")
            @Digits(integer = 7, fraction = 2, message = "Unit price may have at most 2 decimal places") BigDecimal unitPrice,
            @Min(value = 0, message = "Stock quantity cannot be negative") @Max(value = 1_000_000, message = "Stock quantity is too large") Integer stockQuantity,
            @Min(value = 0, message = "Low-stock threshold cannot be negative") @Max(value = 1_000_000, message = "Low-stock threshold is too large") Integer lowStockThreshold,
            Boolean active) {}

    /** All fields optional (PATCH). Stock is not editable here; use inventory adjustments. */
    public record UpdateProductRequest(
            @Size(min = 1, max = 200, message = "Name must be 1 to 200 characters") String name,
            @Size(min = 1, max = 64, message = "SKU must be 1 to 64 characters")
            @Pattern(regexp = SKU_PATTERN, message = "SKU may contain letters, digits, '.', '_', '-' and '/'") String sku,
            @Size(max = 2000, message = "Description must be at most 2000 characters") String description,
            @DecimalMin(value = "0", message = "Unit price cannot be negative")
            @DecimalMax(value = "1000000.00", message = "Unit price is too large")
            @Digits(integer = 7, fraction = 2, message = "Unit price may have at most 2 decimal places") BigDecimal unitPrice,
            @Min(value = 0, message = "Low-stock threshold cannot be negative") @Max(value = 1_000_000, message = "Low-stock threshold is too large") Integer lowStockThreshold,
            Boolean active) {}

    public record ProductDto(UUID id, String name, String sku, String description, String unitPrice, int stockQuantity,
                             int lowStockThreshold, boolean lowStock, boolean active, Instant createdAt, Instant updatedAt) {
        public static ProductDto from(Product p) {
            return new ProductDto(p.id(), p.name(), p.sku(), p.description(), p.unitPrice().toPlainString(),
                    p.stockQuantity(), p.lowStockThreshold(), p.lowStock(), p.active(), p.createdAt(), p.updatedAt());
        }
    }
}
