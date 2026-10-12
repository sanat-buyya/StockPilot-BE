package com.stockpilot.auth;

import com.stockpilot.auth.AuthDtos.*;
import com.stockpilot.common.ApiResponse;
import com.stockpilot.workspace.WorkspaceAccess;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final WorkspaceAccess access;

    public AuthController(AuthService auth, WorkspaceAccess access) {
        this.auth = auth;
        this.access = access;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SessionDto> register(@Valid @RequestBody RegisterRequest req) {
        return ApiResponse.of(auth.register(req));
    }

    @PostMapping("/login")
    public ApiResponse<SessionDto> login(@Valid @RequestBody LoginRequest req) {
        return ApiResponse.of(auth.login(req));
    }

    @GetMapping("/me")
    public ApiResponse<MeDto> me(Authentication authentication) {
        return ApiResponse.of(auth.me(access.userId(authentication)));
    }
}
