package com.stockpilot.common;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Date-only filters (YYYY-MM-DD) are interpreted as calendar days in IST (Asia/Kolkata, UTC+05:30), the
 * application's business timezone. {@code from} is inclusive from 00:00 IST; {@code to} is inclusive of the whole
 * day (the query uses an exclusive bound at the next day's 00:00 IST). Timestamps are stored and returned in UTC.
 */
public record DateRange(OffsetDateTime from, OffsetDateTime toExclusive) {
    /** Business timezone for all date filters and the dashboard's default range. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");


    public static DateRange of(LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw AppException.validation("Validation failed",
                    List.of(Map.of("field", "to", "message", "'to' must not be before 'from'")));
        }
        return new DateRange(
                from == null ? null : from.atStartOfDay(ZONE).toOffsetDateTime(),
                to == null ? null : to.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime());
    }
}
