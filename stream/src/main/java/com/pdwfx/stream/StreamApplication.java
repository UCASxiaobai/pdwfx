package com.pdwfx.stream;

import com.pdwfx.stream.config.StreamProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 独立流式模块入口：TCP 收包 → B108 解析 → 落盘切批 → HTTP 调用原分析服务。
 * <p>与 {@code signal-analysis} backend 进程分离，默认不修改原有功能。</p>
 */
@SpringBootApplication
@EnableConfigurationProperties(StreamProperties.class)
@EnableScheduling
public class StreamApplication {

    public static void main(String[] args) {
        SpringApplication.run(StreamApplication.class, args);
    }
}
