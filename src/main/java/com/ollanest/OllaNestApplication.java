package com.ollanest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OllaNestApplication {
    public static void main(String[] args) {
        SpringApplication.run(OllaNestApplication.class, args);
    }
}
