package com.stockpilot.workspace;

import java.util.UUID;

/**
 * Proof that the caller is an authenticated member of {@code workspaceId} with {@code role}.
 * Services take a Ctx instead of loose ids, so every query is scoped to a verified workspace.
 */
public record Ctx(UUID userId, UUID workspaceId, Role role) {}
