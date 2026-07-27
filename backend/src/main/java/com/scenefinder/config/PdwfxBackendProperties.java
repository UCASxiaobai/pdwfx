package com.scenefinder.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * pdwfx backend 信号分析服务连接配置。
 */
@Component
@ConfigurationProperties(prefix = "pdwfx.backend")
public class PdwfxBackendProperties {

    /** 合并部署时为 true：直接调用本进程信号分析，不走 HTTP */
    private boolean inProcess = true;

    /** 远程模式时的 backend 根地址，例如 http://localhost:18080 */
    private String baseUrl = "http://localhost:18080";

    /** POST /api/signals/analyze 的 freqTolerance 默认值（MHz） */
    private double defaultFreqTolerance = 0.01;

    /** HTTP 连接超时（秒） */
    private int connectTimeoutSeconds = 30;

    /** HTTP 读取超时（秒），大文件分析可能较久 */
    private int readTimeoutSeconds = 600;

    public boolean isInProcess() {
        return inProcess;
    }

    public void setInProcess(boolean inProcess) {
        this.inProcess = inProcess;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public double getDefaultFreqTolerance() {
        return defaultFreqTolerance;
    }

    public void setDefaultFreqTolerance(double defaultFreqTolerance) {
        this.defaultFreqTolerance = defaultFreqTolerance;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public void setReadTimeoutSeconds(int readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
    }
}
