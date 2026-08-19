package com.pdwfx.signal.imports;

/**
 * 已知 CSV 导入格式。
 */
public enum ImportFormat {
    /** 标准英文字段：FREQ, AZIMUTH, DETECT_TIME … */
    STANDARD,
    /** PDW 侦获表：pl / xhfw / zcsj（或 ZBXH + XHFW …） */
    PDW_TABLE,
    /** 外源目标定位（雷情导入）：detectTime + longitude + latitude */
    EXTERNAL_TARGET_LOCATE,
    /** 表头无法识别为上述任一已知格式 */
    UNKNOWN
}
