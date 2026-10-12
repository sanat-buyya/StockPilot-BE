package com.stockpilot;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.Path;

/**
 * Convenience for local development when PostgreSQL is not installed: starts a real embedded PostgreSQL on
 * localhost:54329 (database "postgres", user "postgres", no password) and keeps its data in ./.pgdata.
 * Run with: scripts/dev-db (see README). Not used in production.
 */
public final class LocalDevPostgres {
    private LocalDevPostgres() {}

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 54329;
        EmbeddedPostgres pg = EmbeddedPostgres.builder()
                .setPort(port)
                .setDataDirectory(Path.of(".pgdata").toAbsolutePath())
                .setCleanDataDirectory(false)
                .start();
        System.out.println("Embedded PostgreSQL ready: " + pg.getJdbcUrl("postgres", "postgres"));
        System.out.println("Press Ctrl+C to stop.");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                pg.close();
            } catch (Exception ignored) {
                // shutting down
            }
        }));
        Thread.currentThread().join();
    }
}
