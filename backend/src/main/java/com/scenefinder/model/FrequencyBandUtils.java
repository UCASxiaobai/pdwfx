package com.scenefinder.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 将检测点/轨迹主频按间隙聚类为频段。
 */
public final class FrequencyBandUtils {

    private FrequencyBandUtils() {
    }

    public static List<List<DetectionPoint>> partitionPoints(List<DetectionPoint> points, double gapMhz) {
        if (points.isEmpty()) {
            return Collections.emptyList();
        }
        List<DetectionPoint> sorted = new ArrayList<>(points);
        sorted.sort(Comparator.comparingDouble(DetectionPoint::getFrequencyMhz));

        List<List<DetectionPoint>> bands = new ArrayList<>();
        List<DetectionPoint> current = new ArrayList<>();
        current.add(sorted.get(0));
        double bandMin = sorted.get(0).getFrequencyMhz();

        for (int i = 1; i < sorted.size(); i++) {
            DetectionPoint point = sorted.get(i);
            if (point.getFrequencyMhz() - bandMin > gapMhz) {
                bands.add(current);
                current = new ArrayList<>();
                bandMin = point.getFrequencyMhz();
            }
            current.add(point);
        }
        bands.add(current);
        return bands;
    }

    public static Map<Integer, Integer> assignTracksToBands(
            Map<Integer, Double> dominantFreqByTrack,
            double gapMhz
    ) {
        List<Map.Entry<Integer, Double>> sorted = dominantFreqByTrack.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.comparingByValue())
                .collect(Collectors.toList());

        if (sorted.isEmpty()) {
            return Collections.emptyMap();
        }

        List<FrequencyBand> bands = new ArrayList<>();
        double bandMin = sorted.get(0).getValue();
        double bandMax = bandMin;
        int bandId = 0;

        for (int i = 1; i < sorted.size(); i++) {
            double freq = sorted.get(i).getValue();
            if (freq - bandMin > gapMhz) {
                bands.add(new FrequencyBand(bandId++, bandMin, bandMax, (bandMin + bandMax) / 2.0));
                bandMin = freq;
                bandMax = freq;
            } else {
                bandMax = freq;
            }
        }
        bands.add(new FrequencyBand(bandId, bandMin, bandMax, (bandMin + bandMax) / 2.0));

        Map<Integer, Integer> trackToBand = new LinkedHashMap<>();
        int currentBandIdx = 0;
        double currentBandMin = bands.get(0).getMinMhz();

        for (Map.Entry<Integer, Double> entry : sorted) {
            while (currentBandIdx < bands.size() - 1
                    && entry.getValue() - currentBandMin > gapMhz) {
                currentBandIdx++;
                currentBandMin = bands.get(currentBandIdx).getMinMhz();
            }
            trackToBand.put(entry.getKey(), bands.get(currentBandIdx).getId());
        }
        return trackToBand;
    }

    public static double dominantFrequencyMhz(BearingTrack track) {
        double[] sorted = track.getObservations().stream()
                .mapToDouble(TrackObservation::getFrequencyMhz)
                .sorted()
                .toArray();
        if (sorted.length == 0) {
            return 0;
        }
        return sorted[sorted.length / 2];
    }

    public static double clusterFrequencyMhz(List<DetectionPoint> points) {
        double[] sorted = points.stream()
                .mapToDouble(DetectionPoint::getFrequencyMhz)
                .sorted()
                .toArray();
        if (sorted.length == 0) {
            return 0;
        }
        return sorted[sorted.length / 2];
    }
}
