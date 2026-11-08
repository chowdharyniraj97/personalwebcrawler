plugins {
    alias(libs.plugins.spotless) apply false
}

val catalog = libs

// Settings shared by every module, so new modules only declare their own dependencies.
subprojects {
    apply(plugin = "java-library")
    apply(plugin = "com.diffplug.spotless")

    repositories { mavenCentral() }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion = JavaLanguageVersion.of(25)
    }

    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            googleJavaFormat(catalog.versions.google.java.format.get())
            removeUnusedImports()
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-Werror"))
    }

    dependencies {
        "compileOnly"(catalog.lombok)
        "annotationProcessor"(catalog.lombok)

        "testImplementation"(platform(catalog.junit.bom))
        "testImplementation"(catalog.junit.jupiter)
        "testRuntimeOnly"(catalog.junit.launcher)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
