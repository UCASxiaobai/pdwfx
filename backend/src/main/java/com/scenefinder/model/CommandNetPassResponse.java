package com.scenefinder.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 指挥网二次分析总结果：是否跳过、替换了哪些一次场景、二次场景处理项。
 */
public class CommandNetPassResponse {

    private boolean skipped;
    private String skipReason;
    private int occupancyWindowCount;
    private int clusterCount;
    private String commandNetOutputDir;
    private List<Integer> replacedRanks = new ArrayList<Integer>();
    private List<SceneProcessResponse> items = new ArrayList<SceneProcessResponse>();
    private List<CommandNetAwacsPanel> awacsPanels = new ArrayList<CommandNetAwacsPanel>();
    private long elapsedMs;

    public boolean isSkipped() {
        return skipped;
    }

    public void setSkipped(boolean skipped) {
        this.skipped = skipped;
    }

    public String getSkipReason() {
        return skipReason;
    }

    public void setSkipReason(String skipReason) {
        this.skipReason = skipReason;
    }

    public int getOccupancyWindowCount() {
        return occupancyWindowCount;
    }

    public void setOccupancyWindowCount(int occupancyWindowCount) {
        this.occupancyWindowCount = occupancyWindowCount;
    }

    public int getClusterCount() {
        return clusterCount;
    }

    public void setClusterCount(int clusterCount) {
        this.clusterCount = clusterCount;
    }

    public String getCommandNetOutputDir() {
        return commandNetOutputDir;
    }

    public void setCommandNetOutputDir(String commandNetOutputDir) {
        this.commandNetOutputDir = commandNetOutputDir;
    }

    public List<Integer> getReplacedRanks() {
        return replacedRanks;
    }

    public void setReplacedRanks(List<Integer> replacedRanks) {
        this.replacedRanks = replacedRanks != null ? replacedRanks : new ArrayList<Integer>();
    }

    public List<SceneProcessResponse> getItems() {
        return items;
    }

    public void setItems(List<SceneProcessResponse> items) {
        this.items = items != null ? items : new ArrayList<SceneProcessResponse>();
    }

    public List<CommandNetAwacsPanel> getAwacsPanels() {
        return awacsPanels;
    }

    public void setAwacsPanels(List<CommandNetAwacsPanel> awacsPanels) {
        this.awacsPanels = awacsPanels != null ? awacsPanels : new ArrayList<CommandNetAwacsPanel>();
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }
}
