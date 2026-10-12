package com.stockpilot.workspace;

import com.stockpilot.common.ApiResponse;
import com.stockpilot.workspace.WorkspaceService.MemberDto;
import com.stockpilot.workspace.WorkspaceService.WorkspaceSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {
    public record CreateWorkspaceRequest(@NotBlank(message = "Workspace name is required")
                                         @Size(max = 120, message = "Workspace name must be at most 120 characters") String name) {}

    public record AddMemberRequest(@NotBlank(message = "Email is required") @Email(message = "Email is invalid") String email,
                                   Role role) {}

    public record UpdateMemberRequest(@NotNull(message = "Role must be owner or staff") Role role) {}

    private final WorkspaceService service;
    private final WorkspaceAccess access;

    public WorkspaceController(WorkspaceService service, WorkspaceAccess access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public ApiResponse<List<WorkspaceSummary>> mine(Authentication auth) {
        return ApiResponse.of(service.listForUser(access.userId(auth)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WorkspaceSummary> create(Authentication auth, @Valid @RequestBody CreateWorkspaceRequest req) {
        return ApiResponse.of(service.createInternal(access.userId(auth), req.name().trim()));
    }

    @GetMapping("/{workspaceId}/members")
    public ApiResponse<List<MemberDto>> members(Authentication auth, @PathVariable UUID workspaceId) {
        return ApiResponse.of(service.members(access.require(auth, workspaceId, Permission.MEMBER_READ)));
    }

    @PostMapping("/{workspaceId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MemberDto> addMember(Authentication auth, @PathVariable UUID workspaceId,
                                            @Valid @RequestBody AddMemberRequest req) {
        Ctx ctx = access.require(auth, workspaceId, Permission.MEMBER_MANAGE);
        return ApiResponse.of(service.addMember(ctx, req.email(), req.role() == null ? Role.STAFF : req.role()));
    }

    @PatchMapping("/{workspaceId}/members/{userId}")
    public ApiResponse<MemberDto> updateMember(Authentication auth, @PathVariable UUID workspaceId, @PathVariable UUID userId,
                                               @Valid @RequestBody UpdateMemberRequest req) {
        Ctx ctx = access.require(auth, workspaceId, Permission.MEMBER_MANAGE);
        return ApiResponse.of(service.changeRole(ctx, userId, req.role()));
    }

    @DeleteMapping("/{workspaceId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(Authentication auth, @PathVariable UUID workspaceId, @PathVariable UUID userId) {
        service.removeMember(access.require(auth, workspaceId, Permission.MEMBER_MANAGE), userId);
    }
}
