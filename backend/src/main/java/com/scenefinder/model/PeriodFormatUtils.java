package com.scenefinder.model;

import java.util.Locale;

/** 将秒级时长格式化为「X秒 Y毫秒 Z微秒」。 */
public final class PeriodFormatUtils {

    private PeriodFormatUtils() {
    }

    public static String formatSecMsUs(double periodSec) {
        if (!Double.isFinite(periodSec) || periodSec < 0) {
            return "—";
        }
        long totalMicros = Math.round(periodSec * 1_000_000.0);
        long sec = totalMicros / 1_000_000L;
        long ms = (totalMicros % 1_000_000L) / 1000L;
        long us = totalMicros % 1000L;
        return String.format(Locale.CHINA, "%d秒 %d毫秒 %d微秒", sec, ms, us);
    }
}
