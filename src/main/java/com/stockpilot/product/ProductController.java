package com.stockpilot.product;

import com.stockpilot.common.ApiResponse;
import com.stockpilot.common.DateRange;
import com.stockpilot.inventory.Adjustment;
import com.stockpilot.inventory.AdjustmentRepository;
import com.stockpilot.inventory.InventoryService;
import com.stockpilot.inventory.Reason;
import com.stockpilot.product.ProductDtos.CreateProductRequest;
import com.stockpilot.product.ProductDtos.ProductDto;
import com.stockpilot.product.ProductDtos.UpdateProductRequest;
import com.stockpilot.product.ProductRepository.Filter;
import com.stockpilot.product.ProductRepository.Page;
import com.stockpilot.product.ProductRepository.Sort;
import com.stockpilot.workspace.Ctx;
import com.stockpilot.workspace.Permission;
import com.stockpilot.workspace.WorkspaceAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/products")
public class ProductController {
    public record AdjustRequest(
            @NotNull(message = "Quantity change must be a whole number") Integer quantityChange,
            @NotNull(message = "Reason is required") Reason reasonType,
            @Size(max = 500, message = "Note must be at most 500 characters") String note) {}

    private final ProductService products;
    private final InventoryService inventory;
    private final WorkspaceAccess access;

    public ProductController(ProductService products, InventoryService inventory, WorkspaceAccess access) {
        this.products = products;
        this.inventory = inventory;
        this.access = access;
    }

    @GetMapping
    public ApiResponse<java.util.List<ProductDto>> list(
            Authentication auth, @PathVariable UUID workspaceId,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Boolean lowStock,
            @RequestParam(defaultValue = "name") String sort,
            @RequestParam(defaultValue = "asc") String dir,
            @RequestParam(defaultValue = "1") @Min(1) @Max(100_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        Ctx ctx = access.require(auth, workspaceId, Permission.PRODUCT_READ);
        Sort s;
        try {
            s = Sort.valueOf(sort.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw com.stockpilot.common.AppException.validation("Validation failed",
                    java.util.List.of(java.util.Map.of("field", "sort", "message", "sort must be one of: name, sku, price, stock, created")));
        }
        Page<Product> result = products.list(ctx, new Filter(q, active, lowStock, s, "desc".equalsIgnoreCase(dir), pageSize, (page - 1) * pageSize));
        return ApiResponse.page(result.rows().stream().map(ProductDto::from).toList(), page, pageSize, result.total());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProductDto> create(Authentication auth, @PathVariable UUID workspaceId,
                                          @Valid @RequestBody CreateProductRequest req) {
        Ctx ctx = access.require(auth, workspaceId, Permission.PRODUCT_WRITE);
        return ApiResponse.of(ProductDto.from(products.create(ctx, req)));
    }

    @GetMapping("/{productId}")
    public ApiResponse<ProductDto> get(Authentication auth, @PathVariable UUID workspaceId, @PathVariable UUID productId) {
        Ctx ctx = access.require(auth, workspaceId, Permission.PRODUCT_READ);
        return ApiResponse.of(ProductDto.from(products.get(ctx, productId)));
    }

    @PatchMapping("/{productId}")
    public ApiResponse<ProductDto> update(Authentication auth, @PathVariable UUID workspaceId, @PathVariable UUID productId,
                                          @Valid @RequestBody UpdateProductRequest req) {
        Ctx ctx = access.require(auth, workspaceId, Permission.PRODUCT_WRITE);
        return ApiResponse.of(ProductDto.from(products.update(ctx, productId, req)));
    }

    @PostMapping("/{productId}/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProductDto> adjust(Authentication auth, @PathVariable UUID workspaceId, @PathVariable UUID productId,
                                          @Valid @RequestBody AdjustRequest req) {
        Ctx ctx = access.require(auth, workspaceId, Permission.INVENTORY_ADJUST);
        return ApiResponse.of(ProductDto.from(inventory.adjust(ctx, productId, req.quantityChange(), req.reasonType(), blankToNull(req.note()))));
    }

    @GetMapping("/{productId}/adjustments")
    public ApiResponse<java.util.List<Adjustment>> productHistory(
            Authentication auth, @PathVariable UUID workspaceId, @PathVariable UUID productId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "1") @Min(1) @Max(100_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        Ctx ctx = access.require(auth, workspaceId, Permission.INVENTORY_READ);
        products.get(ctx, productId); // 404 if the product is not in this workspace
        DateRange r = DateRange.of(from, to);
        Page<Adjustment> result = inventory.history(ctx,
                new AdjustmentRepository.Filter(productId, null, r.from(), r.toExclusive(), pageSize, (page - 1) * pageSize));
        return ApiResponse.page(result.rows(), page, pageSize, result.total());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
