@file:OptIn(org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCacheApi::class)

import org.jetbrains.kotlin.gradle.plugin.mpp.DisableCacheInKotlinVersion

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.serialization)
}
kotlin {
    jvmToolchain(21)
    jvm { testRuns["test"].executionTask.configure { useJUnitPlatform() } }
    listOf(macosArm64(), macosX64(), linuxX64(), linuxArm64(), mingwX64()).forEach { target ->
        if (!target.name.startsWith("mingw")) {
            target.compilations.getByName("main").cinterops.create("process") {
                definitionFile.set(project.file("src/nativeInterop/cinterop/process.def"))
                includeDirs(project.file("src/nativeInterop/cinterop"))
            }
        }
        target.binaries.executable {
            entryPoint = "me.nathanfallet.teamcode.cli.main"
            baseName = "teamcode"
            if (target.name.startsWith("linux")) {
                // Clikt 5.1.0 caches export the same internal symbol twice on Linux.
                // Re-evaluate this workaround on the next Kotlin/Clikt update.
                disableNativeCache(
                    version = DisableCacheInKotlinVersion.`2_4_20`,
                    reason = "Clikt 5.1.0 duplicate selfAndAncestors symbol in Linux static caches",
                )
            }
        }
    }
    sourceSets {
        // Shared POSIX implementation compiled with each platform's own process interop.
        macosMain.get().kotlin.srcDir("src/posixMain/kotlin")
        linuxMain.get().kotlin.srcDir("src/posixMain/kotlin")
        commonMain.dependencies {
            implementation(projects.client)
            implementation(libs.clikt)
            implementation(libs.mordant)
            implementation(libs.okio)
            implementation(libs.coroutines)
            implementation(libs.ktor.client.engine.defaults)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
        }
        commonTest.dependencies { implementation(kotlin("test")); implementation(libs.coroutines.test) }
        jvmTest.dependencies {
            runtimeOnly(libs.junit)
            implementation(libs.ktor.server.tests)
            implementation(libs.ktor.client.mock)
        }
    }
}
