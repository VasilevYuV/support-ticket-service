plugins {
    java
    jacoco
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

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        html.required = true
        xml.required = true
        csv.required = false
    }
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
    implementation(libs.springdoc.webmvc.ui)

    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.json.schema.validator)
    testImplementation(libs.jackson.yaml)
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

tasks.processTestResources {
    from("docs/openapi.yaml") {
        into("contract")
    }
}

tasks.processResources {
    from("docs/openapi.yaml") {
        into("static")
    }
}

tasks.register<Test>("e2eTest") {
    group = "verification"
    description = "Проверяет сквозные сценарии через HTTP и PostgreSQL."
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("ru.vasilev.support.*.scenario.*E2ETest")
    }
    shouldRunAfter(tasks.test)
}

tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Проверяет HTTP API, миграции и PostgreSQL через Testcontainers."
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("ru.vasilev.support.*.controller.*Test")
        includeTestsMatching("ru.vasilev.support.database.migration.*Test")
    }
    shouldRunAfter(tasks.test)
}

tasks.register<Test>("unitTest") {
    group = "verification"
    description = "Проверяет сервисы и валидацию без Spring-контекста и Docker."
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("ru.vasilev.support.*.service.*Test")
        includeTestsMatching("ru.vasilev.support.*.validation.*Test")
        includeTestsMatching("ru.vasilev.support.common.contract.*Test")
    }
    systemProperty("user.timezone", "Pacific/Honolulu")
    systemProperty("junit.jupiter.testmethod.order.default", "org.junit.jupiter.api.MethodOrderer\$Random")
    systemProperty("junit.jupiter.testclass.order.default", "org.junit.jupiter.api.ClassOrderer\$Random")
    systemProperty("junit.jupiter.execution.order.random.seed", "20261112")
    shouldRunAfter(tasks.test)
}

tasks.register<Test>("reportRegressionTest") {
    group = "verification"
    description = "Повторяет проверки отчёта в другом часовом поясе и порядке."
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("*SummaryReport*")
        includeTestsMatching("*ReportPeriodParserTest")
        includeTestsMatching("*TicketOverduePolicyTest")
    }
    systemProperty("user.timezone", "Pacific/Kiritimati")
    systemProperty("junit.jupiter.testmethod.order.default", "org.junit.jupiter.api.MethodOrderer\$Random")
    systemProperty("junit.jupiter.testclass.order.default", "org.junit.jupiter.api.ClassOrderer\$Random")
    systemProperty("junit.jupiter.execution.order.random.seed", "20261111")
    shouldRunAfter(tasks.test)
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
                include(
                    "README.md",
                    "docs/**",
                    "src/**",
                    "scripts/**",
                    "*.gradle.kts",
                    "gradle/*.toml",
                    "Dockerfile",
                    "compose.yaml",
                    ".dockerignore",
                    ".env.example",
                )
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
    dependsOn(tasks.jacocoTestReport)
}
