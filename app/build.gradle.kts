plugins {
    alias(libs.plugins.jvm)
    alias(libs.plugins.ktor)
    application
}
kotlin { jvmToolchain(21) }
application { mainClass.set("me.nathanfallet.teamcode.ApplicationKt") }
dependencies {
    implementation(projects.api)
    implementation(projects.domain)
    implementation(projects.infrastructure)
    implementation(projects.presentation)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.server.netty)
    implementation(libs.koin.core)
    implementation(libs.logback)
}
ktor { docker { jreVersion.set(JavaVersion.VERSION_21); localImageName.set("teamcode") } }
tasks.shadowJar {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    filesNotMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
}
