package com.stockpilot;

import static org.junit.jupiter.api.Assertions.*;

import com.stockpilot.common.AppException;
import com.stockpilot.common.DateRange;
import com.stockpilot.order.OrderStatus;
import com.stockpilot.order.OrderStatus.Action;
import com.stockpilot.workspace.Permission;
import com.stockpilot.workspace.Role;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pure unit tests: no Spring, no database. */
class DomainRulesTest {

    @Test
    @DisplayName("Order lifecycle: allowed and forbidden transitions")
    void transitions() {
        assertTrue(Action.CONFIRM.allowedFrom(OrderStatus.PENDING));
        assertFalse(Action.CONFIRM.allowedFrom(OrderStatus.CONFIRMED));
        assertFalse(Action.CONFIRM.allowedFrom(OrderStatus.COMPLETED));
        assertFalse(Action.CONFIRM.allowedFrom(OrderStatus.CANCELLED));

        assertTrue(Action.COMPLETE.allowedFrom(OrderStatus.CONFIRMED));
        assertFalse(Action.COMPLETE.allowedFrom(OrderStatus.PENDING));
        assertFalse(Action.COMPLETE.allowedFrom(OrderStatus.COMPLETED));
        assertFalse(Action.COMPLETE.allowedFrom(OrderStatus.CANCELLED));

        assertTrue(Action.CANCEL.allowedFrom(OrderStatus.PENDING));
        assertTrue(Action.CANCEL.allowedFrom(OrderStatus.CONFIRMED));
        assertFalse(Action.CANCEL.allowedFrom(OrderStatus.COMPLETED));
        assertFalse(Action.CANCEL.allowedFrom(OrderStatus.CANCELLED));

        assertEquals(OrderStatus.CONFIRMED, Action.CONFIRM.to());
        assertEquals(OrderStatus.COMPLETED, Action.COMPLETE.to());
        assertEquals(OrderStatus.CANCELLED, Action.CANCEL.to());
    }

    @Test
    @DisplayName("Only cancellation releases stock (reserve-at-creation strategy)")
    void onlyCancelReleases() {
        assertTrue(Action.CANCEL.releasesStock());
        assertFalse(Action.CONFIRM.releasesStock());
        assertFalse(Action.COMPLETE.releasesStock());
    }

    @Test
    @DisplayName("Permission matrix: owner-only vs shared actions")
    void permissions() {
        for (Permission p : Permission.values()) {
            assertTrue(p.allows(Role.OWNER), "owner must have " + p);
        }
        assertFalse(Permission.PRODUCT_WRITE.allows(Role.STAFF));
        assertFalse(Permission.MEMBER_READ.allows(Role.STAFF));
        assertFalse(Permission.MEMBER_MANAGE.allows(Role.STAFF));
        assertTrue(Permission.PRODUCT_READ.allows(Role.STAFF));
        assertTrue(Permission.INVENTORY_ADJUST.allows(Role.STAFF));
        assertTrue(Permission.ORDER_CREATE.allows(Role.STAFF));
        assertTrue(Permission.ORDER_UPDATE.allows(Role.STAFF));
        assertTrue(Permission.DASHBOARD_READ.allows(Role.STAFF));
    }

    @Test
    @DisplayName("Date filters are UTC days; 'to' is inclusive of the whole day")
    void dateRange() {
        DateRange r = DateRange.of(LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-31"));
        assertEquals(java.time.OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, ZoneOffset.UTC), r.from());
        assertEquals(java.time.OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, ZoneOffset.UTC), r.toExclusive());
        assertNull(DateRange.of(null, null).from());
        assertThrows(AppException.class, () -> DateRange.of(LocalDate.parse("2026-03-02"), LocalDate.parse("2026-03-01")));
    }
}
