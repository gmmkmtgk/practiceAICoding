package com.schedulr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Random;

@Service
public class EmailService {

    private final Random random = new Random();
    private final int latencyMs;
    private final double failureRate;

    public EmailService(
            @Value("${schedulr.email.latency-ms}") int latencyMs,
            @Value("${schedulr.email.failure-rate}") double failureRate) {
        this.latencyMs = latencyMs;
        this.failureRate = failureRate;
    }

    public void sendBookingConfirmation(String toEmail, String host, String startAt) {
        if (toEmail == null || toEmail.isEmpty()) {
            throw new IllegalArgumentException("email required");
        }

        try {
            Thread.sleep(latencyMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (random.nextDouble() < failureRate) {
            throw new RuntimeException("email service unavailable");
        }
    }
}
