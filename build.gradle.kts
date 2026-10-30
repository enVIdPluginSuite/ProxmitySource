import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.21"
    id("com.gradleup.shadow") version "9.2.2"
}

group = "dev.etran"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Compile against the OLDEST supported API (1.20.1). Everything the plugin touches is stable
    // Bukkit API, so the same jar loads on 1.20.1 through 26.x (Paper or Spigot).
    compileOnly("io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT")
    // kotlin-stdlib is added by the Kotlin plugin and bundled into the jar by shadowJar.

    testImplementation(kotlin("test"))
}

// Java 17 bytecode runs on Java 17 (1.20.1-1.20.4), 21 (1.20.5-1.21.11) and 25 (26.x).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

tasks.withType<JavaCompile> {
    options.release.set(17)
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.shadowJar {
    archiveBaseName.set("ProxmitySource")
    archiveClassifier.set("")
}

// `gradle build` produces build/libs/ProxmitySource-<version>.jar (the shaded, ready-to-use jar).
tasks.jar {
    archiveClassifier.set("plain")
}
tasks.build {
    dependsOn(tasks.shadowJar)
}
