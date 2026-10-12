package com.stockpilot.workspace;

import java.util.Set;

import static com.stockpilot.workspace.Role.OWNER;
import static com.stockpilot.workspace.Role.STAFF;

/** Single source of truth for authorization. Every controller method declares the permission it needs. */
public enum Permission {
    PRODUCT_READ(OWNER, STAFF),
    PRODUCT_WRITE(OWNER), // create, edit, activate/deactivate
    INVENTORY_READ(OWNER, STAFF),
    INVENTORY_ADJUST(OWNER, STAFF),
    ORDER_READ(OWNER, STAFF),
    ORDER_CREATE(OWNER, STAFF),
    ORDER_UPDATE(OWNER, STAFF), // confirm / complete / cancel
    DASHBOARD_READ(OWNER, STAFF),
    MEMBER_READ(OWNER),
    MEMBER_MANAGE(OWNER);

    private final Set<Role> roles;

    Permission(Role... roles) {
        this.roles = Set.of(roles);
    }

    public boolean allows(Role role) {
        return roles.contains(role);
    }
}
