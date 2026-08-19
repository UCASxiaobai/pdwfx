package com.pdwfx.signal.model;

import java.time.LocalDateTime;

/**
 * 信号分析域内的单条侦获记录（统一内部模型）。
 * <p>
 * 可由 CSV/Excel（{@link com.pdwfx.signal.service.ExcelImportService}）、
 * JSON DTO（{@link com.pdwfx.signal.api.SignalInputMapper}）或流式落盘表导入填充。
 * </p>
 */
public class DetectSignal {

    /** 记录主键或行号标识；无则导入时生成如 ROW-n */
    private String id;

    /** 源数据文件 ID（若上游提供） */
    private String dataFileId;

    /** 数据类型编码（若上游提供） */
    private String dataType;

    /** 定位平台 ID（若上游提供） */
    private String locPlatId;

    /** 天线选择/通道标识（若上游提供） */
    private String antennaSelect;

    /** 侦获时刻（本地日期时间）；与 {@link #detectTimesss} 对应 */
    private LocalDateTime detectTime;

    /** 侦获时刻 Unix 毫秒时间戳；分析主流程多用此字段排序 */
    private long detectTimesss;

    /**
     * 单条 PDW 驻留时间（ms）。
     * 来自 nSignalTime / nSingnalTime（单位 10µs）× 0.01；节奏/占空比分析输入。
     */
    private double signalDwellMs;

    /** 载频，单位 MHz；表格列 pl / FREQ */
    private double freq;

    /** 信号带宽；表格列 xhbk / SIGNAL_BW 等，单位与源表一致（多为 kHz） */
    private double signalBw;

    /** 信号幅度，单位 dB；表格列 xhfd / SIGNAL_LEVEL */
    private double signalLevel;

    /** 调制样式文字描述（若有） */
    private String modulateStyle;

    /** 调制维数/维度描述（若有） */
    private String modulateDimension;

    /** 码速率等（若有） */
    private double bitRate;

    /** 测向方位角，单位 °；表格列 xhfw / AZIMUTH */
    private double azimuth;

    /** 相对方位（若有） */
    private double relAzimuth;

    /** 信噪比（若有） */
    private double snr;

    /** 测站/平台经度 °（传感器位置）；表格列 zjwzjd 等 */
    private Double longitude;

    /** 测站/平台纬度 °（传感器位置） */
    private Double latitude;

    /**
     * 外源/表内目标定位经度 °（DWJD）。
     * 供 {@link com.pdwfx.signal.service.MotionClassificationService} 误差椭圆使用。
     */
    private Double targetLon;

    /**
     * 外源/表内目标定位纬度 °（DWD）。
     * 供误差椭圆固定/移动判定使用。
     */
    private Double targetLat;

    /** 设备 ID（若有） */
    private String equipId;

    /** 处理状态等业务标记（若有，源列 clzt） */
    private String clzt;

    /** 场景建轨 track_id（轮询转发 CSV 的 track_id 列）；无则不按轨分目标 */
    private Integer sceneTrackId;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDataFileId() { return dataFileId; }
    public void setDataFileId(String dataFileId) { this.dataFileId = dataFileId; }
    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }
    public String getLocPlatId() { return locPlatId; }
    public void setLocPlatId(String locPlatId) { this.locPlatId = locPlatId; }
    public String getAntennaSelect() { return antennaSelect; }
    public void setAntennaSelect(String antennaSelect) { this.antennaSelect = antennaSelect; }
    public LocalDateTime getDetectTime() { return detectTime; }
    public void setDetectTime(LocalDateTime detectTime) { this.detectTime = detectTime; }
    public long getDetectTimesss() { return detectTimesss; }
    public void setDetectTimesss(long detectTimesss) { this.detectTimesss = detectTimesss; }
    public double getSignalDwellMs() { return signalDwellMs; }
    public void setSignalDwellMs(double signalDwellMs) { this.signalDwellMs = signalDwellMs; }
    public double getFreq() { return freq; }
    public void setFreq(double freq) { this.freq = freq; }
    public double getSignalBw() { return signalBw; }
    public void setSignalBw(double signalBw) { this.signalBw = signalBw; }
    public double getSignalLevel() { return signalLevel; }
    public void setSignalLevel(double signalLevel) { this.signalLevel = signalLevel; }
    public String getModulateStyle() { return modulateStyle; }
    public void setModulateStyle(String modulateStyle) { this.modulateStyle = modulateStyle; }
    public String getModulateDimension() { return modulateDimension; }
    public void setModulateDimension(String modulateDimension) { this.modulateDimension = modulateDimension; }
    public double getBitRate() { return bitRate; }
    public void setBitRate(double bitRate) { this.bitRate = bitRate; }
    public double getAzimuth() { return azimuth; }
    public void setAzimuth(double azimuth) { this.azimuth = azimuth; }
    public double getRelAzimuth() { return relAzimuth; }
    public void setRelAzimuth(double relAzimuth) { this.relAzimuth = relAzimuth; }
    public double getSnr() { return snr; }
    public void setSnr(double snr) { this.snr = snr; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getTargetLon() { return targetLon; }
    public void setTargetLon(Double targetLon) { this.targetLon = targetLon; }
    public Double getTargetLat() { return targetLat; }
    public void setTargetLat(Double targetLat) { this.targetLat = targetLat; }
    public String getEquipId() { return equipId; }
    public void setEquipId(String equipId) { this.equipId = equipId; }
    public String getClzt() { return clzt; }
    public void setClzt(String clzt) { this.clzt = clzt; }
    public Integer getSceneTrackId() { return sceneTrackId; }
    public void setSceneTrackId(Integer sceneTrackId) { this.sceneTrackId = sceneTrackId; }
}
