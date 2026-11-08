plugins {
    alias(libs.plugins.protobuf)
}

dependencies {
    api(libs.crawler.commons)
    api(libs.protobuf.java)
    api(libs.xxhash)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
}
