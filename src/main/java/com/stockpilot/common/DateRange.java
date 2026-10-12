package com.stockpilot.common;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Date-only filters (YYYY-MM-DD) are interpreted in UTC. {@code from} is inclusive from 00:00:00Z;
 * {@code to} is inclusive of the whole day (the query uses an exclusive bound at the next day's 00:00:00Z).
 */
public record DateRange(OffsetDateTime from, OffsetDateTime toExclusive) {

    public static DateRange of(LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw AppException.validation("Validation failed",
                    List.of(Map.of("field", "to", "message", "'to' must not be before 'from'")));
        }
        return new DateRange(
                from == null ? null : from.atStartOfDay().atOffset(ZoneOffset.UTC),
                to == null ? null : to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));
    }
}
