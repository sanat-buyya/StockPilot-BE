package com.stockpilot.workspace;

import com.stockpilot.common.AppException;
import com.stockpilot.common.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceService {
    public record WorkspaceSummary(UUID id, String name, String role) {}

    public record MemberDto(UUID userId, String name, String email, String role, Instant joinedAt) {}

    private final JdbcClient jdbc;

    public WorkspaceService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates the workspace and its first owner membership atomically (joins the caller's transaction if any). */
    @Transactional
    public WorkspaceSummary createInternal(UUID ownerId, String name) {
        UUID id = jdbc.sql("INSERT INTO workspaces (name, created_by) VALUES (:n, :u) RETURNING id")
                .param("n", name).param("u", ownerId).query(UUID.class).single();
        jdbc.sql("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (:w, :u, 'owner')")
                .param("w", id).param("u", ownerId).update();
        return new WorkspaceSummary(id, name, "owner");
    }

    public List<WorkspaceSummary> listForUser(UUID userId) {
        return jdbc.sql("""
                SELECT w.id, w.name, m.role FROM workspace_members m
                JOIN workspaces w ON w.id = m.workspace_id
                WHERE m.user_id = :u ORDER BY w.name, w.id""")
                .param("u", userId)
                .query((rs, n) -> new WorkspaceSummary(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("role")))
                .list();
    }

    public List<MemberDto> members(Ctx ctx) {
        return jdbc.sql("""
                SELECT u.id, u.name, u.email, m.role, m.created_at FROM workspace_members m
                JOIN users u ON u.id = m.user_id
                WHERE m.workspace_id = :w ORDER BY m.created_at, u.email""")
                .param("w", ctx.workspaceId())
                .query((rs, n) -> new MemberDto(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("email"),
                        rs.getString("role"), rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant()))
                .list();
    }

    /** Staff membership is created by an owner adding an already-registered user by email. */
    @Transactional
    public MemberDto addMember(Ctx ctx, String email, Role role) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        UUID userId = jdbc.sql("SELECT id FROM users WHERE email = :e").param("e", normalized).query(UUID.class).optional()
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "No registered user with that email. Ask them to register first."));
        int inserted = jdbc.sql("""
                INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (:w, :u, :r)
                ON CONFLICT (workspace_id, user_id) DO NOTHING""")
                .param("w", ctx.workspaceId()).param("u", userId).param("r", role.db()).update();
        if (inserted == 0) {
            throw new AppException(ErrorCode.CONFLICT, "That user is already a member of this workspace");
        }
        return members(ctx).stream().filter(m -> m.userId().equals(userId)).findFirst().orElseThrow();
    }

    @Transactional
    public MemberDto changeRole(Ctx ctx, UUID userId, Role role) {
        lockOwners(ctx);
        String current = currentRole(ctx, userId);
        if (current.equals("owner") && role == Role.STAFF) {
            requireAnotherOwner(ctx, userId);
        }
        jdbc.sql("UPDATE workspace_members SET role = :r WHERE workspace_id = :w AND user_id = :u")
                .param("r", role.db()).param("w", ctx.workspaceId()).param("u", userId).update();
        return members(ctx).stream().filter(m -> m.userId().equals(userId)).findFirst().orElseThrow();
    }

    @Transactional
    public void removeMember(Ctx ctx, UUID userId) {
        lockOwners(ctx);
        String current = currentRole(ctx, userId);
        if (current.equals("owner")) {
            requireAnotherOwner(ctx, userId);
        }
        jdbc.sql("DELETE FROM workspace_members WHERE workspace_id = :w AND user_id = :u")
                .param("w", ctx.workspaceId()).param("u", userId).update();
    }

    /** Serialises owner changes so two concurrent demotions cannot leave a workspace with no owner. */
    private void lockOwners(Ctx ctx) {
        jdbc.sql("SELECT user_id FROM workspace_members WHERE workspace_id = :w AND role = 'owner' FOR UPDATE")
                .param("w", ctx.workspaceId()).query(UUID.class).list();
    }

    private String currentRole(Ctx ctx, UUID userId) {
        return jdbc.sql("SELECT role FROM workspace_members WHERE workspace_id = :w AND user_id = :u")
                .param("w", ctx.workspaceId()).param("u", userId).query(String.class).optional()
                .orElseThrow(() -> AppException.notFound("Member"));
    }

    private void requireAnotherOwner(Ctx ctx, UUID excluding) {
        long others = jdbc.sql("SELECT count(*) FROM workspace_members WHERE workspace_id = :w AND role = 'owner' AND user_id <> :u")
                .param("w", ctx.workspaceId()).param("u", excluding).query(Long.class).single();
        if (others == 0) {
            throw new AppException(ErrorCode.CONFLICT, "A workspace must keep at least one owner");
        }
    }
}
