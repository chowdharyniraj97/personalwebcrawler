plugins {
    application
}

application {
    mainClass = "dev.crawler.node.Main"
}

dependencies {
    implementation(project(":url-core"))
    implementation(libs.kafka.clients)
    implementation(libs.slf4j)
    runtimeOnly(libs.logback)
}
