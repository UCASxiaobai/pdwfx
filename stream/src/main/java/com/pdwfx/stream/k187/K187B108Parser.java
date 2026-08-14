package com.pdwfx.stream.k187;

import com.pdwfx.stream.model.PdwRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单包解析（无 PDWHead1）。
 * <pre>
 *   [PDWHead3 48B | 可选填充 | FFHead 80B | FFData 109B × N]
 * </pre>
 * <ol>
 *   <li>校验 Head3.packHead == 0x7E8118E7</li>
 *   <li>解出 Head3 各字段；用 {@code infoType} 决定是否保留（见 {@link #INFO_TYPE_KEEP}）</li>
 *   <li>保留则继续解析 FFHead + N×FFData → {@link PdwRecord}</li>
 * </ol>
 */
public class K187B108Parser {

    private static final Logger log = LoggerFactory.getLogger(K187B108Parser.class);

    /** PDWHead3 结构长度 */
    public static final int HEAD3_LEN = 48;
    /** FFHead_B108H 结构长度 */
    public static final int FF_HEAD_LEN = 80;
    /** FFData_B108H 单条长度 */
    public static final int FF_DATA_LEN = 109;
    /** Head3 报文头标识 packHead */
    public static final int HEAD3_PACK_HEAD = 0x7E8118E7;
    /**
     * 保留的信息类别（Head3.infoType 低 16 位比较）。
     * 对端当前下发为 0xB107；经典定频 CDW 文档值为 0xB108。
     */
    public static final int INFO_TYPE_KEEP = 0xB107;
    /** @deprecated 使用 {@link #INFO_TYPE_KEEP} */
    public static final int INFO_TYPE_B108 = INFO_TYPE_KEEP;

    public static final class ParseStats {
        public long packets;
        public long b108Packets;
        public long records;
        public long skippedDoa;
        public long skippedOtherType;
        public long skippedBadMagic;
        public long skippedBadLength;
        public long head3PackHeadMismatch;
    }

    /** 最近一次 Head3 快照（status / 断点） */
    public static final class Head3Snapshot {
        public int packHead;
        public int curPackLen;
        public int tgtAddr;
        public int srcAddr;
        public int infoType;
        public long infoTime;
        public int seqNo;
        public int packAmount;
        public int packNo;
        public int dataLen;
        public int version;
        public int bufferLength;
        public String infoTimeText;

        @Override
        public String toString() {
            return String.format(Locale.US,
                    "packHead=0x%08X curPackLen=%d infoType=0x%X(%d) seq=%d pack=%d/%d dataLen=%d ver=0x%04X tgt=%d src=%d infoTime=%s buf=%d",
                    packHead, curPackLen, infoType, infoType, seqNo, packNo, packAmount,
                    dataLen, version, tgtAddr, srcAddr, infoTimeText, bufferLength);
        }
    }

    private final ParseStats stats = new ParseStats();
    private final AtomicReference<Head3Snapshot> lastHead3 = new AtomicReference<>();

    public ParseStats getStats() { return stats; }
    public Head3Snapshot getLastHead3() { return lastHead3.get(); }

    public List<PdwRecord> parsePacket(byte[] packet) {
        return parseHead3Packet(packet);
    }

    private List<PdwRecord> parseHead3Packet(byte[] packet) {
        List<PdwRecord> out = new ArrayList<>();
        if (packet == null || packet.length < HEAD3_LEN) {
            return out;
        }
        LittleEndian le = new LittleEndian(packet);

        // ---- PDWHead3（48 字节，与 cet36 _PDWHead3_S_Head 一致）----
        int packHead = le.readU32();       // 报文头，固定 0x7E8118E7
        int curPackLen = le.readU32();     // 当前包长度（含本头）
        int tgtAddr = le.readU16() & 0xffff;
        int srcAddr = le.readU16() & 0xffff;
        int infoType = le.readU32();       // 信息类别号 → 是否保留本包
        long infoTime = le.readU64();      // 发报时间（高32秒 / 低32 ns）
        int seqNo = le.readU32();
        int packAmount = le.readU32();
        int packNo = le.readU32();
        int dataLen = le.readU32();        // 报文内容总长（不含指令头）
        int version = le.readU16() & 0xffff;
        le.readBytes(6);                   // reserve

        Head3Snapshot snap = new Head3Snapshot();
        snap.packHead = packHead;
        snap.curPackLen = curPackLen;
        snap.tgtAddr = tgtAddr;
        snap.srcAddr = srcAddr;
        snap.infoType = infoType;
        snap.infoTime = infoTime;
        snap.seqNo = seqNo;
        snap.packAmount = packAmount;
        snap.packNo = packNo;
        snap.dataLen = dataLen;
        snap.version = version;
        snap.bufferLength = packet.length;
        snap.infoTimeText = formatInfoTime(infoTime);
        lastHead3.set(snap);

        stats.packets++;
        if (packHead != HEAD3_PACK_HEAD) {
            stats.skippedBadMagic++;
            stats.head3PackHeadMismatch++;
            return out;
        }
        if (curPackLen > 0 && curPackLen > packet.length) {
            stats.skippedBadLength++;
            return out;
        }

        if (log.isInfoEnabled() && (stats.packets <= 5 || stats.packets % 200 == 0)) {
            log.info("Head3: {}", snap);
        }

        // 按 Head3.infoType 过滤；非保留类型整包丢弃（不落盘）
        int type = infoType & 0xffff;
        if (type != INFO_TYPE_KEEP) {
            stats.skippedOtherType++;
            if (log.isDebugEnabled()) {
                log.debug("跳过包 Head3.infoType=0x{} (keep=0x{})",
                        Integer.toHexString(infoType).toUpperCase(Locale.ENGLISH),
                        Integer.toHexString(INFO_TYPE_KEEP).toUpperCase(Locale.ENGLISH));
            }
            return out;
        }

        stats.b108Packets++;
        LocalDateTime headTime = infoTimeToLocalDateTime(infoTime);
        // 对端在 Head3 与 FFHead 之间可能有固定填充，按联调结果跳过
        le.skipBytes(25);
        return parseFfBody(le, headTime, out);
    }

    /** 解析 FFHead + N×FFData，映射为落盘用 {@link PdwRecord} */
    private List<PdwRecord> parseFfBody(LittleEndian le, LocalDateTime headTime, List<PdwRecord> out) {

        if (le.remaining() < FF_HEAD_LEN) {
            return out;
        }
        // ---- FFHead ----
        int infoLength = le.readU32();
        le.readU64(); // taskId
        le.readU64(); // sFreq
        le.readU64(); // dK
        LocalDateTime ffTime = readSystemTime(le);
        le.readU64(); // fpga
        int longitude = le.readU32();
        int latitude = le.readU32();
        le.readU32(); // height
        le.readU32(); // pitch
        le.readU32(); // roll
        le.readU16(); // speed
        int course = le.readU32();
        int infoNum = le.readU16() & 0xffff; // 后续 FFData 条数

        LocalDateTime baseTime = ffTime != null ? ffTime : headTime;
        Set<String> unique = new HashSet<>();
        for (int i = 0; i < infoNum; i++) {
            if (le.remaining() < FF_DATA_LEN) {
                break;
            }
            int qTsc = le.readU32();
            long pLzx = le.readU64();
            int zLsj = le.readU32();
            short gMdk = le.readU16();
            short fD = le.readU16();
            le.readU16(); // fWgs
            short[] fw = le.readU16Array(28);
            le.readBytes(28);
            le.readU8();
            le.readU8();
            le.readU8();

            List<Short> fwList = new ArrayList<>(28);
            for (int j = 0; j < 28; j++) {
                fwList.add(fw[j]);
            }
            DoaHistChiefZhang.Result doa = DoaHistChiefZhang.fuse(fwList, pLzx / 1000L, 21);
            if (doa.code != 0 || doa.doaMean == 4000) {
                stats.skippedDoa++;
                continue;
            }
            short xhfw01 = DoaHistChiefZhang.toTrueAzimuth(doa.doaMean, course);
            LocalDateTime zcsj = baseTime;
            if (zcsj != null) {
                zcsj = zcsj.plusHours(8).plusNanos((long) qTsc * 10_000L);
            }

            PdwRecord rec = new PdwRecord();
            rec.setFreqMhz(pLzx / 1_000_000.0);
            rec.setSignalBwKhz(gMdk / 1000.0);
            rec.setSignalLevelDb(fD);
            rec.setAzimuthDeg(xhfw01 / 10.0);
            rec.setDetectTime(zcsj);
            rec.setLongitude(longitude / 1_000_000.0);
            rec.setLatitude(latitude / 1_000_000.0);
            rec.setNSignalTime10us(zLsj & 0xffffffffL);
            rec.setNSignalStartTime10us(qTsc & 0xffffffffL);

            String key = rec.getFreqMhz() + "|" + rec.getAzimuthDeg() + "|" + rec.getDetectTime();
            if (unique.add(key)) {
                out.add(rec);
                stats.records++;
            }
        }
        if (log.isInfoEnabled() && (stats.b108Packets <= 5 || stats.b108Packets % 200 == 0)) {
            log.info("FFBody infoLength={} infoNum={} outRecords={} remaining={}",
                    infoLength, infoNum, out.size(), le.remaining());
        }
        return out;
    }

    /** Head3.infoTime：高 32 位为相对 1970-1-1 的秒，低 32 位为秒内计数（ns） */
    private static LocalDateTime infoTimeToLocalDateTime(long infoTime) {
        long sec = (infoTime >>> 32) & 0xffffffffL;
        long ns = infoTime & 0xffffffffL;
        if (sec < 1_000_000_000L || sec > 4_000_000_000L) {
            return null;
        }
        if (ns >= 1_000_000_000L) {
            ns = 0;
        }
        try {
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(sec, ns), ZoneId.systemDefault());
        } catch (Exception e) {
            return null;
        }
    }

    private static String formatInfoTime(long infoTime) {
        LocalDateTime t = infoTimeToLocalDateTime(infoTime);
        return t != null ? t.toString() : ("raw=0x" + Long.toHexString(infoTime));
    }

    private static LocalDateTime readSystemTime(LittleEndian le) {
        int year = le.readU16() & 0xffff;
        int month = le.readU16() & 0xffff;
        le.readU16();
        int day = le.readU16() & 0xffff;
        int hour = le.readU16() & 0xffff;
        int minute = le.readU16() & 0xffff;
        int second = le.readU16() & 0xffff;
        int ms = le.readU16() & 0xffff;
        if (year < 1970 || year > 2100 || month < 1 || month > 12 || day < 1 || day > 31) {
            return null;
        }
        try {
            return LocalDateTime.of(year, month, day, hour, minute, second, ms * 1_000_000);
        } catch (Exception e) {
            return null;
        }
    }
}
