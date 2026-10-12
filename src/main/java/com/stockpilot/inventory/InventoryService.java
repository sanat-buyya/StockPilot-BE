package com.stockpilot.inventory;

import com.stockpilot.common.AppException;
import com.stockpilot.common.ErrorCode;
import com.stockpilot.product.Product;
import com.stockpilot.product.ProductRepository;
import com.stockpilot.product.ProductRepository.Page;
import com.stockpilot.workspace.Ctx;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryService {
    private final ProductRepository products;
    private final AdjustmentRepository adjustments;

    public InventoryService(ProductRepository products, AdjustmentRepository adjustments) {
        this.products = products;
        this.adjustments = adjustments;
    }

    /**
     * Changes stock and writes its audit row in ONE transaction: either both happen or neither does.
     * The stock UPDATE itself refuses to go below zero, so concurrent adjustments cannot race past the check.
     */
    @Transactional
    public Product adjust(Ctx ctx, UUID productId, int change, Reason reason, String note) {
        validate(change, reason, note);
        if (products.find(ctx.workspaceId(), productId).isEmpty()) {
            throw AppException.notFound("Product");
        }
        int resulting = products.applyStockDelta(ctx.workspaceId(), productId, change).orElseThrow(() -> {
            int current = products.find(ctx.workspaceId(), productId).map(Product::stockQuantity).orElse(0);
            return new AppException(ErrorCode.INSUFFICIENT_STOCK,
                    "Adjustment of " + change + " would make stock negative (current stock: " + current + ")",
                    Map.of("currentStock", current, "requestedChange", change));
        });
        adjustments.insert(ctx.workspaceId(), productId, change, resulting, reason, note, null, ctx.userId());
        return products.find(ctx.workspaceId(), productId).orElseThrow();
    }

    private static void validate(int change, Reason reason, String note) {
        if (!reason.manual()) {
            throw AppException.validation("Validation failed",
                    List.of(Map.of("field", "reasonType", "message", "Reason must be one of: received, count_correction, damaged, returned, other")));
        }
        String msg = null;
        String field = "quantityChange";
        if (change == 0) msg = "Quantity change cannot be zero";
        else if (Math.abs((long) change) > 1_000_000) msg = "Quantity change is too large";
        else if ((reason == Reason.RECEIVED || reason == Reason.RETURNED) && change < 0) msg = "\"" + reason.db() + "\" must be a positive quantity";
        else if (reason == Reason.DAMAGED && change > 0) msg = "\"damaged\" must be a negative quantity";
        else if (reason == Reason.OTHER && (note == null || note.isBlank())) {
            msg = "A note is required when the reason is \"other\"";
            field = "note";
        }
        if (msg != null) {
            throw AppException.validation("Validation failed", List.of(Map.of("field", field, "message", msg)));
        }
    }

    public Page<Adjustment> history(Ctx ctx, AdjustmentRepository.Filter filter) {
        return adjustments.list(ctx.workspaceId(), filter);
    }
}
