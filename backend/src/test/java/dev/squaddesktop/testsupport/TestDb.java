package dev.squaddesktop.testsupport;

import java.nio.file.Path;
import java.time.Clock;

import dev.squaddesktop.persistence.Database;
import dev.squaddesktop.persistence.Migrator;

/** Banco SQLite real num arquivo temporário, já migrado. */
public final class TestDb {

    private TestDb() {
    }

    public static Database create(Path dir) {
        Database database = new Database(dir.resolve("teste.db"));
        new Migrator(database, Clock.systemUTC()).migrate();
        return database;
    }
}
