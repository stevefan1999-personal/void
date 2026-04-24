package world.gregs.voidps.storage

import com.github.michaelbull.logging.InlineLogger
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Starts a MySQL 8 container for storage tests. The suite is skipped
 * (not failed) when Docker is not reachable, so local runs without Docker keep
 * working.
 */
interface MysqlDatabaseTest {

    @BeforeEach
    fun setup() {
        transaction {
            SchemaUtils.create(*DatabaseStorage.tables, inBatch = false)
        }
    }

    @AfterEach
    fun tidy() {
        transaction {
            SchemaUtils.drop(*DatabaseStorage.tables, inBatch = false)
        }
    }

    companion object {
        private val logger = InlineLogger()
        private var mysql: MySQLContainer<*>? = null

        @BeforeAll
        @JvmStatic
        fun startContainer() {
            val externalUrl = System.getenv("VOID_MYSQL_TEST_URL")
            if (!externalUrl.isNullOrBlank()) {
                logger.info { "Using external MySQL at $externalUrl" }
                DatabaseStorage.connect(
                    username = System.getenv("VOID_MYSQL_TEST_USER") ?: "root",
                    password = System.getenv("VOID_MYSQL_TEST_PASS") ?: "password",
                    driver = "com.mysql.cj.jdbc.Driver",
                    url = externalUrl,
                    poolSize = 2,
                )
                return
            }
            try {
                // mysql:5.6 is the supported baseline; see database/README.md.
                val container = MySQLContainer(DockerImageName.parse("mysql:5.6").asCompatibleSubstituteFor("mysql"))
                    .withDatabaseName("test")
                    .withUsername("root")
                    .withPassword("password")
                    .withUrlParam("rewriteBatchedStatements", "true")
                    .withUrlParam("useServerPrepStmts", "true")
                container.start()
                DatabaseStorage.connect(
                    username = container.username,
                    password = container.password,
                    driver = container.driverClassName,
                    url = container.jdbcUrl,
                    poolSize = 2,
                )
                mysql = container
            } catch (e: Throwable) {
                logger.info { "Docker not reachable; skipping MySQL storage tests (${e.javaClass.simpleName}: ${e.message})." }
                Assumptions.assumeTrue(false, "Docker not available for MySQL tests")
            }
        }

        @AfterAll
        @JvmStatic
        fun stopContainer() {
            mysql?.stop()
        }
    }
}
