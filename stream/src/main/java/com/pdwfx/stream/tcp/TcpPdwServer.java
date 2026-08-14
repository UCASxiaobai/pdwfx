package com.pdwfx.stream.tcp;

import com.pdwfx.stream.batch.StreamBatchFileWriter;
import com.pdwfx.stream.batch.StreamBatchQueue;
import com.pdwfx.stream.config.StreamProperties;
import com.pdwfx.stream.k187.K187B108Parser;
import com.pdwfx.stream.model.PdwRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TCP 服务端：收包 → 组帧(Head3) → 解析 → 落盘切批。
 * <pre>
 *   对端 TCP → FrameAssembler(0x7E8118E7) → K187B108Parser
 *            → StreamBatchFileWriter（数据时间满 5 分钟封批）
 *            → StreamBatchQueue → StreamBatchAnalyzer（自动调 backend 分析）
 * </pre>
 */
@Component
public class TcpPdwServer {

    private static final Logger log = LoggerFactory.getLogger(TcpPdwServer.class);

    private final StreamProperties properties;
    private final StreamBatchFileWriter batchWriter;
    private final StreamBatchQueue batchQueue;
    private final K187B108Parser parser = new K187B108Parser();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong bytesIn = new AtomicLong();
    private final AtomicLong packetsIn = new AtomicLong();
    private final AtomicLong syncLoss = new AtomicLong();
    private final AtomicLong magicHits = new AtomicLong();
    private final AtomicLong badLengthSkips = new AtomicLong();
    private volatile int lastSeenPacketLen = -1;
    private volatile int pendingSize;
    private volatile String firstChunkHex = "";
    private volatile String recentChunkHex = "";
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private ExecutorService clientPool;

    public TcpPdwServer(StreamProperties properties,
                        StreamBatchFileWriter batchWriter,
                        StreamBatchQueue batchQueue) {
        this.properties = properties;
        this.batchWriter = batchWriter;
        this.batchQueue = batchQueue;
    }

    @PostConstruct
    public void start() {
        if (!properties.getTcp().isEnabled()) {
            log.info("TCP stream server disabled");
            return;
        }
        clientPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "stream-tcp-client");
            t.setDaemon(true);
            return t;
        });
        running.set(true);
        acceptThread = new Thread(this::acceptLoop, "stream-tcp-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) { }
        }
        if (clientPool != null) {
            clientPool.shutdownNow();
        }
        batchWriter.sealIfOpen();
    }

    public boolean isRunning() { return running.get() && serverSocket != null && !serverSocket.isClosed(); }
    public int getListenPort() { return properties.getTcp().getPort(); }
    public long getBytesIn() { return bytesIn.get(); }
    public long getPacketsIn() { return packetsIn.get(); }
    public long getSyncLoss() { return syncLoss.get(); }
    public long getMagicHits() { return magicHits.get(); }
    public long getBadLengthSkips() { return badLengthSkips.get(); }
    public int getLastSeenPacketLen() { return lastSeenPacketLen; }
    public int getPendingSize() { return pendingSize; }
    public String getFirstChunkHex() { return firstChunkHex; }
    public String getRecentChunkHex() { return recentChunkHex; }
    public K187B108Parser.ParseStats getParseStats() { return parser.getStats(); }
    public K187B108Parser.Head3Snapshot getLastHead3Snapshot() { return parser.getLastHead3(); }

    private void acceptLoop() {
        try {
            InetAddress bind = InetAddress.getByName(properties.getTcp().getBind());
            serverSocket = new ServerSocket(properties.getTcp().getPort(), 50, bind);
            serverSocket.setSoTimeout(2000);
            log.info("TCP PDW server listening on {}:{}", properties.getTcp().getBind(), properties.getTcp().getPort());
            while (running.get()) {
                try {
                    Socket socket = serverSocket.accept();
                    clientPool.submit(() -> handleClient(socket));
                } catch (SocketTimeoutException ignored) {
                    // loop
                }
            }
        } catch (Exception e) {
            if (running.get()) {
                log.error("TCP server failed: {}", e.getMessage(), e);
            }
        }
    }

    private void handleClient(Socket socket) {
        String remote = String.valueOf(socket.getRemoteSocketAddress());
        log.info("TCP client connected: {} (frame=Head3 0x7E8118E7)", remote);
        FrameAssembler assembler = new FrameAssembler();
        byte[] buf = new byte[64 * 1024];
        boolean loggedFirstChunk = false;
        try (Socket s = socket; InputStream in = s.getInputStream()) {
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            while (running.get() && !s.isClosed()) {
                if (batchQueue.isPaused()) {
                    Thread.sleep(200);
                    continue;
                }
                int n = in.read(buf);
                if (n < 0) {
                    break;
                }
                bytesIn.addAndGet(n);
                if (!loggedFirstChunk) {
                    firstChunkHex = toHex(buf, Math.min(n, 64));
                    log.warn("TCP first chunk from {} ({} bytes), hex[0..{}]={}",
                            remote, n, Math.min(n, 64) - 1, firstChunkHex);
                    loggedFirstChunk = true;
                }
                if (packetsIn.get() == 0 && (bytesIn.get() < 256 * 1024 || bytesIn.get() % (1024 * 1024) < n)) {
                    recentChunkHex = toHex(buf, Math.min(n, 64));
                }
                List<byte[]> packets = assembler.feed(buf, n);
                syncLoss.set(assembler.getSyncLoss());
                magicHits.set(assembler.getMagicHits());
                badLengthSkips.set(assembler.getBadLengthSkips());
                lastSeenPacketLen = assembler.getLastSeenPacketLen();
                pendingSize = assembler.getPendingSize();
                for (byte[] pkt : packets) {
                    packetsIn.incrementAndGet();
                    List<PdwRecord> records = parser.parsePacket(pkt);
                    if (records.isEmpty()) {
                        continue;
                    }
                    for (PdwRecord r : records) {
                        batchWriter.accept(r);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("TCP client {} closed: {}", remote, e.toString());
        } finally {
            log.info("TCP client disconnected: {}", remote);
        }
    }

    private static String toHex(byte[] data, int len) {
        StringBuilder sb = new StringBuilder(len * 3);
        for (int i = 0; i < len; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02X", data[i] & 0xff));
        }
        return sb.toString();
    }
}
