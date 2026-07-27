package com.scenefinder.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 时间窗有交集的场景集合；合并分析时取各成员时间窗的并集作为导出/绘图时段。
 */
public class SceneOverlapGroup {

    private final List<Integer> ranks;
    private final Instant spanStart;
    private final Instant spanEnd;
    private final List<SceneMemberBand> memberBands;

    public SceneOverlapGroup(
            List<Integer> ranks,
            Instant spanStart,
            Instant spanEnd,
            List<SceneSummaryEntry> members
    ) {
        this.ranks = ranks == null ? new ArrayList<>() : new ArrayList<>(ranks);
        Collections.sort(this.ranks);
        this.spanStart = spanStart;
        this.spanEnd = spanEnd;
        if (members == null) {
            this.memberBands = new ArrayList<>();
        } else {
            this.memberBands = members.stream()
                    .map(e -> new SceneMemberBand(
                            e.getRank(),
                            e.getWindowStart(),
                            e.getWindowEnd(),
                            e.getFreqMinMhz(),
                            e.getFreqMaxMhz()))
                    .collect(Collectors.toList());
        }
    }

    public List<Integer> getRanks() {
        return ranks;
    }

    public int getPrimaryRank() {
        return ranks.isEmpty() ? 0 : ranks.get(0);
    }

    public Instant getSpanStart() {
        return spanStart;
    }

    public Instant getSpanEnd() {
        return spanEnd;
    }

    public List<SceneMemberBand> getMemberBands() {
        return memberBands;
    }

    public boolean isMerged() {
        return ranks.size() > 1;
    }

    public long getSpanStartEpochMs() {
        return spanStart != null ? spanStart.toEpochMilli() : 0L;
    }

    public long getSpanEndEpochMs() {
        return spanEnd != null ? spanEnd.toEpochMilli() : 0L;
    }

    public static final class SceneMemberBand {
        private final int rank;
        private final Instant windowStart;
        private final Instant windowEnd;
        private final double freqMinMhz;
        private final double freqMaxMhz;

        public SceneMemberBand(
                int rank,
                Instant windowStart,
                Instant windowEnd,
                double freqMinMhz,
                double freqMaxMhz
        ) {
            this.rank = rank;
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.freqMinMhz = freqMinMhz;
            this.freqMaxMhz = freqMaxMhz;
        }

        public int getRank() {
            return rank;
        }

        public Instant getWindowStart() {
            return windowStart;
        }

        public Instant getWindowEnd() {
            return windowEnd;
        }

        public double getFreqMinMhz() {
            return freqMinMhz;
        }

        public double getFreqMaxMhz() {
            return freqMaxMhz;
        }
    }
}
