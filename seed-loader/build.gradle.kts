plugins {
    application
}

application {
    mainClass = "dev.crawler.seedloader.Main"
}

dependencies {
    implementation(project(":url-core"))
    implementation(libs.kafka.clients)
    implementation(libs.slf4j)
    runtimeOnly(libs.logback)
}
