package com.scenefinder.model;

public class FrequencyBand {

    private final int id;
    private final double minMhz;
    private final double maxMhz;
    private final double centerMhz;

    public FrequencyBand(int id, double minMhz, double maxMhz, double centerMhz) {
        this.id = id;
        this.minMhz = minMhz;
        this.maxMhz = maxMhz;
        this.centerMhz = centerMhz;
    }

    public int getId() {
        return id;
    }

    public double getMinMhz() {
        return minMhz;
    }

    public double getMaxMhz() {
        return maxMhz;
    }

    public double getCenterMhz() {
        return centerMhz;
    }
}
