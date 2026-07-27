package com.pdwfx.signal.api.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 外部 JSON 分析请求：{@link #signals} 中可混合多个频率，由核心按 freqTolerance 分网。
 */
public class AnalyzeSignalsRequest {
    private List<DetectSignalDto> signals = new ArrayList<>();
    /** 同频容差 MHz，默认 0.1 */
    private Double freqTolerance;
    /** 分析完成后预构建的网络 ID，逗号分隔，如 "230,76" */
    private String preloadNetworkIds;

    public List<DetectSignalDto> getSignals() { return signals; }
    public void setSignals(List<DetectSignalDto> signals) { this.signals = signals; }
    public Double getFreqTolerance() { return freqTolerance; }
    public void setFreqTolerance(Double freqTolerance) { this.freqTolerance = freqTolerance; }
    public String getPreloadNetworkIds() { return preloadNetworkIds; }
    public void setPreloadNetworkIds(String preloadNetworkIds) { this.preloadNetworkIds = preloadNetworkIds; }
}
