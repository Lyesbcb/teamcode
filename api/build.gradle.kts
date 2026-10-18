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
            api(libs.serialization)
            api(libs.ktor.resources)
        }
        commonTest.dependencies { implementation(kotlin("test")); implementation(libs.coroutines.test) }
        jvmTest.dependencies { runtimeOnly(libs.junit) }
    }
}
