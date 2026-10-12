package com.stockpilot.common;

import org.postgresql.util.PSQLException;

/** Helpers to translate PostgreSQL constraint violations into business errors. */
public final class DbErrors {
    private DbErrors() {}

    /** Returns the violated constraint/index name for a unique-violation (SQLSTATE 23505), else null. */
    public static String uniqueViolation(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof PSQLException p && "23505".equals(p.getSQLState())) {
                return p.getServerErrorMessage() == null ? "" : p.getServerErrorMessage().getConstraint();
            }
            if (c.getCause() == c) break;
        }
        return null;
    }
}
