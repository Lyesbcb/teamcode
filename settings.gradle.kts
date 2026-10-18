rootProject.name = "teamcode"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
include(":api", ":client", ":domain", ":infrastructure", ":presentation", ":app", ":cli")
