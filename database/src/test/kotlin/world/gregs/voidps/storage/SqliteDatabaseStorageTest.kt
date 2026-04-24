package world.gregs.voidps.storage

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Opt-in: set `VOID_DB_TEST_SQLITE` to any non-empty value to run.
 * No external server required – a temporary SQLite file is used.
 */
@EnabledIfEnvironmentVariable(named = "VOID_DB_TEST_SQLITE", matches = ".+")
class SqliteDatabaseStorageTest : StorageTest(), SqliteDatabaseTest {
    override val storage = DatabaseStorage()
}
