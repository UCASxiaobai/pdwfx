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
 * 流式落盘切批：按<strong>解析出的数据时间</strong>（非墙钟）滚动。
 * <p>
 * 流程：
 * <ol>
 *   <li>TCP 解析得到 {@link PdwRecord} 后调用 {@link #accept}</li>
 *   <li>本批内按频点+方位+探测时间去重后写入 CSV（目录 {@code open/}）</li>
 *   <li>当「本批首点时间 → 当前点时间」跨越 {@code stream.batch.duration-minutes} 时：
 *       封存当前文件到 {@code inbox/}，并 {@code queue.offer} 通知分析线程</li>
 *   <li>跨越边界的那条点写入<strong>新一批</strong>，作为下一轮分析素材</li>
 * </ol>
 */
@Component
public class StreamBatchFileWriter {

    private static final Logger log = LoggerFactory.getLogger(StreamBatchFileWriter.class);
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter CSV_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    /**
     * 场景筛选要求 PDW 表（pl/xhfw/zcsj），不能用 STANDARD（FREQ/AZIMUTH）。
     * 附带驻留列供 {@code NSignalTimeColumns.isValidSceneInput} 过滤。
     */
    private static final String HEADER =
            "pl,xhfw,zcsj,xhfd,gmdk,zjwzjd,zjwzwd,nSignalStartTime,nSignalTime\n";

    private final StreamProperties properties;
    private final StreamBatchQueue queue;

    private Path root;
    private Path inbox;
    private Path openDir;

    private final Object lock = new Object();
    /** 本批第一点的数据时间（切批边界基准） */
    private LocalDateTime batchStart;
    private Path openFile;
    private BufferedWriter writer;
    private int rowsInBatch;
    private int rowsSinceFlush;
    /** 当前打开批内已写入点的去重键 */
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
        log.info("Stream batch dirs ready under {} (durationMinutes={}, dedup={})",
                root, properties.getBatch().getDurationMinutes(),
                properties.getBatch().isDedupEnabled());
    }

    public Path getRoot() { return root; }
    public long getSealedCount() { return sealedCount.get(); }
    public long getWrittenRows() { return writtenRows.get(); }
    public long getSkippedDupRows() { return skippedDupRows.get(); }

    /**
     * 接收一条解析结果：必要时先封批触发分析，再写入（可能写入新批）。
     */
    public void accept(PdwRecord record) throws IOException {
        if (record == null || record.getDetectTime() == null) {
            return;
        }
        synchronized (lock) {
            int durationMin = Math.max(1, properties.getBatch().getDurationMinutes());
            if (batchStart == null) {
                // 新会话 / 上一批刚封完：以本点时间为新批起点
                openNewBatch(record.getDetectTime());
            } else if (!record.getDetectTime().isBefore(batchStart.plusMinutes(durationMin))) {
                // 数据时间已跨越 durationMin 分钟 → 封存当前批并入分析队列，后续点归下一批
                log.info("数据时间跨越 {} 分钟，封批并触发分析: batchStart={} triggerPoint={} rows={}",
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

    /** 进程退出时封存未满的打开批（仍会入队分析，若有数据） */
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
        seenKeys.clear();
        String name = "batch_" + FILE_TS.format(t0) + "_" + System.currentTimeMillis() + ".csv.open";
        openFile = openDir.resolve(name);
        writer = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(openFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING),
                StandardCharsets.UTF_8));
        writer.write(HEADER);
        log.info("Opened stream batch file {} (batchStart={})", openFile.getFileName(), batchStart);
    }

    /** 与单包解析去重一致：频点 + 方位 + 探测时间（毫秒） */
    private static String dedupKey(PdwRecord r) {
        long ms = r.getDetectTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        // 量化避免 double 字符串抖动导致漏去重
        long freqMilli = Math.round(r.getFreqMhz() * 1000.0);
        long azMilli = Math.round(r.getAzimuthDeg() * 1000.0);
        return freqMilli + "|" + azMilli + "|" + ms;
    }

    private void writeRowLocked(PdwRecord r) throws IOException {
        // pl=频点MHz, xhfw=方位°, zcsj=探测时间, xhfd=电平, gmdk=带宽kHz,
        // zjwzjd/zjwzwd=经纬度, nSignal*=10µs 计数（与 PrcFf / 场景筛选一致）
        String line = String.format(
                Locale.US,
                "%.6f,%.3f,%s,%.1f,%.3f,%.6f,%.6f,%d,%d%n",
                r.getFreqMhz(),
                r.getAzimuthDeg(),
                CSV_TS.format(r.getDetectTime()),
                r.getSignalLevelDb(),
                r.getSignalBwKhz(),
                r.getLongitude(),
                r.getLatitude(),
                r.getNSignalStartTime10us(),
                r.getNSignalTime10us()
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

    /**
     * 封存 open 文件 → inbox，并放入 {@link StreamBatchQueue}，由分析线程自动拉取。
     */
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
        rowsInBatch = 0;
        seenKeys.clear();
    }
}
