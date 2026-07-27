package com.scenefinder.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 一条跨帧关联得到的方位轨迹（非设备 ID，仅表示方位随时间连续变化的一条线）。
 * <p>
 * 状态由 {@link com.scenefinder.service.TrackBuilderService} 维护：
 * 当前方位、角速度预测、命中次数、连续丢帧数，以及全部 {@link TrackObservation}。
 * </p>
 */
public final class BearingTrack {

    /** 全局唯一轨迹编号 */
    private int id;
    /** 当前估计方位（度） */
    private double bearingDeg;
    /** 方位角速度（度/秒），用于下一帧预测 */
    private double velocityDegPerSec;
    /** 最近一次关联成功的帧时刻 */
    private Instant lastTime;
    /** 累计关联成功帧数 */
    private int hits;
    /** 当前连续未匹配帧数 */
    private int missedFrames;
    /** 该轨迹关联到的所有检测观测（含源行号） */
    private final List<TrackObservation> observations = new ArrayList<>();

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public double getBearingDeg() {
        return bearingDeg;
    }

    public void setBearingDeg(double bearingDeg) {
        this.bearingDeg = bearingDeg;
    }

    public double getVelocityDegPerSec() {
        return velocityDegPerSec;
    }

    public void setVelocityDegPerSec(double velocityDegPerSec) {
        this.velocityDegPerSec = velocityDegPerSec;
    }

    public Instant getLastTime() {
        return lastTime;
    }

    public void setLastTime(Instant lastTime) {
        this.lastTime = lastTime;
    }

    public int getHits() {
        return hits;
    }

    public void setHits(int hits) {
        this.hits = hits;
    }

    public int getMissedFrames() {
        return missedFrames;
    }

    public void setMissedFrames(int missedFrames) {
        this.missedFrames = missedFrames;
    }

    public List<TrackObservation> getObservations() {
        return observations;
    }

    public Instant startTime() {
        return observations.isEmpty() ? null : observations.get(0).getTime();
    }

    public Instant endTime() {
        return observations.isEmpty() ? null : observations.get(observations.size() - 1).getTime();
    }

    /** 轨迹起止时间差（秒）。 */
    public double durationSeconds() {
        if (observations.size() < 2) {
            return 0;
        }
        return (endTime().toEpochMilli() - startTime().toEpochMilli()) / 1000.0;
    }

    /**
     * 平滑度指标：相邻观测点平均角速度（度/秒）的绝对值均值。
     * 值越小轨迹越平滑；场景评分时作为惩罚项。
     */
    public double smoothnessScore() {
        if (observations.size() < 3) {
            return 0;
        }
        double sum = 0;
        for (int i = 1; i < observations.size(); i++) {
            double dt = (observations.get(i).getTime().toEpochMilli()
                    - observations.get(i - 1).getTime().toEpochMilli()) / 1000.0;
            if (dt <= 0) {
                continue;
            }
            double v = Math.abs(BearingMath.shortestDelta(
                    observations.get(i - 1).getBearingDeg(), observations.get(i).getBearingDeg())) / dt;
            sum += v;
        }
        return sum / (observations.size() - 1);
    }
}
