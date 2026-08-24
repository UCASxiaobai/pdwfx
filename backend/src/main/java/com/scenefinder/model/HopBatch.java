package com.scenefinder.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 测向匹配用的换频编批：一条过噪声门的链（{@code hop:{seedId}}）或未入链单轨（{@code track:{id}}）。
 */
public class HopBatch {

    private String batchId;
    private String label;
    private int seedTrackId;
    private List<Integer> linkedTrackIds = Collections.emptyList();
    private boolean noise;
    private boolean hasFreqHop;
    private int hits;
    private double durationSeconds;
    private double seedFreqMhz;
    private String platformType;
    private List<String> platformTypes = Collections.emptyList();
    private List<Map<String, Object>> hops = Collections.emptyList();
    private List<TrackObservation> observations = Collections.emptyList();

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public int getSeedTrackId() {
        return seedTrackId;
    }

    public void setSeedTrackId(int seedTrackId) {
        this.seedTrackId = seedTrackId;
    }

    public List<Integer> getLinkedTrackIds() {
        return linkedTrackIds;
    }

    public void setLinkedTrackIds(List<Integer> linkedTrackIds) {
        this.linkedTrackIds = linkedTrackIds != null ? linkedTrackIds : Collections.emptyList();
    }

    public boolean isNoise() {
        return noise;
    }

    public void setNoise(boolean noise) {
        this.noise = noise;
    }

    public boolean isHasFreqHop() {
        return hasFreqHop;
    }

    public void setHasFreqHop(boolean hasFreqHop) {
        this.hasFreqHop = hasFreqHop;
    }

    public int getHits() {
        return hits;
    }

    public void setHits(int hits) {
        this.hits = hits;
    }

    public double getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(double durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public double getSeedFreqMhz() {
        return seedFreqMhz;
    }

    public void setSeedFreqMhz(double seedFreqMhz) {
        this.seedFreqMhz = seedFreqMhz;
    }

    public String getPlatformType() {
        return platformType;
    }

    public void setPlatformType(String platformType) {
        this.platformType = platformType;
    }

    public List<String> getPlatformTypes() {
        return platformTypes;
    }

    public void setPlatformTypes(List<String> platformTypes) {
        this.platformTypes = platformTypes != null ? platformTypes : Collections.emptyList();
    }

    public List<Map<String, Object>> getHops() {
        return hops;
    }

    public void setHops(List<Map<String, Object>> hops) {
        this.hops = hops != null ? hops : Collections.emptyList();
    }

    public List<TrackObservation> getObservations() {
        return observations;
    }

    public void setObservations(List<TrackObservation> observations) {
        this.observations = observations != null ? observations : new ArrayList<TrackObservation>();
    }
}
