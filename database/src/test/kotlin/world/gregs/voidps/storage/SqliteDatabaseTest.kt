package world.gregs.voidps.storage

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Files

/**
 * Exercises the storage layer against an ephemeral SQLite file so the full
 * test matrix can run without Docker. SQLite uses a TEXT/INTEGER/REAL/BLOB
 * type system, so this also covers the most minimal dialect Exposed supports.
 */
interface SqliteDatabaseTest {

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
        private var file: java.io.File? = null

        @BeforeAll
        @JvmStatic
        fun setupDatabase() {
            val path = Files.createTempFile("void-sqlite-test-", ".db")
            path.toFile().deleteOnExit()
            file = path.toFile()
            DatabaseStorage.connect(
                username = "",
                password = "",
                driver = "org.sqlite.JDBC",
                url = "jdbc:sqlite:${path.toAbsolutePath()}",
                poolSize = 1,
            )
        }

        @AfterAll
        @JvmStatic
        fun dropDatabase() {
            file?.delete()
        }
    }
}
