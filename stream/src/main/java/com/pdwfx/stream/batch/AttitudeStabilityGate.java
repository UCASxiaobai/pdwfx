package com.pdwfx.stream.batch;

import com.pdwfx.stream.config.StreamProperties;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 转向门控：只看横滚角绝对值，不再用俯仰/横滚平滑度。
 * <ul>
 *   <li>{@code STABLE}：|roll| ≤ 门限，可落盘</li>
 *   <li>{@code BANKING}：|roll| &gt; 门限但尚未持续满确认秒数，丢弃高横滚点、不封批</li>
 *   <li>{@code MANEUVER}：高横滚已持续满确认秒数（确认转向）；调用方按本批时长决定是否封批分析</li>
 * </ul>
 * 横滚回到门限以内即回到 {@code STABLE}，可继续接收。
 */
public class AttitudeStabilityGate {

    public enum State {
        STABLE,
        BANKING,
        MANEUVER
    }

    private final StreamProperties.Batch cfg;
    private State state = State.STABLE;
    private boolean trackingHighRoll = false;
    private long highRollSinceEpochMs = 0L;
    private boolean lastHighRoll = false;
    private final AtomicLong droppedManeuver = new AtomicLong();

    public AttitudeStabilityGate(StreamProperties.Batch cfg) {
        this.cfg = cfg;
    }

    public State getState() {
        return state;
    }

    public long getDroppedManeuverCount() {
        return droppedManeuver.get();
    }

    public void incrementDropped() {
        droppedManeuver.incrementAndGet();
    }

    /** 重启接收：清零姿态门控与丢点计数。 */
    public void reset() {
        state = State.STABLE;
        trackingHighRoll = false;
        highRollSinceEpochMs = 0L;
        lastHighRoll = false;
        droppedManeuver.set(0L);
    }

    /**
     * 当前样本是否允许落盘：仅横滚不超过转向门限时接收。
     */
    public boolean shouldAccept() {
        return !lastHighRoll;
    }

    /**
     * 从开批到当前触发时刻是否达到最小分析时长（含中间被丢弃的高横滚时段）。
     */
    public boolean isBatchLongEnough(long firstEpochMs, long triggerEpochMs) {
        if (triggerEpochMs < firstEpochMs) {
            return false;
        }
        long minMs = Math.max(0L, Math.round(cfg.getAttitudeMinBatchSeconds() * 1000.0));
        return (triggerEpochMs - firstEpochMs) >= minMs;
    }

    /**
     * 用最新横滚更新状态。乱序早到的样本不重置已确认的转向，也不回拨计时起点。
     *
     * @return true 表示本拍刚确认转向（进入 MANEUVER 边沿）
     */
    public boolean update(double rollDeg, long epochMs) {
        double threshold = Math.max(0.0, cfg.getAttitudeRollTurnDeg());
        long holdMs = Math.max(0L, Math.round(cfg.getAttitudeRollHoldSeconds() * 1000.0));
        boolean highRoll = Math.abs(rollDeg) > threshold;
        lastHighRoll = highRoll;
        if (!highRoll) {
            trackingHighRoll = false;
            highRollSinceEpochMs = 0L;
            state = State.STABLE;
            return false;
        }
        if (!trackingHighRoll) {
            trackingHighRoll = true;
            highRollSinceEpochMs = epochMs;
        } else if (epochMs < highRollSinceEpochMs) {
            return false;
        }
        if (state == State.MANEUVER) {
            return false;
        }
        if (epochMs - highRollSinceEpochMs >= holdMs) {
            state = State.MANEUVER;
            return true;
        }
        state = State.BANKING;
        return false;
    }
}
