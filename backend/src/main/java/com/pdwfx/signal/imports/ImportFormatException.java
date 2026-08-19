package com.pdwfx.signal.imports;

/**
 * CSV 格式与已知模板不符，或误将外源定位文件当作 PDW 输入。
 */
public class ImportFormatException extends IllegalArgumentException {

    /** 检测器识别出的格式（可能为 UNKNOWN 或误用的 EXTERNAL_TARGET_LOCATE） */
    private final ImportFormat detectedFormat;
    /** 出问题的文件名（展示用） */
    private final String fileName;

    public ImportFormatException(String message, ImportFormat detectedFormat, String fileName) {
        super(message);
        this.detectedFormat = detectedFormat;
        this.fileName = fileName;
    }

    public ImportFormat getDetectedFormat() {
        return detectedFormat;
    }

    public String getFileName() {
        return fileName;
    }
}
