package com.pdwfx.signal.model;

public class SeriesPoint {
    private long t;
    private double v;

    public SeriesPoint() {}
    public SeriesPoint(long t, double v) {
        this.t = t;
        this.v = v;
    }

    public long getT() { return t; }
    public void setT(long t) { this.t = t; }
    public double getV() { return v; }
    public void setV(double v) { this.v = v; }
}
