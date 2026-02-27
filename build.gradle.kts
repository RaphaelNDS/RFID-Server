plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.spring") version "2.1.0"
    kotlin("plugin.jpa") version "2.1.0"

    id("org.springframework.boot") version "3.2.1"
    id("io.spring.dependency-management") version "1.1.4"
}

group = "org.example"
version = "1.3.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    dependencies {

        // Web + MVC
        implementation("org.springframework.boot:spring-boot-starter-web")
        implementation("org.springframework.boot:spring-boot-starter-thymeleaf")

        // Security (OBRIGATÓRIO pro @PreAuthorize)
        implementation("org.springframework.boot:spring-boot-starter-security")

        // JPA
        implementation("org.springframework.boot:spring-boot-starter-data-jpa")
        runtimeOnly("com.h2database:h2")

        // Kotlin
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
        implementation(kotlin("reflect"))

        // WebFlux (SSE, streams, etc)
        implementation("org.springframework.boot:spring-boot-starter-webflux")

        // Test
        testImplementation("org.springframework.boot:spring-boot-starter-test")
    }

//    implementation("org.springframework.boot:spring-boot-starter-web")
//    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
//    runtimeOnly("com.h2database:h2")
//    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
//    implementation(kotlin("reflect"))
//    testImplementation("org.springframework.boot:spring-boot-starter-test")
//    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
//
//    implementation("org.springframework.boot:spring-boot-starter-webflux")
//    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

}

tasks.test {
    useJUnitPlatform()
}
