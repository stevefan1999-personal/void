import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("shared")
}

/**
 * Enables an optional DB *test* backend when the matching Gradle property
 * (`-PdbTest<Name>`) or environment variable (`VOID_DB_TEST_<NAME>`) is set.
 */
fun wantTest(name: String): Boolean =
    project.hasProperty("dbTest${name.replaceFirstChar { it.uppercase() }}") ||
        !System.getenv("VOID_DB_TEST_${name.uppercase()}").isNullOrBlank()

/**
 * Runtime drivers. PostgreSQL, MySQL/MariaDB and SQLite are bundled by default
 * because their jars are small and they cover the common deployment targets.
 * Oracle's `ojdbc11` is heavy (~6 MB) and niche, so it is opt-in via
 * `-PincludeOracle`.
 */
val includeOracle: Boolean = project.hasProperty("includeOracle")

dependencies {
    implementation(project(":types"))
    implementation(project(":engine"))
    implementation(libs.bundles.database)
    implementation(libs.bundles.logging)

    implementation(libs.mysql)
    implementation(libs.mariadb)
    implementation(libs.sqlite)
    if (includeOracle) implementation(libs.ojdbc)

    testImplementation(libs.bundles.testing)
    testImplementation(libs.bundles.postgres)
    testImplementation(enforcedPlatform(libs.embedded.postgres.binaries))

    if (wantTest("mysql")) testImplementation(libs.bundles.mysql)
    if (wantTest("mariadb")) testImplementation(libs.bundles.mariadb)
    if (wantTest("oracle")) testImplementation(libs.bundles.oracle)
    if (wantTest("sqlite")) testImplementation(libs.bundles.sqlite)
}

tasks.named<KotlinCompile>("compileTestKotlin") {
    if (!wantTest("mysql")) exclude("**/Mysql*.kt")
    if (!wantTest("oracle")) exclude("**/Oracle*.kt")
    if (!wantTest("sqlite")) exclude("**/Sqlite*.kt")
}
