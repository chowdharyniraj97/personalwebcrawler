plugins {
    // Lets Gradle download the JDK version the build asks for.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "personalwebcrawler"

include("url-core", "node", "synthetic-web")
