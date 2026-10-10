plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    id("io.ktor.plugin") version "3.6.0"
}

group = "app.kairo"
version = "1.0.0"

application {
    mainClass.set("app.kairo.ApplicationKt")
}

kotlin {
    jvmToolchain(21)
}

ktor {
    fatJar {
        archiveFileName.set("kairo.jar")
    }
}

dependencies {
    implementation(platform("io.ktor:ktor-bom:3.6.0"))

    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-server-auth")
    implementation("io.ktor:ktor-server-auth-jwt")
    implementation("io.ktor:ktor-server-websockets")
    implementation("io.ktor:ktor-server-status-pages")
    implementation("io.ktor:ktor-server-call-logging")
    implementation("io.ktor:ktor-server-default-headers")
    implementation("io.ktor:ktor-server-compression")
    implementation("io.ktor:ktor-server-rate-limit")
    implementation("io.ktor:ktor-server-forwarded-header")
    implementation("io.ktor:ktor-server-cors")

    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-cio")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    implementation("org.postgresql:postgresql:42.7.14")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.flywaydb:flyway-core:13.10.0")
    implementation("org.flywaydb:flyway-database-postgresql:13.10.0")

    implementation("at.favre.lib:bcrypt:0.10.2")
    implementation("org.dmfs:lib-recur:0.17.1")
    implementation("ch.qos.logback:logback-classic:1.6.5")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation("io.ktor:ktor-client-content-negotiation")
}

tasks.test {
    useJUnitPlatform()
    environment("KAIRO_TEST_DB_URL", System.getenv("KAIRO_TEST_DB_URL") ?: "")
}
