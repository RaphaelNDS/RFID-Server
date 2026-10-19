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
    // apksig (leitura e verificação de APK) só é publicado no repositório do Google
    google()
}

dependencies {
    // Web + MVC
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")

    // Security (OBRIGATÓRIO pro @PreAuthorize)
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.thymeleaf.extras:thymeleaf-extras-springsecurity6")

    // JPA
    // Banco: PostgreSQL (no Docker, ver docker/); o Flyway precisa do módulo do banco desde a versão 10
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // HTTPS: gera o certificado próprio do servidor na primeira inicialização (CertificadoHttps)
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    // Kotlin
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation(kotlin("reflect"))

    // Atualização do app pelo servidor: lê a versão de dentro do APK e confere a assinatura
    // (a mesma biblioteca que o Android usa para verificar APKs)
    implementation("com.android.tools.build:apksig:8.12.3")

    // WebFlux (SSE, streams, etc)
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test") // login simulado, CSRF no MockMvc
    testImplementation("io.projectreactor:reactor-test")                    // StepVerifier (SSE / Flux)
    testImplementation("io.mockk:mockk:1.13.13")                            // mocks idiomáticos em Kotlin
    // Testes num PostgreSQL de verdade, num contêiner temporário (precisa do Docker ligado)
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
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
