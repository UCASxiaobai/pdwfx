package com.pdwfx.signal.model;

public class BurstWindow {
    private long start;
    private long end;
    private double durationMs;
    private int pulseCount;
    private double meanPriMs;
    private double meanFreq;

    public BurstWindow() {}

    public BurstWindow(long start, long end, double durationMs, int pulseCount, double meanPriMs, double meanFreq) {
        this.start = start;
        this.end = end;
        this.durationMs = durationMs;
        this.pulseCount = pulseCount;
        this.meanPriMs = meanPriMs;
        this.meanFreq = meanFreq;
    }

    public long getStart() { return start; }
    public void setStart(long start) { this.start = start; }
    public long getEnd() { return end; }
    public void setEnd(long end) { this.end = end; }
    public double getDurationMs() { return durationMs; }
    public void setDurationMs(double durationMs) { this.durationMs = durationMs; }
    public int getPulseCount() { return pulseCount; }
    public void setPulseCount(int pulseCount) { this.pulseCount = pulseCount; }
    public double getMeanPriMs() { return meanPriMs; }
    public void setMeanPriMs(double meanPriMs) { this.meanPriMs = meanPriMs; }
    public double getMeanFreq() { return meanFreq; }
    public void setMeanFreq(double meanFreq) { this.meanFreq = meanFreq; }
}
