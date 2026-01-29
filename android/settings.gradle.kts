pluginManagement {
    val flutterSdkPath = run {
        val properties = java.util.Properties()
        file("local.properties").inputStream().use { properties.load(it) }
        properties.getProperty("flutter.sdk")
            ?: throw GradleException("flutter.sdk not set in local.properties")
    }

    includeBuild("$flutterSdkPath/packages/flutter_tools/gradle")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.flutter.flutter-plugin-loader") version "1.0.0"
    // UPDATED: Version 8.1.4 -> 8.6.0
    id("com.android.application") version "8.6.0" apply false
    // UPDATED: Version 1.9.22 -> 2.1.0
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}

include(":app")
