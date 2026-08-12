package com.pdwfx.stream.batch;

import com.pdwfx.stream.config.StreamProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class StreamBatchQueue {

    private final BlockingQueue<Path> queue;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final int maxQueueFiles;

    public StreamBatchQueue(StreamProperties properties) {
        this.maxQueueFiles = Math.max(1, properties.getBatch().getMaxQueueFiles());
        this.queue = new LinkedBlockingQueue<>(maxQueueFiles);
    }

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
