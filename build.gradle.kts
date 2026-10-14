plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.spring") version "2.1.0"
    kotlin("plugin.jpa") version "2.1.0"

    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "org.example"
version = "1.3.1"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Web + MVC
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")

    // Security (OBRIGATÓRIO pro @PreAuthorize)
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.thymeleaf.extras:thymeleaf-extras-springsecurity6")

    // JPA
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("com.h2database:h2")
    implementation("org.flywaydb:flyway-core")

    // HTTPS: gera o certificado próprio do servidor na primeira inicialização (CertificadoHttps)
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    // Kotlin
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation(kotlin("reflect"))

    // WebFlux (SSE, streams, etc)
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test") // login simulado, CSRF no MockMvc
    testImplementation("io.projectreactor:reactor-test")                    // StepVerifier (SSE / Flux)
    testImplementation("io.mockk:mockk:1.13.13")                            // mocks idiomáticos em Kotlin
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Gera META-INF/build-info.properties: a tela de status lê a versão daqui (não precisa repetir no código)
springBoot {
    buildInfo()
}
