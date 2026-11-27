plugins { id("org.springframework.boot") }

dependencies {
    implementation(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter-rabbitmq")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.duckdb:duckdb_jdbc:1.5.6.0")
    implementation(platform("software.amazon.awssdk:bom:2.40.0"))
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:url-connection-client")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
