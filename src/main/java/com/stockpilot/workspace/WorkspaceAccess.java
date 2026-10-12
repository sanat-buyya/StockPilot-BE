package com.stockpilot.workspace;

import com.stockpilot.common.AppException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * The authorization gate. The user identity is taken ONLY from the verified JWT subject, and membership
 * is re-read from the database on every request (so removed members lose access immediately).
 * A non-member gets 404 (not 403) so workspace ids cannot be probed.
 */
@Component
public class WorkspaceAccess {
    private final JdbcClient jdbc;

    public WorkspaceAccess(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID userId(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new AppException(com.stockpilot.common.ErrorCode.UNAUTHENTICATED, "Authentication required");
        }
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException e) {
            throw new AppException(com.stockpilot.common.ErrorCode.UNAUTHENTICATED, "Authentication required");
        }
    }

    public Ctx require(Authentication auth, UUID workspaceId, Permission permission) {
        UUID userId = userId(auth);
        Optional<Role> role = jdbc.sql("SELECT role FROM workspace_members WHERE workspace_id = :w AND user_id = :u")
                .param("w", workspaceId).param("u", userId)
                .query((rs, n) -> Role.fromDb(rs.getString("role"))).optional();
        Role r = role.orElseThrow(() -> AppException.notFound("Workspace"));
        if (!permission.allows(r)) {
            throw AppException.forbidden();
        }
        return new Ctx(userId, workspaceId, r);
    }
}
