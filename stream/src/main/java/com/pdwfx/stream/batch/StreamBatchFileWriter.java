package com.pdwfx.stream.batch;

import com.pdwfx.stream.config.StreamProperties;
import com.pdwfx.stream.model.PdwRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 按数据时间滚动切批并追加写 STANDARD CSV（供原 backend /api/signals/analyze 直接导入）。
 */
@Component
public class StreamBatchFileWriter {

    private static final Logger log = LoggerFactory.getLogger(StreamBatchFileWriter.class);
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter CSV_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final String HEADER =
            "FREQ,AZIMUTH,DETECT_TIME,SIGNAL_LEVEL,SIGNAL_BW,LONGITUDE,LATITUDE,DETECT_TIMESSS\n";

    private final StreamProperties properties;
    private final StreamBatchQueue queue;

    private Path root;
    private Path inbox;
    private Path openDir;

    private final Object lock = new Object();
    private LocalDateTime batchStart;
    private Path openFile;
    private BufferedWriter writer;
    private int rowsInBatch;
    private int rowsSinceFlush;
    private final AtomicLong sealedCount = new AtomicLong();
    private final AtomicLong writtenRows = new AtomicLong();

    public StreamBatchFileWriter(StreamProperties properties, StreamBatchQueue queue) {
        this.properties = properties;
        this.queue = queue;
    }

    @PostConstruct
    public void init() throws IOException {
        root = Paths.get(properties.getBatch().getDir()).toAbsolutePath().normalize();
        inbox = root.resolve("inbox");
        openDir = root.resolve("open");
        Files.createDirectories(inbox);
        Files.createDirectories(openDir);
        Files.createDirectories(root.resolve("processing"));
        Files.createDirectories(root.resolve("done"));
        Files.createDirectories(root.resolve("failed"));
        log.info("Stream batch dirs ready under {}", root);
    }

    public Path getRoot() { return root; }
    public long getSealedCount() { return sealedCount.get(); }
    public long getWrittenRows() { return writtenRows.get(); }

    public void accept(PdwRecord record) throws IOException {
        if (record == null || record.getDetectTime() == null) {
            return;
        }
        synchronized (lock) {
            if (batchStart == null) {
                openNewBatch(record.getDetectTime());
            } else {
                long minutes = java.time.Duration.between(batchStart, record.getDetectTime()).toMinutes();
                if (minutes >= properties.getBatch().getDurationMinutes()) {
                    sealCurrentLocked();
                    openNewBatch(record.getDetectTime());
                }
            }
            writeRowLocked(record);
        }
    }

    public void sealIfOpen() {
        synchronized (lock) {
            try {
                sealCurrentLocked();
            } catch (IOException e) {
                log.warn("seal open batch failed: {}", e.getMessage());
            }
        }
    }

    private void openNewBatch(LocalDateTime t0) throws IOException {
        batchStart = t0;
        rowsInBatch = 0;
        rowsSinceFlush = 0;
        String name = "batch_" + FILE_TS.format(t0) + "_" + System.currentTimeMillis() + ".csv.open";
        openFile = openDir.resolve(name);
        writer = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(openFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING),
                StandardCharsets.UTF_8));
        writer.write(HEADER);
        log.info("Opened stream batch file {}", openFile.getFileName());
    }

    private void writeRowLocked(PdwRecord r) throws IOException {
        long ms = r.getDetectTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        String line = String.format(
                Locale.US,
                "%.6f,%.3f,%s,%.1f,%.3f,%.6f,%.6f,%d%n",
                r.getFreqMhz(),
                r.getAzimuthDeg(),
                CSV_TS.format(r.getDetectTime()),
                r.getSignalLevelDb(),
                r.getSignalBwKhz(),
                r.getLongitude(),
                r.getLatitude(),
                ms
        );
        writer.write(line);
        rowsInBatch++;
        rowsSinceFlush++;
        writtenRows.incrementAndGet();
        int flushEvery = Math.max(1, properties.getBatch().getFlushEveryRows());
        if (rowsSinceFlush >= flushEvery) {
            writer.flush();
            rowsSinceFlush = 0;
        }
    }

    private void sealCurrentLocked() throws IOException {
        if (writer == null || openFile == null) {
            return;
        }
        writer.flush();
        writer.close();
        writer = null;
        if (rowsInBatch <= 0) {
            Files.deleteIfExists(openFile);
            openFile = null;
            batchStart = null;
            return;
        }
        String sealedName = openFile.getFileName().toString().replace(".csv.open", ".csv");
        Path target = inbox.resolve(sealedName);
        Files.move(openFile, target);
        queue.offer(target);
        sealedCount.incrementAndGet();
        log.info("Sealed batch {} rows={} -> inbox", sealedName, rowsInBatch);
        openFile = null;
        batchStart = null;
        rowsInBatch = 0;
    }
}
