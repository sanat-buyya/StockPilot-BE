package com.stockpilot.inventory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Why stock changed. Only {@code manual} reasons may be submitted by users; the rest are written by the system. */
public enum Reason {
    RECEIVED(true), COUNT_CORRECTION(true), DAMAGED(true), RETURNED(true), OTHER(true),
    INITIAL_STOCK(false), ORDER_RESERVE(false), ORDER_RELEASE(false);

    private final boolean manual;

    Reason(boolean manual) {
        this.manual = manual;
    }

    public boolean manual() {
        return manual;
    }

    @JsonValue
    public String db() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static Reason fromDb(String s) {
        return valueOf(s.trim().toUpperCase(Locale.ROOT));
    }
}
