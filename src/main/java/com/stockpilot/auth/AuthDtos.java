package com.stockpilot.auth;

import com.stockpilot.workspace.WorkspaceService.WorkspaceSummary;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public final class AuthDtos {
    private AuthDtos() {}

    public record RegisterRequest(
            @NotBlank(message = "Email is required") @Email(message = "Email is invalid") @Size(max = 254) String email,
            @NotBlank(message = "Name is required") @Size(max = 100, message = "Name must be at most 100 characters") String name,
            @NotBlank(message = "Password is required")
            @Size(min = 8, max = 72, message = "Password must be 8 to 72 characters") String password,
            @Size(max = 120, message = "Workspace name must be at most 120 characters") String workspaceName) {}

    public record LoginRequest(
            @NotBlank(message = "Email is required") String email,
            @NotBlank(message = "Password is required") @Size(max = 72) String password) {}

    public record UserDto(UUID id, String email, String name) {}

    public record SessionDto(String token, UserDto user, List<WorkspaceSummary> workspaces) {}

    public record MeDto(UserDto user, List<WorkspaceSummary> workspaces) {}
}
