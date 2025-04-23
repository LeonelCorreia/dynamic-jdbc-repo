plugins {
    alias(libs.plugins.kotlin.jvm)
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
    id("me.champeau.jmh") version "0.7.3"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":jdbcRepoLib"))

    // Domain model for micro benchmarking
    implementation(project(":chatDomainModel"))
    implementation(project(":sportsDomainModel"))

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(22)
}
