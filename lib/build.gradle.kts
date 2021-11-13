import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.5.31"
    `java-library`
    `maven-publish`
    id("org.jlleitschuh.gradle.ktlint") version "10.0.0"
}

group = "com.github.traxterz"
version = "0.1.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

repositories {
    mavenCentral()
    jcenter()
    mavenLocal()
}

val event_store_client: String by project
val ktor_version: String by project

dependencies {
    implementation(kotlin("stdlib"))
    api("com.eventstore:db-client-java:$event_store_client")
    implementation("io.ktor:ktor-server-core:$ktor_version")
    testImplementation("io.ktor:ktor-server-test-host:$ktor_version")
    testImplementation(kotlin("test"))
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict")
        jvmTarget = "1.8"
        languageVersion = "1.5"
        apiVersion = "1.5"
    }
}

val sourcesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets["main"].allSource)
}

publishing {
    (publications) {
        register("ktor-plugin-event-store-db", MavenPublication::class) {
            groupId = "com.github.traxterz"
            artifactId = "ktor-plugin-event-store-db"
            from(components["java"])
            artifact(sourcesJar)
        }
    }
}

tasks {
    test { useJUnitPlatform() }
    check { dependsOn(test) }
}

ktlint {
    version.set("0.43.0")
    ignoreFailures.set(false)
}
