package world.gregs.voidps.storage

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Opt-in: set `VOID_DB_TEST_MYSQL` to any non-empty value to run.
 * Set `VOID_MYSQL_TEST_URL` as well to point at an existing server; otherwise a
 * mysql:5.6 container is started through testcontainers.
 */
@EnabledIfEnvironmentVariable(named = "VOID_DB_TEST_MYSQL", matches = ".+")
class MysqlDatabaseStorageTest : StorageTest(), MysqlDatabaseTest {
    override val storage = DatabaseStorage()
}
