plugins { `java-library` }

dependencies {
    api("org.springframework.boot:spring-boot-autoconfigure")
    api("tools.jackson.core:jackson-databind")
    api("io.opentelemetry:opentelemetry-api")
    compileOnly("org.springframework.boot:spring-boot-starter-amqp")
    compileOnly("org.springframework.boot:spring-boot-starter-jdbc")
    compileOnly("org.springframework.boot:spring-boot-starter-webmvc")
    compileOnly("org.springframework.boot:spring-boot-starter-validation")
    compileOnly("org.springframework.amqp:spring-rabbit")
    compileOnly("org.springframework:spring-jdbc")
    compileOnly("org.springframework:spring-webmvc")
    compileOnly("jakarta.servlet:jakarta.servlet-api")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-amqp")
}
