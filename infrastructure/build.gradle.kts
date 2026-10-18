plugins { alias(libs.plugins.jvm); alias(libs.plugins.serialization) }
kotlin { jvmToolchain(21) }
dependencies {
    api(projects.domain)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.serialization)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    implementation(libs.flyway.mysql)
    implementation(libs.mysql)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testRuntimeOnly(libs.junit)
}
tasks.test { useJUnitPlatform() }
