buildscript {
    dependencies {
        classpath(libs.kotlin)
        classpath(libs.shadow)
    }
}

plugins {
    `kotlin-dsl`
    alias(libs.plugins.jacoco.aggregation)
    alias(libs.plugins.spotless)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.kotlin.gradle)
    implementation(libs.spotless)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

tasks.test {
    useJUnitPlatform()
}

spotless {
    kotlin {
        target("src/main/kotlin/TeaVmClient*.kt", "src/test/kotlin/TeaVmClient*.kt")
        ktlint().editorConfigOverride(mapOf("ktlint_code_style" to "intellij_idea"))
    }
}
