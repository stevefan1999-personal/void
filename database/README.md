# Database storage

Persistent player / grand exchange storage for the Void server. The module uses
[Exposed](https://github.com/JetBrains/Exposed) on top of
[HikariCP](https://github.com/brettwooldridge/HikariCP) and is wired into the
game in `GameModules.kt` when built with `-PincludeDb`.

## Supported dialects

| Dialect                     | Bundled in default jar? | Driver coord                                    |
|-----------------------------|-------------------------|-------------------------------------------------|
| PostgreSQL 12+              | yes (primary)           | `org.postgresql:postgresql`                     |
| MySQL 5.6+ (8.x recommended)| yes                     | `com.mysql:mysql-connector-j` (8.x LTS)         |
| MariaDB 10.5+               | yes                     | `org.mariadb.jdbc:mariadb-java-client`          |
| SQLite 3.24+                | yes (Java only – see below) | `org.xerial:sqlite-jdbc`                    |
| Oracle Free 23c / 19c+      | opt-in (`-PincludeOracle`) | `com.oracle.database.jdbc:ojdbc11`           |

All five backends are covered by an integration test under `database/src/test/`.
Other dialects Exposed 0.48 understands (H2, SQL Server) should also work but
are not exercised.

### SQLite native library

`sqlite-jdbc` normally ships ~14 MB of cross-platform `.so`/`.dylib`/`.dll`
binaries (Windows/macOS/Linux/Android/Musl/FreeBSD × x86/x86_64/arm/armv6/armv7/
aarch64/ppc64/riscv64). The shadow jar strips these to stay slim; operators who
pick SQLite must provide the native themselves, e.g.:

* install `sqlite-jdbc` as a system package (`libsqlitejdbc-jni` etc.), or
* download the matching `libsqlitejdbc.so` from the xerial release page and
  point to it with `-Dorg.sqlite.lib.path=/path/to/dir -Dorg.sqlite.lib.name=libsqlitejdbc.so`.

### Oracle driver

`ojdbc11` adds ~6 MB to the fat jar and is only relevant to Oracle deployments,
so it is excluded by default. Build with `./gradlew :game:shadowJar -PincludeDb -PincludeOracle`
to bundle it, or drop it onto the classpath at runtime.

## List columns

Player state carries a few list-shaped columns (`looks`, `colours`, `friends`,
`inventory items`, …). These are dialect-aware:

* **PostgreSQL** keeps native `INT[]` / `TEXT[]` columns. Existing deployments
  need no migration.
* **MySQL / MariaDB / Oracle / SQL Server / H2** store the list in a single
  `TEXT` / `CLOB` column using a small, reversible text encoding:
  * `List<String>` – newline-separated Base64-encoded UTF-8 entries.
  * `List<Int>`    – comma-separated decimal entries.

The switch happens transparently inside `ListColumnTypes.kt` based on the active
`currentDialect` at transaction time, so the same compiled jar works on any of
the supported backends.

`clans()` aggregates in Kotlin rather than via PostgreSQL's `ARRAY_AGG`, so the
query path itself is now dialect-agnostic.

## Switching dialect

Point the `storage.database.*` settings at the target database and put the
matching JDBC driver on the classpath. See `game/src/main/resources/game.properties`
for copy-and-paste URL templates.

## Oracle notes

* Uses Oracle 12c or newer (Exposed generates sequence-backed auto-increment
  columns). CI exercises `gvenzl/oracle-free:23-slim-faststart`.
* `bool` columns are emitted by Exposed as `CHAR(1)` (`'Y'`/`'N'`) on versions
  before 23c. Existing behaviour is preserved transparently.
* `batchUpsert` compiles to `MERGE INTO ... USING DUAL`. MERGE cannot run under
  `TRANSACTION_SERIALIZABLE` without `ORA-08176`, so the Hikari isolation
  defaults to `READ_COMMITTED` on non-PostgreSQL dialects (PostgreSQL keeps
  `SERIALIZABLE`).
* Column named `index` was renamed to `slot` at the SQL level because `INDEX`
  is reserved in Oracle and Exposed 0.48 does not auto-quote it.
* `AccountsTable.id` dropped `.uniqueIndex()` because it's already the primary
  key and Oracle rejects the duplicate UNIQUE constraint with ORA-02261.
* The JDBC driver (`ojdbc11`) is free for development and production use since
  the OTN license change in 2019; add it to the classpath at runtime or via
  `game/build.gradle.kts`.

## Running the integration tests locally

`DatabaseStorageTest` (PostgreSQL embedded) runs every time. The three
containerised backends are opt-in – set the matching `VOID_DB_TEST_*` env var
to any non-empty value to enable them. Each test also honours a
`VOID_<DB>_TEST_URL` override pointing at a pre-existing server; without it,
testcontainers boots its own container.

```bash
# Default run – PostgreSQL only
./gradlew :database:test

# Opt in to SQLite (no Docker needed)
VOID_DB_TEST_SQLITE=1 ./gradlew :database:test

# Opt in to MySQL against an existing server
docker run -d --name mysql56 -e MYSQL_ROOT_PASSWORD=pw -e MYSQL_DATABASE=test -p 33061:3306 mysql:5.6
VOID_DB_TEST_MYSQL=1 \
  VOID_MYSQL_TEST_URL="jdbc:mysql://127.0.0.1:33061/test?useSSL=false&allowPublicKeyRetrieval=true" \
  ./gradlew :database:test

# Opt in to Oracle Free against an existing server
docker run -d --name oracle -e ORACLE_PASSWORD=pw -e APP_USER=test -e APP_USER_PASSWORD=pw -p 15210:1521 gvenzl/oracle-free:23-slim-faststart
VOID_DB_TEST_ORACLE=1 \
  VOID_ORACLE_TEST_URL="jdbc:oracle:thin:@//127.0.0.1:15210/FREEPDB1" \
  VOID_ORACLE_TEST_USER=test VOID_ORACLE_TEST_PASS=pw \
  ./gradlew :database:test

# All four backends
VOID_DB_TEST_SQLITE=1 VOID_DB_TEST_MYSQL=1 VOID_DB_TEST_ORACLE=1 \
  VOID_MYSQL_TEST_URL="…" VOID_ORACLE_TEST_URL="…" \
  ./gradlew :database:test
```
