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
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 流式落盘切批。
 * <ul>
 *   <li>{@code ATTITUDE}：|roll|≤门限时落盘；高横滚持续确认转向后，按开批到当前时刻（含空洞）
 *       满最小时长则封批分析并暂停，否则只丢高横滚点并在转向持续期间复查</li>
 *   <li>{@code DURATION}：仅按数据时间跨越 duration-minutes 封批（可回退）</li>
 * </ul>
 * {@code duration-minutes} 在 ATTITUDE 模式下仍作超长平飞安全阀。
 */
@Component
public class StreamBatchFileWriter {

    private static final Logger log = LoggerFactory.getLogger(StreamBatchFileWriter.class);
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter CSV_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    /**
     * 场景筛选要求 PDW 表（pl/xhfw/zcsj）；附带驻留与姿态审计列。
     */
    private static final String HEADER =
            "pl,xhfw,zcsj,xhfd,gmdk,zjwzjd,zjwzwd,nSignalStartTime,nSignalTime,pitchDeg,rollDeg,courseDeg\n";

    private final StreamProperties properties;
    private final StreamBatchQueue queue;
    private AttitudeStabilityGate attitudeGate;

    private Path root;
    private Path inbox;
    private Path openDir;

    private final Object lock = new Object();
    private LocalDateTime batchStart;
    private LocalDateTime lastWrittenTime;
    private Path openFile;
    private BufferedWriter writer;
    private int rowsInBatch;
    private int rowsSinceFlush;
    private final Set<String> seenKeys = new HashSet<>();
    private final AtomicLong sealedCount = new AtomicLong();
    private final AtomicLong writtenRows = new AtomicLong();
    private final AtomicLong skippedDupRows = new AtomicLong();

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
        attitudeGate = new AttitudeStabilityGate(properties.getBatch());
        log.info("Stream batch dirs ready under {} (sealMode={}, durationMinutes={}, dedup={})",
                root, properties.getBatch().getSealMode(),
                properties.getBatch().getDurationMinutes(),
                properties.getBatch().isDedupEnabled());
    }

    public Path getRoot() { return root; }
    public long getSealedCount() { return sealedCount.get(); }
    public long getWrittenRows() { return writtenRows.get(); }
    public long getSkippedDupRows() { return skippedDupRows.get(); }
    public String getAttitudeState() {
        return attitudeGate == null ? "—" : attitudeGate.getState().name();
    }
    public long getDroppedManeuverRows() {
        return attitudeGate == null ? 0L : attitudeGate.getDroppedManeuverCount();
    }

    public void accept(PdwRecord record) throws IOException {
        if (record == null || record.getDetectTime() == null) {
            return;
        }
        synchronized (lock) {
            long epochMs = record.getDetectTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            boolean attitudeMode = properties.getBatch().isAttitudeSealMode();
            boolean enteredManeuver = false;
            if (attitudeMode && attitudeGate != null) {
                enteredManeuver = attitudeGate.update(record.getRollDeg(), epochMs);
            }

            if (attitudeMode && enteredManeuver) {
                long firstMs = batchStart == null ? 0L
                        : batchStart.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                boolean longEnough = batchStart != null && attitudeGate.isBatchLongEnough(firstMs, epochMs);
                if (longEnough && rowsInBatch > 0) {
                    log.info("横滚超门限持续确认转向，封批并暂停落盘: batchStart={} lastWritten={} trigger={} rows={}",
                            batchStart, lastWrittenTime, record.getDetectTime(), rowsInBatch);
                    sealCurrentLocked();
                } else {
                    log.info("横滚超门限持续确认转向，但开批至当前不足最小时长，不触发分析，仅丢弃高横滚点: batchStart={} trigger={} rows={}",
                            batchStart, record.getDetectTime(), rowsInBatch);
                }
            }

            if (attitudeMode && attitudeGate != null && !attitudeGate.shouldAccept()) {
                if (attitudeGate.getState() == AttitudeStabilityGate.State.MANEUVER) {
                    trySealByCollectionSpan(epochMs, record);
                }
                attitudeGate.incrementDropped();
                return;
            }

            int durationMin = Math.max(1, properties.getBatch().getDurationMinutes());
            if (batchStart == null) {
                openNewBatch(record.getDetectTime());
            } else if (!record.getDetectTime().isBefore(batchStart.plusMinutes(durationMin))) {
                log.info("数据时间跨越 {} 分钟（安全阀），封批: batchStart={} triggerPoint={} rows={}",
                        durationMin, batchStart, record.getDetectTime(), rowsInBatch);
                sealCurrentLocked();
                openNewBatch(record.getDetectTime());
            }
            if (properties.getBatch().isDedupEnabled()) {
                String key = dedupKey(record);
                if (!seenKeys.add(key)) {
                    skippedDupRows.incrementAndGet();
                    return;
                }
            }
            writeRowLocked(record);
        }
    }

    /**
     * 转向已确认后，按「开批→当前时刻」复查最小时长；中间高横滚空洞也计入。
     */
    private void trySealByCollectionSpan(long epochMs, PdwRecord record) throws IOException {
        if (batchStart == null || rowsInBatch <= 0 || attitudeGate == null) {
            return;
        }
        long firstMs = batchStart.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        if (!attitudeGate.isBatchLongEnough(firstMs, epochMs)) {
            return;
        }
        log.info("开批至当前已满最小时长且转向持续，封批并暂停落盘: batchStart={} lastWritten={} trigger={} rows={}",
                batchStart, lastWrittenTime, record.getDetectTime(), rowsInBatch);
        sealCurrentLocked();
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

    /**
     * 重启接收：丢弃当前未封存开批（不入分析队列）、清零计数与姿态门控。
     *
     * @return 被丢弃的开批文件名；无开批时为 null
     */
    public String discardOpenAndReset() {
        synchronized (lock) {
            String discarded = null;
            if (writer != null || openFile != null) {
                try {
                    if (writer != null) {
                        try {
                            writer.close();
                        } catch (IOException ignored) {
                            // ignore
                        }
                        writer = null;
                    }
                    if (openFile != null) {
                        discarded = openFile.getFileName().toString();
                        Files.deleteIfExists(openFile);
                        openFile = null;
                    }
                } catch (IOException e) {
                    log.warn("discard open batch failed: {}", e.getMessage());
                }
            }
            batchStart = null;
            lastWrittenTime = null;
            rowsInBatch = 0;
            rowsSinceFlush = 0;
            seenKeys.clear();
            sealedCount.set(0L);
            writtenRows.set(0L);
            skippedDupRows.set(0L);
            if (attitudeGate != null) {
                attitudeGate.reset();
            } else {
                attitudeGate = new AttitudeStabilityGate(properties.getBatch());
            }
            log.info("Batch writer reset for restart (discardedOpen={})", discarded);
            return discarded;
        }
    }

    private void openNewBatch(LocalDateTime t0) throws IOException {
        batchStart = t0;
        lastWrittenTime = null;
        rowsInBatch = 0;
        rowsSinceFlush = 0;
        seenKeys.clear();
        String name = "batch_" + FILE_TS.format(t0) + "_" + System.currentTimeMillis() + ".csv.open";
        openFile = openDir.resolve(name);
        writer = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(openFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING),
                StandardCharsets.UTF_8));
        writer.write(HEADER);
        log.info("Opened stream batch file {} (batchStart={})", openFile.getFileName(), batchStart);
    }

    private static String dedupKey(PdwRecord r) {
        long ms = r.getDetectTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long freqMilli = Math.round(r.getFreqMhz() * 1000.0);
        long azMilli = Math.round(r.getAzimuthDeg() * 1000.0);
        return freqMilli + "|" + azMilli + "|" + ms;
    }

    private void writeRowLocked(PdwRecord r) throws IOException {
        String line = String.format(
                Locale.US,
                "%.6f,%.3f,%s,%.1f,%.3f,%.6f,%.6f,%d,%d,%.3f,%.3f,%.3f%n",
                r.getFreqMhz(),
                r.getAzimuthDeg(),
                CSV_TS.format(r.getDetectTime()),
                r.getSignalLevelDb(),
                r.getSignalBwKhz(),
                r.getLongitude(),
                r.getLatitude(),
                r.getNSignalStartTime10us(),
                r.getNSignalTime10us(),
                r.getPitchDeg(),
                r.getRollDeg(),
                r.getCourseDeg()
        );
        writer.write(line);
        lastWrittenTime = r.getDetectTime();
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
            lastWrittenTime = null;
            return;
        }
        String sealedName = openFile.getFileName().toString().replace(".csv.open", ".csv");
        Path target = inbox.resolve(sealedName);
        Files.move(openFile, target);
        boolean queued = queue.offer(target);
        sealedCount.incrementAndGet();
        if (queued) {
            log.info("Sealed batch {} rows={} -> inbox，已提交分析队列 (queueSize={})",
                    sealedName, rowsInBatch, queue.size());
        } else {
            log.warn("Sealed batch {} rows={} -> inbox，但分析队列已满已暂停收包 (max={})",
                    sealedName, rowsInBatch, queue.getMaxQueueFiles());
        }
        openFile = null;
        batchStart = null;
        lastWrittenTime = null;
        rowsInBatch = 0;
        seenKeys.clear();
    }
}
