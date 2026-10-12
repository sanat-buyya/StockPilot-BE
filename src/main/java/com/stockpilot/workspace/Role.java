package com.stockpilot.workspace;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum Role {
    OWNER, STAFF;

    @JsonValue
    public String db() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static Role fromDb(String s) {
        return valueOf(s.trim().toUpperCase(Locale.ROOT));
    }
}
