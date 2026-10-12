package com.stockpilot.order;

import com.stockpilot.common.ApiResponse;
import com.stockpilot.common.AppException;
import com.stockpilot.common.DateRange;
import com.stockpilot.order.OrderDtos.CreateOrderRequest;
import com.stockpilot.order.OrderDtos.OrderDto;
import com.stockpilot.order.OrderService.CreateResult;
import com.stockpilot.order.OrderService.ItemInput;
import com.stockpilot.product.ProductRepository.Page;
import com.stockpilot.workspace.Ctx;
import com.stockpilot.workspace.Permission;
import com.stockpilot.workspace.WorkspaceAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/orders")
public class OrderController {
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._:\\-]{8,128}$");

    private final OrderService orders;
    private final WorkspaceAccess access;

    public OrderController(OrderService orders, WorkspaceAccess access) {
        this.orders = orders;
        this.access = access;
    }

    @GetMapping
    public ApiResponse<List<OrderDto>> list(
            Authentication auth, @PathVariable UUID workspaceId,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) @Size(max = 64) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "1") @Min(1) @Max(100_000) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int pageSize) {
        Ctx ctx = access.require(auth, workspaceId, Permission.ORDER_READ);
        DateRange r = DateRange.of(from, to);
        Page<OrderService.OrderView> result = orders.list(ctx,
                new OrderRepository.Filter(status, q, r.from(), r.toExclusive(), pageSize, (page - 1) * pageSize));
        return ApiResponse.page(result.rows().stream().map(OrderDto::from).toList(), page, pageSize, result.total());
    }

    /**
     * Creates an order (reserving stock). Optional {@code Idempotency-Key} header: a retry with the same key
     * returns the original order (200) instead of creating a second one.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<OrderDto>> create(
            Authentication auth, @PathVariable UUID workspaceId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest req) {
        Ctx ctx = access.require(auth, workspaceId, Permission.ORDER_CREATE);
        if (idempotencyKey != null && !KEY.matcher(idempotencyKey).matches()) {
            throw AppException.validation("Validation failed", List.of(Map.of("field", "Idempotency-Key",
                    "message", "Must be 8-128 characters: letters, digits, '.', '_', ':' or '-'")));
        }
        List<ItemInput> items = req.items().stream().map(i -> new ItemInput(i.productId(), i.quantity())).toList();
        String notes = req.notes() == null || req.notes().isBlank() ? null : req.notes().trim();
        CreateResult result = orders.create(ctx, items, notes, idempotencyKey);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.of(OrderDto.from(result.view())));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderDto> get(Authentication auth, @PathVariable UUID workspaceId, @PathVariable UUID orderId) {
        Ctx ctx = access.require(auth, workspaceId, Permission.ORDER_READ);
        return ApiResponse.of(OrderDto.from(orders.get(ctx, orderId)));
    }

    @PostMapping("/{orderId}/confirm")
    public ApiResponse<OrderDto> confirm(Authentication auth, @PathVariable UUID workspaceId, @PathVariable UUID orderId) {
        return transition(auth, workspaceId, orderId, OrderStatus.Action.CONFIRM);
    }

    @PostMapping("/{orderId}/complete")
    public ApiResponse<OrderDto> complete(Authentication auth, @PathVariable UUID workspaceId, @PathVariable UUID orderId) {
        return transition(auth, workspaceId, orderId, OrderStatus.Action.COMPLETE);
    }

    @PostMapping("/{orderId}/cancel")
    public ApiResponse<OrderDto> cancel(Authentication auth, @PathVariable UUID workspaceId, @PathVariable UUID orderId) {
        return transition(auth, workspaceId, orderId, OrderStatus.Action.CANCEL);
    }

    private ApiResponse<OrderDto> transition(Authentication auth, UUID workspaceId, UUID orderId, OrderStatus.Action action) {
        Ctx ctx = access.require(auth, workspaceId, Permission.ORDER_UPDATE);
        return ApiResponse.of(OrderDto.from(orders.apply(ctx, orderId, action)));
    }
}
