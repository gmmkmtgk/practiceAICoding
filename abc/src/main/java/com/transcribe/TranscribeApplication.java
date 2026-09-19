package com.transcribe;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class TranscribeApplication {

    public static void main(String[] args) {
        SpringApplication.run(TranscribeApplication.class, args);
    }

    @Bean
    ApplicationRunner initDatabase(Database database) {
        return args -> database.init();
    }

    @Bean
    @ConditionalOnProperty(name = "transcribe.worker.enabled", havingValue = "true", matchIfMissing = true)
    ApplicationRunner startWorker(JobWorker worker) {
        return args -> worker.start();
    }
}
