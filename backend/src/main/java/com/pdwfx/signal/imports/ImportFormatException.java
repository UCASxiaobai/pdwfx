package com.pdwfx.signal.imports;

/**
 * CSV 格式与已知模板不符，或误将外源定位文件当作 PDW 输入。
 */
public class ImportFormatException extends IllegalArgumentException {

    private final ImportFormat detectedFormat;
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
