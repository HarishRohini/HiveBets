plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api("org.springframework.boot:spring-boot-autoconfigure")
    api("tools.jackson.core:jackson-databind")
    api("io.opentelemetry:opentelemetry-api")
    compileOnly("org.springframework.boot:spring-boot-starter-rabbitmq")
    compileOnly("org.springframework.boot:spring-boot-starter-jdbc")
    compileOnly("org.springframework.boot:spring-boot-starter-webmvc")
    compileOnly("org.springframework.boot:spring-boot-starter-validation")
    compileOnly("org.springframework.amqp:spring-rabbit")
    compileOnly("org.springframework:spring-jdbc")
    compileOnly("org.springframework:spring-webmvc")
    compileOnly("jakarta.servlet:jakarta.servlet-api")

    testFixturesApi("com.networknt:json-schema-validator:3.0.9")
    testFixturesApi("tools.jackson.core:jackson-databind")
    testFixturesApi("org.junit.jupiter:junit-jupiter-api")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("org.springframework.boot:spring-boot-starter-rabbitmq")
}
