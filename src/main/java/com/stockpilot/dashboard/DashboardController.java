package com.stockpilot.dashboard;

import com.stockpilot.common.ApiResponse;
import com.stockpilot.dashboard.DashboardService.Dashboard;
import com.stockpilot.workspace.Ctx;
import com.stockpilot.workspace.Permission;
import com.stockpilot.workspace.WorkspaceAccess;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/dashboard")
public class DashboardController {
    private final DashboardService dashboard;
    private final WorkspaceAccess access;

    public DashboardController(DashboardService dashboard, WorkspaceAccess access) {
        this.dashboard = dashboard;
        this.access = access;
    }

    @GetMapping
    public ApiResponse<Dashboard> get(
            Authentication auth, @PathVariable UUID workspaceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Ctx ctx = access.require(auth, workspaceId, Permission.DASHBOARD_READ);
        return ApiResponse.of(dashboard.get(ctx, from, to));
    }
}
