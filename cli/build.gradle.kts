plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.serialization)
}
kotlin {
    jvmToolchain(21)
    jvm { testRuns["test"].executionTask.configure { useJUnitPlatform() } }
    listOf(macosArm64(), macosX64(), linuxX64(), linuxArm64(), mingwX64()).forEach {
        it.binaries.executable {
            entryPoint = "me.nathanfallet.teamcode.cli.main"
            baseName = "teamcode"
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(projects.client)
            implementation(libs.clikt)
            implementation(libs.mordant)
            implementation(libs.okio)
            implementation(libs.coroutines)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
        }
        commonTest.dependencies { implementation(kotlin("test")); implementation(libs.coroutines.test) }
        jvmMain.dependencies { implementation(libs.ktor.client.cio) }
        jvmTest.dependencies { runtimeOnly(libs.junit) }
        macosMain.dependencies { implementation(libs.ktor.client.darwin) }
        linuxMain.dependencies { implementation(libs.ktor.client.curl) }
        mingwMain.dependencies { implementation(libs.ktor.client.winhttp) }
    }
}
