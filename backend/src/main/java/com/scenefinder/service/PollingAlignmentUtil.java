package com.scenefinder.service;

/**
 * 轮询时间窗对齐与槽位轮次覆盖率计算。
 */
public final class PollingAlignmentUtil {

    private PollingAlignmentUtil() {
    }

    /** 评分时间窗内按周期估算的期望轮次数。 */
    public static int expectedRoundCount(long windowStartMs, long windowEndMs, long periodMs) {
        if (periodMs <= 0 || windowEndMs < windowStartMs) {
            return 0;
        }
        return (int) Math.floor((windowEndMs - windowStartMs) / (double) periodMs) + 1;
    }

    /**
     * 槽位/目标在场景时间窗轮次中至少应出现的轮次数。
     *
     * @param roundCount 时间窗内统计到的对齐轮次数 N
     * @param coverageRatio 参与比例，如 0.8 表示至少 ceil(0.8×N) 轮
     */
    public static int minRoundHits(int roundCount, double coverageRatio) {
        if (roundCount <= 0) {
            return 0;
        }
        double ratio = Math.max(0.5, Math.min(1.0, coverageRatio));
        return Math.max(2, (int) Math.ceil(roundCount * ratio));
    }
}
