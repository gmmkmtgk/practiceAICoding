package com.transcribe;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Random;

@Service
public class TranscriptionService {

    private final Random random = new Random();
    private final int latencyMs;
    private final double failureRate;

    public TranscriptionService(
            @Value("${transcribe.transcription.latency-ms}") int latencyMs,
            @Value("${transcribe.transcription.failure-rate}") double failureRate) {
        this.latencyMs = latencyMs;
        this.failureRate = failureRate;
    }

    public String transcribeAudio(byte[] audioData, String language) {
        try {
            Thread.sleep(latencyMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (random.nextDouble() < failureRate) {
            throw new RuntimeException("transcription model unavailable");
        }

        return "[" + language + "] mock transcription of " + audioData.length + " bytes";
    }
}
