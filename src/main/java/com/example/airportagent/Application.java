package com.example.airportagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class Application {

    /** Starts the application. */
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
