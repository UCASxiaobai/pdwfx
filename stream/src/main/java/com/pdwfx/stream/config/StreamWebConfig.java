package com.pdwfx.stream.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class StreamWebConfig implements WebMvcConfigurer {

    private final StreamProperties properties;

    public StreamWebConfig(StreamProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String origins = properties.getCors().getAllowedOrigins();
        registry.addMapping("/api/stream/**")
                .allowedOriginPatterns(origins == null || origins.isEmpty() ? "*" : origins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*");
    }
}
