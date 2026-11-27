package dev.hivebets.lakesink;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(LakeProperties.class)
public class LakeSinkApplication {
    public static void main(String[] args) {
        SpringApplication.run(LakeSinkApplication.class, args);
    }
}
