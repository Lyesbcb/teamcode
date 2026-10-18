plugins {
    alias(libs.plugins.jvm) apply false
    alias(libs.plugins.multiplatform) apply false
    alias(libs.plugins.serialization) apply false
    alias(libs.plugins.ktor) apply false
}
allprojects {
    group = "me.nathanfallet.teamcode"
    version = "0.1.0-SNAPSHOT"
}
