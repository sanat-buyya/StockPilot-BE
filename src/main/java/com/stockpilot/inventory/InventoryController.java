package com.stockpilot.inventory;

import com.stockpilot.common.ApiResponse;
import com.stockpilot.common.DateRange;
import com.stockpilot.product.ProductRepository.Page;
import com.stockpilot.workspace.Ctx;
import com.stockpilot.workspace.Permission;
import com.stockpilot.workspace.WorkspaceAccess;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/inventory")
public class InventoryController {
    private final InventoryService inventory;
    private final WorkspaceAccess access;

    public InventoryController(InventoryService inventory, WorkspaceAccess access) {
        this.inventory = inventory;
        this.access = access;
    }

    /** Workspace-wide stock movement history (manual adjustments and order reserve/release events). */
    @GetMapping("/adjustments")
    public ApiResponse<List<Adjustment>> history(
            Authentication auth, @PathVariable UUID workspaceId,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) Reason reasonType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "1") @Min(1) @Max(100_000) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int pageSize) {
        Ctx ctx = access.require(auth, workspaceId, Permission.INVENTORY_READ);
        DateRange r = DateRange.of(from, to);
        Page<Adjustment> result = inventory.history(ctx,
                new AdjustmentRepository.Filter(productId, reasonType, r.from(), r.toExclusive(), pageSize, (page - 1) * pageSize));
        return ApiResponse.page(result.rows(), page, pageSize, result.total());
    }
}
