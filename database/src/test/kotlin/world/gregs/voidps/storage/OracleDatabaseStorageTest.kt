package world.gregs.voidps.storage

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Opt-in: set `VOID_DB_TEST_ORACLE` to any non-empty value to run.
 * Set `VOID_ORACLE_TEST_URL`/`VOID_ORACLE_TEST_USER`/`VOID_ORACLE_TEST_PASS` to
 * point at an existing server; otherwise a gvenzl/oracle-free container is
 * started through testcontainers.
 */
@EnabledIfEnvironmentVariable(named = "VOID_DB_TEST_ORACLE", matches = ".+")
class OracleDatabaseStorageTest : StorageTest(), OracleDatabaseTest {
    override val storage = DatabaseStorage()
}
