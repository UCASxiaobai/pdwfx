package com.pdwfx.signal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.pdwfx.signal", "com.scenefinder"})
public class SignalAnalysisApplication {
    public static void main(String[] args) {
        SpringApplication.run(SignalAnalysisApplication.class, args);
    }
}
