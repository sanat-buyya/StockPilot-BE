package com.stockpilot.order;

import com.stockpilot.order.OrderService.OrderView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class OrderDtos {
    private OrderDtos() {}

    /** Unknown properties (for example a client-sent "total" or "unitPrice") are ignored, never trusted. */
    public record CreateOrderRequest(
            @NotEmpty(message = "An order needs at least one item")
            @Size(max = 50, message = "An order can have at most 50 items") List<@Valid ItemRequest> items,
            @Size(max = 1000, message = "Notes must be at most 1000 characters") String notes) {}

    public record ItemRequest(
            @NotNull(message = "Product is required") UUID productId,
            @NotNull(message = "Quantity must be a whole number")
            @Min(value = 1, message = "Quantity must be at least 1")
            @Max(value = 100_000, message = "Quantity is too large") Integer quantity) {}

    public record ItemDto(UUID id, UUID productId, String productName, String productSku, String unitPrice, int quantity, String lineTotal) {}

    public record CreatorDto(UUID id, String name) {}

    public record OrderDto(UUID id, String orderNumber, OrderStatus status, String total, String notes, CreatorDto createdBy,
                           int itemCount, int unitCount, Instant stockReleasedAt, Instant createdAt, Instant updatedAt,
                           Instant confirmedAt, Instant completedAt, Instant cancelledAt, List<ItemDto> items) {
        public static OrderDto from(OrderView v) {
            Order o = v.order();
            List<ItemDto> items = v.items().stream().map(i -> new ItemDto(i.id(), i.productId(), i.productName(), i.productSku(),
                    i.unitPrice().toPlainString(), i.quantity(), i.lineTotal().toPlainString())).toList();
            return new OrderDto(o.id(), o.displayNumber(), o.status(), o.total().toPlainString(), o.notes(),
                    new CreatorDto(o.createdById(), o.createdByName()), items.size(),
                    v.items().stream().mapToInt(OrderItem::quantity).sum(), o.stockReleasedAt(), o.createdAt(), o.updatedAt(),
                    o.confirmedAt(), o.completedAt(), o.cancelledAt(), items);
        }
    }
}
