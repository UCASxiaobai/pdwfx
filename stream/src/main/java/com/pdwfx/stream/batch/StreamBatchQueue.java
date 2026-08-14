package com.pdwfx.stream.batch;

import com.pdwfx.stream.config.StreamProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 已封存 CSV 的分析任务队列。
 * <p>{@link StreamBatchFileWriter} 封批后 {@link #offer}；
 * {@link com.pdwfx.stream.analyze.StreamBatchAnalyzer} 轮询 {@link #poll} 并串行分析。
 * 队列满时 {@link #isPaused()} 为 true，TCP 侧暂停读包以免堆积。</p>
 */
@Component
public class StreamBatchQueue {

    private final BlockingQueue<Path> queue;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final int maxQueueFiles;

    public StreamBatchQueue(StreamProperties properties) {
        this.maxQueueFiles = Math.max(1, properties.getBatch().getMaxQueueFiles());
        this.queue = new LinkedBlockingQueue<>(maxQueueFiles);
    }

    /** 封批完成后入队；失败则标记暂停收包 */
    public boolean offer(Path file) {
        boolean ok = queue.offer(file);
        if (!ok) {
            paused.set(true);
        }
        return ok;
    }

    public Path poll(long timeoutMs) throws InterruptedException {
        Path p = queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (p != null && queue.size() < maxQueueFiles / 2) {
            paused.set(false);
        }
        return p;
    }

    public int size() { return queue.size(); }
    public boolean isPaused() { return paused.get(); }
    public int getMaxQueueFiles() { return maxQueueFiles; }
}
