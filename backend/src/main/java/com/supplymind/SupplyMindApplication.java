package com.supplymind;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SupplyMindApplication {

    public static void main(String[] args) {
        SpringApplication.run(SupplyMindApplication.class, args);
    }
}
