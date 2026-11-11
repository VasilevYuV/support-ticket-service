plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spotless)
}

group = "ru.vasilev"
version = "0.1.0-SNAPSHOT"
description = "Сервис обработки заявок. Васильев Юрий, 4П1.23."

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.webmvc)
    implementation(libs.spring.boot.actuator)
    implementation(libs.spring.boot.data.jpa)
    implementation(libs.spring.boot.flyway)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)
    implementation(libs.jackson.kotlin)
    implementation(libs.kotlin.reflect)

    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}

dependencyLocking {
    lockAllConfigurations()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
    }
}

spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat(libs.versions.javaFormat.get()).aosp()
        removeUnusedImports()
    }
    kotlin {
        target("src/**/*.kt")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
    }
}

val checkTextConventions =
    tasks.register("checkTextConventions") {
        group = "verification"
        description = "Проверяет отсутствие длинного и среднего тире в исходниках и документации."
        val sources =
            fileTree(projectDir) {
                include("README.md", "docs/**", "src/**", "*.gradle.kts", "gradle/*.toml")
            }
        inputs.files(sources)
        doLast {
            val forbidden = Regex("[\u2012-\u2015]")
            val violations = sources.files.filter { forbidden.containsMatchIn(it.readText(Charsets.UTF_8)) }
            check(violations.isEmpty()) {
                "Недопустимые тире: ${violations.joinToString { it.relativeTo(projectDir).path }}"
            }
        }
    }

tasks.check {
    dependsOn(checkTextConventions)
}
