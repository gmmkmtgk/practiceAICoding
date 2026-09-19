package com.schedulr;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class SchedulrApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchedulrApplication.class, args);
    }

    @Bean
    ApplicationRunner initDatabase(Database database) {
        return args -> database.init();
    }
}
