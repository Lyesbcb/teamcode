plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.serialization)
}
kotlin {
    jvmToolchain(21)
    jvm { testRuns["test"].executionTask.configure { useJUnitPlatform() } }
    macosArm64(); macosX64(); linuxX64(); linuxArm64(); mingwX64()
    sourceSets {
        commonMain.dependencies {
            api(projects.api)
            api(libs.ktor.client.core)
            implementation(libs.coroutines)
            implementation(libs.ktor.client.resources)
            implementation(libs.ktor.client.json)
            implementation(libs.ktor.json)
        }
        commonTest.dependencies { implementation(kotlin("test")); implementation(libs.coroutines.test) }
        jvmTest.dependencies { runtimeOnly(libs.junit); implementation(libs.ktor.client.mock) }
    }
}
