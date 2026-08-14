package com.pdwfx.stream.tcp;

import com.pdwfx.stream.k187.K187B108Parser;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * TCP 粘包 / 拆包。
 * <ul>
 *   <li>同步标识：PDWHead3.packHead = {@code 0x7E8118E7}（小端字节 {@code E7 18 81 7E}）</li>
 *   <li>包长：Head3.curPackLen（偏移 +4，含 48 字节指令头本身）</li>
 *   <li>不做 Head1；对端流以 Head3 起头</li>
 * </ul>
 */
public class FrameAssembler {

    private final ByteArrayOutputStream pending = new ByteArrayOutputStream(64 * 1024);
    private long syncLoss;
    private long magicHits;
    private long badLengthSkips;
    private int lastSeenPacketLen = -1;
    private int pendingSize;

    public long getSyncLoss() { return syncLoss; }
    public long getMagicHits() { return magicHits; }
    public long getBadLengthSkips() { return badLengthSkips; }
    public int getLastSeenPacketLen() { return lastSeenPacketLen; }
    public int getPendingSize() { return pendingSize; }

    /**
     * 喂入一段 TCP 字节，返回本段内切出的完整包（每个元素从 packHead 开始）。
     */
    public synchronized List<byte[]> feed(byte[] chunk, int len) {
        List<byte[]> packets = new ArrayList<>();
        if (chunk == null || len <= 0) {
            return packets;
        }
        pending.write(chunk, 0, len);
        byte[] all = pending.toByteArray();
        int offset = 0;
        while (offset + K187B108Parser.HEAD3_LEN <= all.length) {
            int magicPos = findHead3Magic(all, offset);
            if (magicPos < 0) {
                // 找不到头：丢掉已扫描噪声，保留末尾最多 3 字节以防魔数跨 chunk
                int keep = Math.min(3, Math.max(0, all.length - offset));
                int from = Math.max(offset, all.length - keep);
                byte[] rem = new byte[all.length - from];
                System.arraycopy(all, from, rem, 0, rem.length);
                pending.reset();
                if (rem.length > 0) {
                    pending.write(rem, 0, rem.length);
                }
                pendingSize = rem.length;
                return packets;
            }
            if (magicPos > offset) {
                syncLoss += (magicPos - offset);
                offset = magicPos;
            }
            if (offset + K187B108Parser.HEAD3_LEN > all.length) {
                break;
            }
            magicHits++;
            // Head3: packHead(4) + curPackLen(4) + ...
            int packetLen = readU32(all, offset + 4);
            lastSeenPacketLen = packetLen;
            if (packetLen < K187B108Parser.HEAD3_LEN || packetLen > 8 * 1024 * 1024) {
                // 非法长度：滑 1 字节继续找下一个头
                offset += 1;
                syncLoss++;
                badLengthSkips++;
                continue;
            }
            if (offset + packetLen > all.length) {
                // 半包，等后续 TCP 数据
                break;
            }
            byte[] pkt = new byte[packetLen];
            System.arraycopy(all, offset, pkt, 0, packetLen);
            packets.add(pkt);
            offset += packetLen;
        }
        int remain = all.length - offset;
        pending.reset();
        if (remain > 0) {
            pending.write(all, offset, remain);
        }
        pendingSize = remain;
        return packets;
    }

    /** 小端：0x7E8118E7 → E7 18 81 7E */
    private static int findHead3Magic(byte[] data, int from) {
        for (int i = from; i + 4 <= data.length; i++) {
            if ((data[i] & 0xff) == 0xE7
                    && (data[i + 1] & 0xff) == 0x18
                    && (data[i + 2] & 0xff) == 0x81
                    && (data[i + 3] & 0xff) == 0x7E) {
                return i;
            }
        }
        return -1;
    }

    private static int readU32(byte[] data, int off) {
        return ByteBuffer.wrap(data, off, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }
}
