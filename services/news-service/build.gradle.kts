plugins { id("org.springframework.boot") }
dependencies {
    implementation(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
    implementation("org.springframework.boot:spring-boot-starter-rabbitmq")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("com.rometools:rome:2.1.0")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
