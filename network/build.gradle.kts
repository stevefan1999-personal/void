plugins {
    id("shared")
}

dependencies {
    implementation(project(":buffer"))
    implementation(project(":cache"))

    implementation(libs.ktor)
    implementation(libs.ktor.network)
    implementation(libs.ktor.websockets)
    implementation(libs.ktor.netty)
    implementation(libs.jbcrypt)
    implementation(libs.inline.logging)
    implementation(libs.kotlinx.coroutines)

    testImplementation(libs.bundles.testing)
}
