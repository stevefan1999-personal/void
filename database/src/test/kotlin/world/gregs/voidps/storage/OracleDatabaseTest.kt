package world.gregs.voidps.storage

import com.github.michaelbull.logging.InlineLogger
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.oracle.OracleContainer
import org.testcontainers.utility.DockerImageName

/**
 * Spins up gvenzl/oracle-free to prove that the storage layer is also
 * compatible with Oracle. The image is large (~1 GB) and the first boot takes
 * ~60 s, so the suite is skipped if Docker is not reachable.
 */
interface OracleDatabaseTest {

    // Oracle's identity-column bookkeeping leaves behind sequences that
    // `SchemaUtils.drop` does not clean up, so re-creating the schema between
    // tests fails with ORA-00955. Instead, create the schema once (via the
    // `DatabaseStorage.connect` call in @BeforeAll) and clear the data between
    // tests by deleting rows in reverse FK order.
    @AfterEach
    fun tidy() {
        transaction {
            val order: Array<Table> = arrayOf(
                ClaimsTable,
                ItemHistoryTable,
                ActiveOffersTable,
                OffersTable,
                PlayerHistoryTable,
                InventoriesTable,
                VariablesTable,
                LevelsTable,
                ExperienceTable,
                AccountsTable,
            )
            for (table in order) {
                table.deleteAll()
            }
        }
    }

    companion object {
        private val logger = InlineLogger()
        private var oracle: OracleContainer? = null

        @BeforeAll
        @JvmStatic
        fun startContainer() {
            val externalUrl = System.getenv("VOID_ORACLE_TEST_URL")
            if (!externalUrl.isNullOrBlank()) {
                val user = System.getenv("VOID_ORACLE_TEST_USER") ?: "test"
                val pass = System.getenv("VOID_ORACLE_TEST_PASS") ?: "password"
                logger.info { "Using external Oracle at $externalUrl" }
                wipeSchema(externalUrl, user, pass)
                DatabaseStorage.connect(
                    username = user,
                    password = pass,
                    driver = "oracle.jdbc.OracleDriver",
                    url = externalUrl,
                    poolSize = 2,
                    createSchema = false,
                )
                applySchema(externalUrl, user, pass)
                return
            }
            try {
                val container = OracleContainer(
                    DockerImageName.parse("gvenzl/oracle-free:23-slim-faststart")
                        .asCompatibleSubstituteFor("gvenzl/oracle-free"),
                )
                    .withDatabaseName("testdb")
                    .withUsername("test")
                    .withPassword("password")
                container.start()
                DatabaseStorage.connect(
                    username = container.username,
                    password = container.password,
                    driver = container.driverClassName,
                    url = container.jdbcUrl,
                    poolSize = 2,
                )
                oracle = container
            } catch (e: Throwable) {
                logger.info { "Docker not reachable; skipping Oracle storage tests (${e.javaClass.simpleName}: ${e.message})." }
                Assumptions.assumeTrue(false, "Docker not available for Oracle tests")
            }
        }

        /**
         * Drops every leftover object in the current Oracle schema so that
         * `DatabaseStorage.connect` can cleanly issue the CREATE statements
         * even after a previous run aborted partway through. Exposed's Oracle
         * dialect does not emit `IF NOT EXISTS` clauses for sequences, so any
         * orphan `*_ID_SEQ` object would otherwise trigger ORA-00955.
         */
        /**
         * Drops every table and sequence in the current user's schema so that a
         * previous aborted run doesn't trip up the fresh `CREATE` statements.
         * Exposed's Oracle dialect doesn't emit `IF NOT EXISTS` for sequences
         * (ORA-00955 without this), so we must do it ourselves.
         */
        private fun wipeSchema(url: String, username: String, password: String) {
            Class.forName("oracle.jdbc.OracleDriver")
            java.sql.DriverManager.getConnection(url, username, password).use { conn ->
                conn.createStatement().use { st ->
                    st.execute(
                        """
                        BEGIN
                          FOR r IN (SELECT table_name FROM user_tables) LOOP
                            EXECUTE IMMEDIATE 'DROP TABLE "'||r.table_name||'" CASCADE CONSTRAINTS';
                          END LOOP;
                          FOR r IN (SELECT sequence_name FROM user_sequences) LOOP
                            EXECUTE IMMEDIATE 'DROP SEQUENCE "'||r.sequence_name||'"';
                          END LOOP;
                        END;
                        """.trimIndent(),
                    )
                }
            }
        }

        /**
         * Applies Exposed's generated DDL statement-by-statement through plain
         * JDBC. Going through HikariCP + a single `SchemaUtils.create` call
         * intermittently races with Oracle's post-CREATE housekeeping, so the
         * Oracle test harness takes this slightly slower but deterministic
         * path.
         */
        private fun applySchema(url: String, username: String, password: String) {
            val stmts = transaction { SchemaUtils.createStatements(*DatabaseStorage.tables) }
            java.sql.DriverManager.getConnection(url, username, password).use { conn ->
                conn.createStatement().use { st ->
                    for (statement in stmts) {
                        st.executeUpdate(statement)
                    }
                }
            }
        }

        @AfterAll
        @JvmStatic
        fun stopContainer() {
            oracle?.stop()
        }
    }
}
