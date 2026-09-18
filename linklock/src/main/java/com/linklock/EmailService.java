package com.linklock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Random;

@Service
public class EmailService {

    private final Random random = new Random();
    private final SecureRandom secureRandom = new SecureRandom();
    private final int latencyMs;
    private final double failureRate;
    private final String baseUrl;

    public EmailService(
            @Value("${linklock.email.latency-ms}") int latencyMs,
            @Value("${linklock.email.failure-rate}") double failureRate,
            @Value("${linklock.email.base-url}") String baseUrl) {
        this.latencyMs = latencyMs;
        this.failureRate = failureRate;
        this.baseUrl = baseUrl;
    }

    public String generateToken() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public void sendMagicLink(String toEmail, String token, String redirectTo) {
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
