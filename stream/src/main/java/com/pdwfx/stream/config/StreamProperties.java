package com.pdwfx.stream.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "stream")
public class StreamProperties {

    private final Tcp tcp = new Tcp();
    private final Batch batch = new Batch();
    private final Analyze analyze = new Analyze();
    private final Cors cors = new Cors();

    public Tcp getTcp() { return tcp; }
    public Batch getBatch() { return batch; }
    public Analyze getAnalyze() { return analyze; }
    public Cors getCors() { return cors; }

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

        public int getDurationMinutes() { return durationMinutes; }
        public void setDurationMinutes(int durationMinutes) { this.durationMinutes = durationMinutes; }
        public String getDir() { return dir; }
        public void setDir(String dir) { this.dir = dir; }
        public int getFlushEveryRows() { return flushEveryRows; }
        public void setFlushEveryRows(int flushEveryRows) { this.flushEveryRows = flushEveryRows; }
        public int getMaxQueueFiles() { return maxQueueFiles; }
        public void setMaxQueueFiles(int maxQueueFiles) { this.maxQueueFiles = maxQueueFiles; }
    }

    public static class Analyze {
        private boolean enabled = true;
        private String baseUrl = "http://localhost:18080";
        private double freqTolerance = 0.01;
        private boolean preloadAll = true;
        private int workers = 1;

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
    }

    public static class Cors {
        private String allowedOrigins = "*";

        public String getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(String allowedOrigins) { this.allowedOrigins = allowedOrigins; }
    }
}
