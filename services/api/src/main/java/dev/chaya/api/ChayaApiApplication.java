package dev.chaya.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ChayaApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(ChayaApiApplication.class, args);
    }
}
