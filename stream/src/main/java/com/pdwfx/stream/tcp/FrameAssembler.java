package com.pdwfx.stream.tcp;

import com.pdwfx.stream.k187.K187B108Parser;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * TCP 粘包拆包：寻找 0x7e7e7e7e，按 Head1.ulPacketLength 切出完整包。
 */
public class FrameAssembler {

    private final ByteArrayOutputStream pending = new ByteArrayOutputStream(64 * 1024);
    private long syncLoss;

    public long getSyncLoss() { return syncLoss; }

    public synchronized List<byte[]> feed(byte[] chunk, int len) {
        List<byte[]> packets = new ArrayList<>();
        if (chunk == null || len <= 0) {
            return packets;
        }
        pending.write(chunk, 0, len);
        byte[] all = pending.toByteArray();
        int offset = 0;
        while (offset + K187B108Parser.HEAD1_LEN <= all.length) {
            int magicPos = findMagic(all, offset);
            if (magicPos < 0) {
                // 保留末尾 3 字节以防跨 chunk 魔数
                int keep = Math.min(3, all.length);
                byte[] rem = new byte[keep];
                System.arraycopy(all, all.length - keep, rem, 0, keep);
                pending.reset();
                pending.write(rem, 0, rem.length);
                return packets;
            }
            if (magicPos > offset) {
                syncLoss += (magicPos - offset);
                offset = magicPos;
            }
            if (offset + K187B108Parser.HEAD1_LEN > all.length) {
                break;
            }
            int packetLen = readPacketLength(all, offset);
            if (packetLen < K187B108Parser.HEAD1_LEN || packetLen > 8 * 1024 * 1024) {
                offset += 1;
                syncLoss++;
                continue;
            }
            if (offset + packetLen > all.length) {
                break;
            }
            byte[] pkt = new byte[packetLen];
            System.arraycopy(all, offset, pkt, 0, packetLen);
            packets.add(pkt);
            offset += packetLen;
        }
        // 保留未消费尾部
        int remain = all.length - offset;
        pending.reset();
        if (remain > 0) {
            pending.write(all, offset, remain);
        }
        return packets;
    }

    private static int findMagic(byte[] data, int from) {
        for (int i = from; i + 4 <= data.length; i++) {
            if ((data[i] & 0xff) == 0x7e
                    && (data[i + 1] & 0xff) == 0x7e
                    && (data[i + 2] & 0xff) == 0x7e
                    && (data[i + 3] & 0xff) == 0x7e) {
                return i;
            }
        }
        return -1;
    }

    private static int readPacketLength(byte[] data, int magicOffset) {
        return ByteBuffer.wrap(data, magicOffset + 8, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }
}
