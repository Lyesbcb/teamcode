plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.serialization)
}
val generatedVersionDirectory = layout.buildDirectory.dir("generated/teamcode-version")
val generateBuildInfo by tasks.registering {
    inputs.property("version", project.version.toString())
    outputs.dir(generatedVersionDirectory)
    doLast {
        val output = generatedVersionDirectory.get().file("me/nathanfallet/teamcode/api/BuildInfo.kt").asFile
        output.parentFile.mkdirs()
        output.writeText("package me.nathanfallet.teamcode.api\n\nobject BuildInfo { const val VERSION: String = \"${project.version}\" }\n")
    }
}
kotlin {
    jvmToolchain(21)
    jvm { testRuns["test"].executionTask.configure { useJUnitPlatform() } }
    macosArm64(); macosX64(); linuxX64(); linuxArm64(); mingwX64()
    sourceSets {
        commonMain { kotlin.srcDir(generateBuildInfo) }
        commonMain.dependencies {
            api(libs.serialization)
            api(libs.ktor.resources)
        }
        commonTest.dependencies { implementation(kotlin("test")); implementation(libs.coroutines.test) }
        jvmTest.dependencies { runtimeOnly(libs.junit) }
    }
}
