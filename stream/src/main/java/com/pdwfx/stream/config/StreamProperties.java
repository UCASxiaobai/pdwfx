package com.pdwfx.stream.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "stream")
public class StreamProperties {

    private final Tcp tcp = new Tcp();
    private final Batch batch = new Batch();
    private final Analyze analyze = new Analyze();
    private final Cors cors = new Cors();
    private final Display display = new Display();

    public Tcp getTcp() { return tcp; }
    public Batch getBatch() { return batch; }
    public Analyze getAnalyze() { return analyze; }
    public Cors getCors() { return cors; }
    public Display getDisplay() { return display; }

    public static class Tcp {
        private boolean enabled = true;
        private int port = 19090;
        private String bind = "0.0.0.0";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getBind() { return bind; }
        public void setBind(String bind) { this.bind = bind; }
    }

    public static class Batch {
        private int durationMinutes = 5;
        private String dir = "./stream-data";
        private int flushEveryRows = 200;
        private int maxQueueFiles = 24;
        /** 落盘前按频点+方位+探测时间去重（本批内），减小后续场景/信号分析量 */
        private boolean dedupEnabled = true;

        public int getDurationMinutes() { return durationMinutes; }
        public void setDurationMinutes(int durationMinutes) { this.durationMinutes = durationMinutes; }
        public String getDir() { return dir; }
        public void setDir(String dir) { this.dir = dir; }
        public int getFlushEveryRows() { return flushEveryRows; }
        public void setFlushEveryRows(int flushEveryRows) { this.flushEveryRows = flushEveryRows; }
        public int getMaxQueueFiles() { return maxQueueFiles; }
        public void setMaxQueueFiles(int maxQueueFiles) { this.maxQueueFiles = maxQueueFiles; }
        public boolean isDedupEnabled() { return dedupEnabled; }
        public void setDedupEnabled(boolean dedupEnabled) { this.dedupEnabled = dedupEnabled; }
    }

    public static class Analyze {
        private boolean enabled = true;
        private String baseUrl = "http://localhost:18080";
        private double freqTolerance = 0.01;
        /** 预加载全部网络详情（含目标类型、波道研判），与主流程「加载全部网络详情」一致 */
        private boolean preloadAll = true;
        private int workers = 1;
        private final Scene scene = new Scene();
        private final Annotation annotation = new Annotation();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public double getFreqTolerance() { return freqTolerance; }
        public void setFreqTolerance(double freqTolerance) { this.freqTolerance = freqTolerance; }
        public boolean isPreloadAll() { return preloadAll; }
        public void setPreloadAll(boolean preloadAll) { this.preloadAll = preloadAll; }
        public int getWorkers() { return workers; }
        public void setWorkers(int workers) { this.workers = workers; }
        public Scene getScene() { return scene; }
        public Annotation getAnnotation() { return annotation; }
    }

    /** 场景筛选参数（流式默认全段窗；window-seconds 仅 full-span-window=false 时备用） */
    public static class Scene {
        private Double freqMin;
        private Double freqMax;
        private double windowSeconds = 120;
        private Double windowStepSeconds;
        private int topKTrackScenes = 50;
        private int topKPollingScenes = 50;
        private Integer minTracksInScene;
        private Integer topKScenes;
        /** 每频段只评本批实际 [min,max] 一次（连续+轮询），默认开启 */
        private boolean fullSpanWindow = true;

        public Double getFreqMin() { return freqMin; }
        public void setFreqMin(Double freqMin) { this.freqMin = freqMin; }
        public Double getFreqMax() { return freqMax; }
        public void setFreqMax(Double freqMax) { this.freqMax = freqMax; }
        public double getWindowSeconds() { return windowSeconds; }
        public void setWindowSeconds(double windowSeconds) { this.windowSeconds = windowSeconds; }
        public Double getWindowStepSeconds() { return windowStepSeconds; }
        public void setWindowStepSeconds(Double windowStepSeconds) { this.windowStepSeconds = windowStepSeconds; }
        public int getTopKTrackScenes() { return topKTrackScenes; }
        public void setTopKTrackScenes(int topKTrackScenes) { this.topKTrackScenes = topKTrackScenes; }
        public int getTopKPollingScenes() { return topKPollingScenes; }
        public void setTopKPollingScenes(int topKPollingScenes) { this.topKPollingScenes = topKPollingScenes; }
        public Integer getMinTracksInScene() { return minTracksInScene; }
        public void setMinTracksInScene(Integer minTracksInScene) { this.minTracksInScene = minTracksInScene; }
        public Integer getTopKScenes() { return topKScenes; }
        public void setTopKScenes(Integer topKScenes) { this.topKScenes = topKScenes; }
        public boolean isFullSpanWindow() { return fullSpanWindow; }
        public void setFullSpanWindow(boolean fullSpanWindow) { this.fullSpanWindow = fullSpanWindow; }
    }

    public static class Annotation {
        /** 相对 batch.dir 或绝对路径；空则用 {batch.dir}/annotations */
        private String dir = "";

        public String getDir() { return dir; }
        public void setDir(String dir) { this.dir = dir; }
    }

    /** 显示软件推送（本期仅占位，空则不推） */
    public static class Display {
        private String pushUrl = "";

        public String getPushUrl() { return pushUrl; }
        public void setPushUrl(String pushUrl) { this.pushUrl = pushUrl; }
    }

    public static class Cors {
        private String allowedOrigins = "*";

        public String getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(String allowedOrigins) { this.allowedOrigins = allowedOrigins; }
    }
}
