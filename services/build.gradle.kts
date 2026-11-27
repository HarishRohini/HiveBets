plugins {
    java
    id("org.springframework.boot") version "4.2.0" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

val springCloudVersion: String by project

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    group = "dev.hivebets"
    version = "0.1.0"

    java {
        toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
    }

    repositories { mavenCentral() }

    the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
        imports {
            mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
            mavenBom("org.springframework.cloud:spring-cloud-dependencies:$springCloudVersion")
        }
    }

    dependencies {
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile> { options.compilerArgs.add("-parameters") }
    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }
}
