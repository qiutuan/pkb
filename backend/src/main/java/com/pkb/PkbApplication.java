package com.pkb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PkbApplication {
    public static void main(String[] args) {
        SpringApplication.run(PkbApplication.class, args);
    }
}
