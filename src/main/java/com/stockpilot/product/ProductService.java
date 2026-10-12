package com.stockpilot.product;

import com.stockpilot.common.AppException;
import com.stockpilot.inventory.AdjustmentRepository;
import com.stockpilot.inventory.Reason;
import com.stockpilot.product.ProductDtos.CreateProductRequest;
import com.stockpilot.product.ProductDtos.UpdateProductRequest;
import com.stockpilot.product.ProductRepository.Filter;
import com.stockpilot.product.ProductRepository.Page;
import com.stockpilot.workspace.Ctx;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {
    private final ProductRepository products;
    private final AdjustmentRepository adjustments;

    public ProductService(ProductRepository products, AdjustmentRepository adjustments) {
        this.products = products;
        this.adjustments = adjustments;
    }

    public Page<Product> list(Ctx ctx, Filter filter) {
        return products.list(ctx.workspaceId(), filter);
    }

    public Product get(Ctx ctx, UUID id) {
        return products.find(ctx.workspaceId(), id).orElseThrow(() -> AppException.notFound("Product"));
    }

    /**
     * Creates the product; any opening stock is recorded as an "initial_stock" adjustment in the same
     * transaction so current stock always has a matching audit trail. A duplicate SKU in the same workspace
     * trips the unique constraint (translated to DUPLICATE_SKU by the exception handler) and rolls everything back.
     */
    @Transactional
    public Product create(Ctx ctx, CreateProductRequest r) {
        String name = r.name().trim();
        String sku = normalizeSku(r.sku());
        if (name.isEmpty()) throw fieldError("name", "Name is required");
        int stock = r.stockQuantity() == null ? 0 : r.stockQuantity();
        int threshold = r.lowStockThreshold() == null ? 0 : r.lowStockThreshold();
        String description = blankToNull(r.description());
        Product p = products.insert(ctx.workspaceId(), name, sku, description, r.unitPrice(), stock, threshold,
                r.active() == null || r.active());
        if (stock > 0) {
            adjustments.insert(ctx.workspaceId(), p.id(), stock, stock, Reason.INITIAL_STOCK, "Opening stock", null, ctx.userId());
        }
        return p;
    }

    @Transactional
    public Product update(Ctx ctx, UUID id, UpdateProductRequest r) {
        Map<String, Object> cols = new LinkedHashMap<>();
        if (r.name() != null) {
            if (r.name().isBlank()) throw fieldError("name", "Name is required");
            cols.put("name", r.name().trim());
        }
        if (r.sku() != null) cols.put("sku", normalizeSku(r.sku()));
        if (r.description() != null) cols.put("description", blankToNull(r.description()));
        if (r.unitPrice() != null) cols.put("unit_price", r.unitPrice());
        if (r.lowStockThreshold() != null) cols.put("low_stock_threshold", r.lowStockThreshold());
        if (r.active() != null) cols.put("is_active", r.active());
        if (cols.isEmpty()) throw fieldError("(body)", "Provide at least one field to update");
        return products.update(ctx.workspaceId(), id, cols).orElseThrow(() -> AppException.notFound("Product"));
    }

    private static String normalizeSku(String sku) {
        String s = sku.trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty()) throw fieldError("sku", "SKU is required");
        return s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static AppException fieldError(String field, String message) {
        return AppException.validation("Validation failed", List.of(Map.of("field", field, "message", message)));
    }
}
